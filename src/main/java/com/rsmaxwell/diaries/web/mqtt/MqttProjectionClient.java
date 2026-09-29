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
    private static final int SUBSCRIPTION_QOS = 1;

    private final MqttConfig config;
    private final MqttCredentials credentials;
    private final ProjectionService projection;
    private final TopicParser topicParser;
    private final RetainedMessageDecoder decoder;
    private final ScheduledExecutorService lifecycle;
    private final AtomicBoolean connecting = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean acceptingMessages = new AtomicBoolean();
    private final Object messageLock = new Object();
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

        acceptingMessages.set(false);
        try {
            projection.beginReplay(reconnect).join();
            List<String> filters = topicParser.canonicalFilters();
            MqttSubscription[] subscriptions = filters.stream()
                    .map(filter -> new MqttSubscription(filter, SUBSCRIPTION_QOS))
                    .toArray(MqttSubscription[]::new);

            // Retained messages may arrive before the SUBACK has been returned. Accept them
            // into staging, but do not publish the generation until every SUBACK code has
            // been checked below.
            acceptingMessages.set(true);
            IMqttToken token = client.subscribe(subscriptions);
            token.waitForCompletion(config.connectTimeoutSeconds() * 1000L);
            requireSuccessfulSubAck(filters, token.getReasonCodes());

            for (String filter : filters) {
                log.info("Subscribed to canonical retained filter {}", filter);
            }
            projection.subscriptionsAcknowledged().join();
        } catch (Exception exception) {
            // A broker can grant some filters and reject another in the same SUBACK. Stop
            // processing immediately before clearing staging so partial retained state can
            // never leak into the active projection after the failed replay.
            String detail = exception.getMessage();
            String reason = detail == null || detail.isBlank()
                    ? "MQTT subscription failed"
                    : "MQTT subscription failed: " + detail;
            java.util.concurrent.CompletableFuture<Void> failed;
            synchronized (messageLock) {
                acceptingMessages.set(false);
                // Queue failure after every in-flight callback, never before its upsert.
                failed = projection.failed(reason, exception);
            }
            failed.join();
            log.error("Unable to subscribe to all canonical MQTT filters", exception);
        }
    }

    static void requireSuccessfulSubAck(List<String> filters, int[] reasonCodes) {
        if (reasonCodes == null || reasonCodes.length != filters.size()) {
            throw new IllegalStateException(
                    "MQTT SUBACK did not contain one reason code per canonical filter");
        }

        for (int index = 0; index < reasonCodes.length; index++) {
            int reasonCode = reasonCodes[index];
            // MQTT v5 SUBACK success values are Granted QoS 0, 1 and 2. All failure
            // reason codes are >= 0x80.
            if (reasonCode < 0 || reasonCode > 2) {
                throw new IllegalStateException(String.format(
                        "MQTT subscription rejected for %s (SUBACK reason code 0x%02X)",
                        filters.get(index),
                        reasonCode & 0xff));
            }
        }
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        synchronized (messageLock) {
            if (!acceptingMessages.get()) {
                log.debug("Ignoring MQTT message outside an active subscription cycle on topic {}", topic);
                return;
            }
            try {
                ProjectionEvent event = decoder.decode(topic, message.getPayload());
                projection.accept(event);
            } catch (Exception exception) {
                recordRejectedMessage(topic);
                log.warn("Rejected retained message on topic {}: {}", topic, exception.getMessage());
            }
        }
    }

    private void recordRejectedMessage(String topic) {
        try {
            ParsedTopic parsed = topicParser.parse(topic);
            if (parsed.type() == EntityType.IMAGE) {
                projection.recordInvalidImage(parsed.id());
                return;
            }
        } catch (RuntimeException ignored) {
            // The topic itself is malformed; it has no canonical Image identity to retain.
        }
        projection.recordInvalidMessage();
    }

    @Override
    public void disconnected(MqttDisconnectResponse disconnectResponse) {
        String reason = disconnectResponse == null
                ? "MQTT disconnected"
                : "MQTT disconnected (reason code " + disconnectResponse.getReturnCode() + ")";
        synchronized (messageLock) {
            acceptingMessages.set(false);
            projection.disconnected(reason);
        }
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
        acceptingMessages.set(false);
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
