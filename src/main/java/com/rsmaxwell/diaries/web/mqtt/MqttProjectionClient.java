package com.rsmaxwell.diaries.web.mqtt;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.MqttSubscription;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rsmaxwell.diaries.web.config.AppConfig.MqttConfig;
import com.rsmaxwell.diaries.web.config.MqttCredentials;
import com.rsmaxwell.diaries.web.projection.ProjectionEvent;
import com.rsmaxwell.diaries.web.projection.ProjectionService;

public final class MqttProjectionClient implements AutoCloseable, MqttCallback {
    private static final Logger log = LoggerFactory.getLogger(MqttProjectionClient.class);

    private final MqttConfig config;
    private final MqttCredentials credentials;
    private final ProjectionService projection;
    private final TopicParser topicParser;
    private final RetainedMessageDecoder decoder;
    private final ScheduledExecutorService lifecycle;
    private final AtomicBoolean connecting = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final MqttAsyncClient client;
    private final MqttConnectionOptions connectionOptions;

    public MqttProjectionClient(
            MqttConfig config,
            MqttCredentials credentials,
            ProjectionService projection) throws MqttException {
        this.config = config;
        this.credentials = credentials;
        this.projection = projection;
        this.topicParser = new TopicParser(config.topicPrefix());
        this.decoder = new RetainedMessageDecoder(topicParser);
        this.lifecycle = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "diaries-web-mqtt-lifecycle");
            thread.setDaemon(true);
            return thread;
        });

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String actualClientId = config.clientId() + "-" + suffix;
        this.client = new MqttAsyncClient(config.serverUri(), actualClientId, new MemoryPersistence());
        this.client.setCallback(this);
        this.connectionOptions = new MqttConnectionOptions();
        connectionOptions.setUserName(credentials.username());
        connectionOptions.setPassword(credentials.password().getBytes(StandardCharsets.UTF_8));
        connectionOptions.setCleanStart(config.cleanStart());
        connectionOptions.setAutomaticReconnect(true);
        connectionOptions.setKeepAliveInterval(config.keepAliveSeconds());
        connectionOptions.setConnectionTimeout(config.connectTimeoutSeconds());
    }

    public void start() {
        lifecycle.execute(this::connectIfNeeded);
    }

    private void connectIfNeeded() {
        if (closed.get() || client.isConnected() || !connecting.compareAndSet(false, true)) {
            return;
        }
        try {
            log.info("Connecting diaries-web MQTT subscriber to {}", config.serverUri());
            client.connect(connectionOptions)
                    .waitForCompletion(config.connectTimeoutSeconds() * 1000L);
        } catch (MqttException exception) {
            log.warn("MQTT connection failed; retrying in {} seconds: {}",
                    config.reconnectDelaySeconds(),
                    exception.getMessage());
            projection.disconnected("MQTT connection failed");
            scheduleConnectRetry();
        } finally {
            connecting.set(false);
        }
    }

    private void scheduleConnectRetry() {
        if (!closed.get()) {
            lifecycle.schedule(this::connectIfNeeded, config.reconnectDelaySeconds(), TimeUnit.SECONDS);
        }
    }

    @Override
    public void connectComplete(boolean reconnect, String serverURI) {
        lifecycle.execute(() -> subscribeAfterConnection(reconnect));
    }

    private void subscribeAfterConnection(boolean reconnect) {
        if (closed.get()) {
            return;
        }
        try {
            projection.beginReplay(reconnect).join();
            List<String> filters = topicParser.canonicalFilters();
            for (String filter : filters) {
                MqttSubscription subscription = new MqttSubscription(filter, 1);
                client.subscribe(subscription).waitForCompletion(config.connectTimeoutSeconds() * 1000L);
                log.info("Subscribed to canonical retained filter {}", filter);
            }
            projection.subscriptionsAcknowledged().join();
        } catch (Exception exception) {
            projection.failed("MQTT subscription failed", exception);
            log.error("Unable to subscribe to canonical MQTT filters", exception);
        }
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        try {
            ProjectionEvent event = decoder.decode(topic, message.getPayload());
            projection.accept(event);
        } catch (Exception exception) {
            projection.recordInvalidMessage();
            log.warn("Rejected retained message on topic {}: {}", topic, exception.getMessage());
        }
    }

    @Override
    public void disconnected(MqttDisconnectResponse disconnectResponse) {
        String reason = disconnectResponse == null
                ? "MQTT disconnected"
                : "MQTT disconnected (reason code " + disconnectResponse.getReturnCode() + ")";
        projection.disconnected(reason);
        log.warn("{}", reason);
    }

    @Override
    public void mqttErrorOccurred(MqttException exception) {
        log.warn("MQTT client error: {}", exception.getMessage());
    }

    @Override
    public void deliveryComplete(IMqttToken token) {
        // Subscription-only client: no deliveries are expected.
    }

    @Override
    public void authPacketArrived(int reasonCode, MqttProperties properties) {
        // No enhanced authentication exchange is configured.
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        lifecycle.shutdownNow();
        try {
            if (client.isConnected()) {
                client.disconnect().waitForCompletion(5_000);
            }
            client.close();
        } catch (MqttException exception) {
            log.warn("MQTT shutdown did not complete cleanly: {}", exception.getMessage());
        }
    }
}
