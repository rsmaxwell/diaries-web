package com.rsmaxwell.diaries.web.mqtt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.LinkedBlockingQueue;
import org.testcontainers.images.builder.Transferable;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import com.rsmaxwell.diaries.web.config.AppConfig.MqttConfig;
import com.rsmaxwell.diaries.web.config.MqttCredentials;
import com.rsmaxwell.diaries.web.projection.ProjectionService;

@Testcontainers(disabledWithoutDocker = true)
class MqttReaderAclIntegrationTest {
    private static final String PUBLISHER_PASSWORD = "publisher-secret";
    private static final String READER_PASSWORD = "reader-secret";
    private static final String READER_NO_IMAGE_PASSWORD = "reader-no-image-secret";

    @Container
    private static final GenericContainer<?> MOSQUITTO = new GenericContainer<>(
            DockerImageName.parse("eclipse-mosquitto:2.0.22"))
            .withExposedPorts(1883)
            .withCopyFileToContainer(
                    MountableFile.forClasspathResource("mosquitto/mosquitto.conf"),
                    "/tmp/mosquitto.conf")
            .withCopyToContainer(Transferable.of(brokerAcl(), 0644), "/tmp/diaries-web-acl")
            .withCommand(
                    "sh", "-c",
                    "mosquitto_passwd -b -c /tmp/diaries-web-passwords publisher " + PUBLISHER_PASSWORD
                            + " && mosquitto_passwd -b /tmp/diaries-web-passwords diaries-web " + READER_PASSWORD
                            + " && mosquitto_passwd -b /tmp/diaries-web-passwords reader-no-image "
                            + READER_NO_IMAGE_PASSWORD
                            + " && chmod 644 /tmp/diaries-web-passwords /tmp/diaries-web-acl"
                            + " && mosquitto -c /tmp/mosquitto.conf &"
                            + " while true; do sleep 1; done");

