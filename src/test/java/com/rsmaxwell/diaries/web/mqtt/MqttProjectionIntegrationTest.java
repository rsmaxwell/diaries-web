package com.rsmaxwell.diaries.web.mqtt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.MqttSubscription;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import com.rsmaxwell.diaries.web.config.AppConfig.MqttConfig;
import com.rsmaxwell.diaries.web.config.MqttCredentials;
import com.rsmaxwell.diaries.web.TestData;
import com.rsmaxwell.diaries.web.buildinfo.BuildInfo;
import com.rsmaxwell.diaries.web.http.WebServer;
import com.rsmaxwell.diaries.web.projection.ProjectionService;

@Testcontainers(disabledWithoutDocker = true)
class MqttProjectionIntegrationTest {
    private static final String PUBLISHER_PASSWORD = "publisher-secret";
    private static final String READER_PASSWORD = "reader-secret";

    @Container
    private static final GenericContainer<?> MOSQUITTO = new GenericContainer<>(
            DockerImageName.parse("eclipse-mosquitto:2.0.22"))
            .withExposedPorts(1883)
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("mosquitto/mosquitto.conf"),
                    "/tmp/mosquitto.conf")
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("mosquitto/acl"),
                    "/tmp/diaries-web-acl")
            .withCommand(
                    "sh", "-c",
                    "mosquitto_passwd -b -c /tmp/diaries-web-passwords publisher " + PUBLISHER_PASSWORD
                            + " && mosquitto_passwd -b /tmp/diaries-web-passwords reader " + READER_PASSWORD
                            + " && chmod 644 /tmp/diaries-web-passwords /tmp/diaries-web-acl"
                            + " && mosquitto -c /tmp/mosquitto.conf &"
                            + " while true; do sleep 1; done");

    @Test
    void retainedStartupLiveUpdateTombstoneAndReadOnlyAclWorkTogether() throws Exception {
        String uri = "tcp://" + MOSQUITTO.getHost() + ":" + MOSQUITTO.getMappedPort(1883);
        MqttAsyncClient publisher = connected(uri, "publisher", PUBLISHER_PASSWORD);
        try {
            retain(publisher, "diaries/diaries/11", fixture("diary.json"));
            retain(publisher, "diaries/pages/22", fixture("page.json"));
            retain(publisher, "diaries/fragments/33", fixture("fragment.json"));
            retain(publisher, "diaries/marquees/44", fixture("marquee.json"));

            try (ProjectionService projection = new ProjectionService(
                    Duration.ofMillis(200), Duration.ofSeconds(5));
                    MqttProjectionClient subscriber = new MqttProjectionClient(
                            mqttConfig(MOSQUITTO.getHost(), MOSQUITTO.getMappedPort(1883)),
                            new MqttCredentials("reader", READER_PASSWORD),
                            projection);
                    WebServer web = new WebServer(
                            TestData.config(""),
                            projection,
                            new BuildInfo("diaries-web", "integration", "test", "test", "test", "test", "test"))) {
                web.start();
                subscriber.start();
                await().atMost(Duration.ofSeconds(10)).until(() -> projection.status().ready());
                assertThat(projection.snapshot().resolveFragment(33)).isPresent();
                assertThat(get(web, "/diaries/11/2026/09?fragment=33")).contains("A diary entry");

                String updated = fixture("fragment.json").replace("A diary entry", "Updated entry");
                retain(publisher, "diaries/fragments/33", updated);
                await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                        assertThat(projection.snapshot().fragmentsById().get(33L).text())
                                .contains("Updated entry"));
                assertThat(get(web, "/diaries/11/2026/09?fragment=33")).contains("Updated entry");

                publisher.publish("diaries/fragments/33", new byte[0], 1, true).waitForCompletion();
                await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                        assertThat(projection.snapshot().fragmentsById()).doesNotContainKey(33L));

                retain(publisher, "diaries/fragments/33", fixture("fragment.json"));
                await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                        assertThat(projection.snapshot().resolveFragment(33)).isPresent());

                MOSQUITTO.execInContainer("pkill", "mosquitto");
                await().atMost(Duration.ofSeconds(5)).until(() -> !projection.status().ready());
                close(publisher);
                MOSQUITTO.execInContainer(
                        "sh", "-c", "mosquitto -c /tmp/mosquitto.conf >/tmp/restarted.log 2>&1 &");
                await().atMost(Duration.ofSeconds(15)).until(() -> projection.status().ready());
                assertThat(projection.snapshot().diariesById()).isEmpty();
                assertThat(projection.snapshot().fragmentsById()).isEmpty();
            }

            MqttAsyncClient forbiddenPublisher = connected(uri, "reader", READER_PASSWORD);
            CountDownLatch received = new CountDownLatch(1);
            MqttAsyncClient verifier = connected(
                    uri, "publisher", PUBLISHER_PASSWORD, new RecordingCallback(received));
            try {
                verifier.subscribe(new MqttSubscription("diaries/rpc/request", 1)).waitForCompletion(3_000);
                forbiddenPublisher
                        .publish("diaries/rpc/request", "forbidden".getBytes(StandardCharsets.UTF_8), 1, false)
                        .waitForCompletion(3_000);
                assertThat(received.await(750, TimeUnit.MILLISECONDS)).isFalse();
            } finally {
                close(forbiddenPublisher);
                close(verifier);
            }
        } finally {
            close(publisher);
        }
    }

    private static MqttConfig mqttConfig(String host, int port) {
        return new MqttConfig(host, port, "integration-reader", "diaries", 10, 3, 1, true);
    }

    private static MqttAsyncClient connected(String uri, String username, String password) throws Exception {
        return connected(uri, username, password, null);
    }

    private static MqttAsyncClient connected(
            String uri, String username, String password, MqttCallback callback) throws Exception {
        MqttAsyncClient client = new MqttAsyncClient(
                uri, username + "-" + UUID.randomUUID(), new MemoryPersistence());
        if (callback != null) {
            client.setCallback(callback);
        }
        MqttConnectionOptions options = new MqttConnectionOptions();
        options.setUserName(username);
        options.setPassword(password.getBytes(StandardCharsets.UTF_8));
        options.setCleanStart(true);
        client.connect(options).waitForCompletion(5_000);
        return client;
    }

    private static void close(MqttAsyncClient client) {
        try {
            if (client.isConnected()) {
                client.disconnect().waitForCompletion(2_000);
            }
            client.close();
        } catch (MqttException ignored) {
            // The broker may already have disconnected a client denied by the ACL.
        }
    }

    private static void retain(MqttAsyncClient client, String topic, String payload) throws Exception {
        client.publish(topic, payload.getBytes(StandardCharsets.UTF_8), 1, true).waitForCompletion(5_000);
    }

    private static String get(WebServer web, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + web.port() + path))
                .GET()
                .build();
        return HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString())
                .body();
    }

    private static String fixture(String name) throws Exception {
        try (var input = MqttProjectionIntegrationTest.class.getResourceAsStream("/fixtures/" + name)) {
            if (input == null) {
                throw new IllegalArgumentException("missing fixture " + name);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private record RecordingCallback(CountDownLatch received) implements MqttCallback {
        @Override
        public void messageArrived(String topic, MqttMessage message) {
            received.countDown();
        }

        @Override
        public void disconnected(MqttDisconnectResponse disconnectResponse) {
        }

        @Override
        public void mqttErrorOccurred(MqttException exception) {
        }

        @Override
        public void deliveryComplete(IMqttToken token) {
        }

        @Override
        public void connectComplete(boolean reconnect, String serverURI) {
        }

        @Override
        public void authPacketArrived(int reasonCode, MqttProperties properties) {
        }
    }
}