    private static byte[] brokerAcl() {
        try {
            // Gradle runs :diaries-web:test in the web subproject. Exercise the actual local ACL.
            return (Files.readString(Path.of("../config/mosquitto/aclfile.txt")) + """

                    user publisher
                    topic readwrite diaries/#
                    topic readwrite diaries-sync/#

                    user reader-no-image
                    topic read diaries/diaries/+
                    topic read diaries/pages/+
                    topic read diaries/fragments/+
                    topic read diaries/marquees/+
                    """).getBytes(StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot read the shared Diaries ACL", exception);
        }
    }

    @Test
    void readerCanReplayCanonicalImageMetadataWithReadOnlyPermission() throws Exception {
        String uri = brokerUri();
        MqttAsyncClient publisher = connected(uri, "publisher", PUBLISHER_PASSWORD, null);
        CountDownLatch imageReceived = new CountDownLatch(1);
        AtomicReference<String> payload = new AtomicReference<>();
        MqttAsyncClient reader = connected(
                uri,
                "diaries-web",
                READER_PASSWORD,
                new RecordingCallback((topic, message) -> {
                    if (topic.equals("diaries/images/60")) {
                        payload.set(new String(message.getPayload(), StandardCharsets.UTF_8));
                        imageReceived.countDown();
                    }
                }));
        try {
            retain(publisher, "diaries/images/60", fixture("image.json"));

            IMqttToken token = reader.subscribe(new MqttSubscription("diaries/images/+", 1));
            token.waitForCompletion(3_000);
            assertGranted(token, "diaries/images/+");

            assertThat(imageReceived.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(payload.get()).contains("\"id\":60", "\"relativePath\"");
        } finally {
            publisher.publish("diaries/images/60", new byte[0], 1, true).waitForCompletion(3_000);
            close(reader);
            close(publisher);
        }
    }

    @Test
    void readerAclAllowsOnlyTheFiveCanonicalLookupFamilies() throws Exception {
        String uri = brokerUri();
        MqttAsyncClient reader = connected(uri, "diaries-web", READER_PASSWORD, null);
        try {
            for (String filter : List.of(
                    "diaries/diaries/+",
                    "diaries/pages/+",
                    "diaries/fragments/+",
                    "diaries/marquees/+",
                    "diaries/images/+")) {
                IMqttToken token = reader.subscribe(new MqttSubscription(filter, 1));
                token.waitForCompletion(3_000);
                assertGranted(token, filter);
            }

        } finally {
            close(reader);
        }

        for (String filter : List.of(
                "diaries/dates/#",
                "diaries/people/+",
                "diaries/roles/+",
                "diaries/rpc/#",
                "diaries/rpc/%c/response",
                "diaries-sync/#",
                "diaries/images/+/nested")) {
            assertReaderSubscribeDenied(uri, filter);
        }

        assertReaderPublishDenied(uri, "diaries/rpc/request");
        for (String topic : List.of("diaries/diaries/999", "diaries/pages/999",
                "diaries/fragments/999", "diaries/marquees/999", "diaries/images/999", "diaries-sync/barrier")) {
            assertReaderPublishDenied(uri, topic);
        }
    }

    @Test
    void missingImageReadPermissionWithholdsDataDespiteSuccessfulSuback() throws Exception {
        var messages = new LinkedBlockingQueue<String>();
        MqttAsyncClient publisher = connected(brokerUri(), "publisher", PUBLISHER_PASSWORD, null);
        MqttAsyncClient reader = connected(brokerUri(), "reader-no-image", READER_NO_IMAGE_PASSWORD,
                new RecordingCallback((topic, message) -> messages.add(topic)));
        try {
            retain(publisher, "diaries/images/60", fixture("image.json"));
            var subscription = reader.subscribe(new MqttSubscription[] {
                    new MqttSubscription("diaries/images/+", 1),
                    new MqttSubscription("diaries/diaries/+", 1) });
            subscription.waitForCompletion(3000);
            assertThat(subscription.getReasonCodes()).containsExactly(1, 1);
            retain(publisher, "diaries/images/60", fixture("image.json"));
            publisher.publish("diaries/diaries/998", "control".getBytes(StandardCharsets.UTF_8), 1, false)
                    .waitForCompletion(3000);
            assertThat(messages.poll(3, TimeUnit.SECONDS)).isEqualTo("diaries/diaries/998");
            assertThat(messages.poll(500, TimeUnit.MILLISECONDS)).isNull();
        } finally {
            publisher.publish("diaries/images/60", new byte[0], 1, true).waitForCompletion(3000);
            close(reader);
            close(publisher);
        }
    }

    @Test
    void actualProjectionStoresValidImagesAndRejectsMalformedRetainedMetadata() throws Exception {
        MqttAsyncClient publisher = connected(brokerUri(), "publisher", PUBLISHER_PASSWORD, null);
        try {
            retain(publisher, "diaries/images/60", fixture("image.json"));
            retain(publisher, "diaries/images/61", "{}");
            try (ProjectionService projection = new ProjectionService(
                    Duration.ofMillis(100), Duration.ofSeconds(4));
                    MqttProjectionClient reader = new MqttProjectionClient(mqttConfig(),
                            new MqttCredentials("diaries-web", READER_PASSWORD), projection)) {
                reader.start();
                await().atMost(Duration.ofSeconds(5)).until(() -> projection.status().ready());
                assertThat(projection.snapshot().imageCount()).isEqualTo(1);
                assertThat(projection.snapshot().imageById(60)).isPresent();
                assertThat(projection.status().invalidMessageCount()).isEqualTo(1);
            }
        } finally {
            publisher.publish("diaries/images/60", new byte[0], 1, true).waitForCompletion(3000);
            publisher.publish("diaries/images/61", new byte[0], 1, true).waitForCompletion(3000);
            close(publisher);
        }
    }

    private static void assertReaderSubscribeDenied(String uri, String filter) throws Exception {
        var received = new LinkedBlockingQueue<String>();
        MqttAsyncClient publisher = connected(uri, "publisher", PUBLISHER_PASSWORD, null);
        MqttAsyncClient reader = connected(uri, "diaries-web", READER_PASSWORD,
                new RecordingCallback((topic, message) -> received.add(topic)));
        filter = filter.replace("%c", reader.getClientId());
        String forbiddenTopic = filter.replace("#", "step4-probe").replace("+", "999");
        try {
            retain(publisher, forbiddenTopic, "forbidden-retained");
            // File ACLs enforce delivery, not SUBACK denial.
            reader.subscribe(new MqttSubscription[] {
                    new MqttSubscription(filter, 1), new MqttSubscription("diaries/#", 1)
            }).waitForCompletion(3000);
            publisher.publish(forbiddenTopic, "forbidden-live".getBytes(StandardCharsets.UTF_8), 1, false)
                    .waitForCompletion(3000);
            publisher.publish("diaries/images/998", "control".getBytes(StandardCharsets.UTF_8), 1, false)
                    .waitForCompletion(3000);
            assertThat(received.poll(3, TimeUnit.SECONDS)).isEqualTo("diaries/images/998");
            assertThat(received.poll(500, TimeUnit.MILLISECONDS)).isNull();
        } finally {
            publisher.publish(forbiddenTopic, new byte[0], 1, true).waitForCompletion(3000);
            close(reader);
            close(publisher);
        }
    }

    private static void assertReaderPublishDenied(String uri, String topic) throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        MqttAsyncClient verifier = connected(
                uri,
                "publisher",
                PUBLISHER_PASSWORD,
                new RecordingCallback((ignoredTopic, ignoredMessage) -> received.countDown()));
        MqttAsyncClient reader = connected(uri, "diaries-web", READER_PASSWORD, null);
        try {
            IMqttToken subscribe = verifier.subscribe(new MqttSubscription(topic, 1));
            subscribe.waitForCompletion(3_000);
            assertGranted(subscribe, topic);

            boolean deniedByAckOrDisconnect = false;
            try {
                IMqttToken publish = reader.publish(
                        topic,
                        "forbidden".getBytes(StandardCharsets.UTF_8),
                        1,
                        false);
                publish.waitForCompletion(3_000);
                deniedByAckOrDisconnect = hasFailureReason(publish.getReasonCodes());
            } catch (MqttException exception) {
                deniedByAckOrDisconnect = true;
            }

            assertThat(deniedByAckOrDisconnect)
                    .as("broker must reject reader publish to %s", topic)
                    .isTrue();
            assertThat(received.await(750, TimeUnit.MILLISECONDS))
                    .as("denied publication must not be delivered on %s", topic)
                    .isFalse();
        } finally {
            close(reader);
            close(verifier);
        }
    }

    private static void assertGranted(IMqttToken token, String filter) {
        int[] reasonCodes = token.getReasonCodes();
        assertThat(reasonCodes)
                .as("SUBACK reason codes for %s", filter)
                .isNotNull()
                .hasSize(1);
        assertThat(reasonCodes[0])
                .as("SUBACK must grant %s", filter)
                .isBetween(0, 2);
    }

    private static boolean hasFailureReason(int[] reasonCodes) {
        if (reasonCodes == null || reasonCodes.length == 0) {
            return false;
        }
        for (int reasonCode : reasonCodes) {
            if (reasonCode >= 0x80) {
                return true;
            }
        }
        return false;
    }

    private static String brokerUri() {
        return "tcp://" + MOSQUITTO.getHost() + ":" + MOSQUITTO.getMappedPort(1883);
    }

    private static MqttConfig mqttConfig() {
        return new MqttConfig(
                MOSQUITTO.getHost(),
                MOSQUITTO.getMappedPort(1883),
                "step4-reader",
                "diaries",
                10,
                3,
                1,
                true);
    }

    private static MqttAsyncClient connected(
            String uri,
            String username,
            String password,
            MqttCallback callback) throws Exception {
        MqttAsyncClient client = new MqttAsyncClient(
                uri,
                username + "-" + UUID.randomUUID(),
                new MemoryPersistence());
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

    private static void retain(MqttAsyncClient client, String topic, String payload) throws Exception {
        client.publish(topic, payload.getBytes(StandardCharsets.UTF_8), 1, true)
                .waitForCompletion(5_000);
    }

    private static String fixture(String name) throws Exception {
        try (var input = MqttReaderAclIntegrationTest.class.getResourceAsStream("/fixtures/" + name)) {
            if (input == null) {
                throw new IllegalArgumentException("missing fixture " + name);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void close(MqttAsyncClient client) {
        if (client == null) {
            return;
        }
        try {
            if (client.isConnected()) {
                client.disconnect().waitForCompletion(2_000);
            }
            client.close();
        } catch (MqttException ignored) {
            // A denied operation or broker teardown may already have disconnected it.
        }
    }

    @FunctionalInterface
    private interface MessageConsumer {
        void accept(String topic, MqttMessage message);
    }

    private record RecordingCallback(MessageConsumer consumer) implements MqttCallback {
        @Override
        public void messageArrived(String topic, MqttMessage message) {
            consumer.accept(topic, message);
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
