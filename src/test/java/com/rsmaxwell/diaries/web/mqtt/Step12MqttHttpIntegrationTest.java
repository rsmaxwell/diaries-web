package com.rsmaxwell.diaries.web.mqtt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.images.builder.Transferable;

import com.rsmaxwell.diaries.web.TestData;
import com.rsmaxwell.diaries.web.buildinfo.BuildInfo;
import com.rsmaxwell.diaries.web.config.AppConfig.MqttConfig;
import com.rsmaxwell.diaries.web.config.MqttCredentials;
import com.rsmaxwell.diaries.web.http.WebServer;
import com.rsmaxwell.diaries.web.projection.ProjectionService;
import com.rsmaxwell.diaries.web.projection.ResolvedFragment.MediaState;
import com.rsmaxwell.diaries.web.projection.SourceConnectionState;

/**
 * Step 12 real-broker verification: MQTT retained state is consumed by the
 * production decoder/projection client and then rendered by the production
 * HTTP server. No responder RPC or database shortcuts are used here.
 */
@Testcontainers(disabledWithoutDocker = true)
class Step12MqttHttpIntegrationTest {
    private static final String PUBLISHER_PASSWORD = "publisher-secret";
    private static final String READER_PASSWORD = "reader-secret";
    private static final String READER_NO_IMAGE_PASSWORD = "reader-no-image-secret";

    private static final int PRODUCTION_DIARIES = 10;
    private static final int PRODUCTION_PAGES = 683;
    private static final int PRODUCTION_FRAGMENTS = 2329;
    private static final int PRODUCTION_MARQUEES = 2269;
    private static final int PRODUCTION_IMAGES = 85;
    private static final int PRODUCTION_TOPIC_COUNT = PRODUCTION_DIARIES + PRODUCTION_PAGES
            + PRODUCTION_FRAGMENTS + PRODUCTION_MARQUEES + PRODUCTION_IMAGES;

    @Test
    void retainedReplayLiveImageChangesAndFreshReconnectReachHttpWithoutStaleState() throws Exception {
        try (GenericContainer<?> broker = broker()) {
            broker.start();
            String uri = brokerUri(broker);
            MqttAsyncClient publisher = connected(uri, "publisher", PUBLISHER_PASSWORD, null);
            try {
                publishSmallMixedFixture(publisher);

                try (ProjectionService projection = new ProjectionService(
                        Duration.ofSeconds(2), Duration.ofSeconds(12));
                        MqttProjectionClient reader = new MqttProjectionClient(
                                mqttConfig(broker, "step12-reader"),
                                new MqttCredentials("diaries-web", READER_PASSWORD),
                                projection);
                        WebServer web = web(projection)) {
                    web.start();
                    assertThat(http(web, "/health/ready").statusCode()).isEqualTo(503);

                    // Late subscriber: every canonical object already exists as retained state.
                    reader.start();
                    await().atMost(Duration.ofSeconds(12)).until(() -> projection.status().ready());
                    assertThat(projection.snapshot().diariesById()).hasSize(1);
                    assertThat(projection.snapshot().pagesById()).hasSize(1);
                    assertThat(projection.snapshot().fragmentsById()).hasSize(3);
                    assertThat(projection.snapshot().marqueesById()).hasSize(1);
                    assertThat(projection.snapshot().imageCount()).isEqualTo(1);
                    assertThat(projection.snapshot().resolveFragment(35).orElseThrow().mediaState())
                            .isEqualTo(MediaState.AVAILABLE);
                    assertThat(projection.snapshot().resolveFragment(36).orElseThrow().image().orElseThrow().id())
                            .isEqualTo(60);

                    HttpResponse<String> ready = http(web, "/health/ready");
                    assertThat(ready.statusCode()).isEqualTo(200);
                    assertThat(ready.body()).contains(
                            "\"status\":\"UP\"", "\"fragments\":3", "\"images\":1");
                    assertThat(http(web, "/diaries/11/2026/09?fragment=35").body()).contains(
                            "data-reader-fragment=\"35\"",
                            "data-fragment-type=\"IMAGE\"",
                            "data-media-state=\"AVAILABLE\"",
                            "Detail from the source page");

                    // One catalogue Image is reused by two Fragments. A metadata update must
                    // atomically refresh both references and the HTTP projection.
                    long beforeUpdate = projection.snapshot().generation();
                    retain(publisher, "diaries/images/60",
                            imagePayload(60, 4, "1829/06/updated detail.jpg", "Updated catalogue caption"));
                    await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                        assertThat(projection.snapshot().generation()).isGreaterThan(beforeUpdate);
                        assertThat(projection.snapshot().imageById(60).orElseThrow().version()).isEqualTo(4);
                        assertThat(projection.snapshot().resolveFragment(35).orElseThrow().image().orElseThrow().version())
                                .isEqualTo(4);
                        assertThat(projection.snapshot().resolveFragment(36).orElseThrow().image().orElseThrow().version())
                                .isEqualTo(4);
                    });
                    assertThat(http(web, "/diaries/11/2026/09?fragment=35").body())
                            .contains("Updated catalogue caption", "updated%20detail.jpg");

                    // Tombstone removes only Image metadata; both Page-owned Fragment rows
                    // remain in chronology and degrade to MISSING_METADATA.
                    tombstone(publisher, "diaries/images/60");
                    await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                        assertThat(projection.snapshot().imageById(60)).isEmpty();
                        assertThat(projection.snapshot().resolveFragment(35).orElseThrow().mediaState())
                                .isEqualTo(MediaState.MISSING_METADATA);
                        assertThat(projection.snapshot().resolveFragment(36).orElseThrow().mediaState())
                                .isEqualTo(MediaState.MISSING_METADATA);
                        assertThat(projection.snapshot().relationshipDiagnostics().imagesReferencedButMissing())
                                .isEqualTo(2);
                    });
                    assertThat(http(web, "/diaries/11/2026/09?fragment=35").body())
                            .contains("data-media-state=\"MISSING_METADATA\"", "Image unavailable");

                    // Restore the shared Image, then exercise reversed live arrival: Fragment
                    // first, Image metadata second. The same immutable projection repairs itself.
                    retain(publisher, "diaries/images/60", fixture("step12/image-60.json"));
                    await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                            assertThat(projection.snapshot().resolveFragment(35).orElseThrow().mediaState())
                                    .isEqualTo(MediaState.AVAILABLE));

                    retain(publisher, "diaries/fragments/37", imageFragmentPayload(37, 61, 5, "Late image metadata"));
                    await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                            assertThat(projection.snapshot().resolveFragment(37).orElseThrow().mediaState())
                                    .isEqualTo(MediaState.MISSING_METADATA));
                    assertThat(http(web, "/diaries/11/2026/09?fragment=37").body())
                            .contains("data-media-state=\"MISSING_METADATA\"");

                    retain(publisher, "diaries/images/61",
                            imagePayload(61, 0, "1829/06/late image.png", "Late catalogue image"));
                    await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                            assertThat(projection.snapshot().resolveFragment(37).orElseThrow().mediaState())
                                    .isEqualTo(MediaState.AVAILABLE));
                    assertThat(http(web, "/diaries/11/2026/09?fragment=37").body())
                            .contains("data-media-state=\"AVAILABLE\"", "Late catalogue image");

                    // Restart the non-persistent broker. The active generation remains available
                    // internally while the reader is disconnected/replaying, but HTTP stays 503.
                    // Publish a changed retained generation before replay quiet completion.
                    long generationBeforeReconnect = projection.snapshot().generation();
                    assertThat(projection.snapshot().imageById(60)).isPresent();
                    close(publisher);
                    broker.execInContainer("pkill", "mosquitto");
                    await().atMost(Duration.ofSeconds(5)).until(() -> !projection.status().ready());
                    assertThat(http(web, "/health/ready").statusCode()).isEqualTo(503);

                    broker.execInContainer("sh", "-c",
                            "mosquitto -c /tmp/mosquitto.conf >/tmp/restarted.log 2>&1 &");
                    awaitBroker(broker);
                    publisher = connected(uri, "publisher", PUBLISHER_PASSWORD, null);
                    publishChangedReconnectFixture(publisher);

                    await().atMost(Duration.ofSeconds(8)).until(() ->
                            projection.snapshot().sourceConnectionState() == SourceConnectionState.REPLAYING);
                    assertThat(projection.status().ready()).isFalse();
                    assertThat(projection.snapshot().generation()).isEqualTo(generationBeforeReconnect);
                    assertThat(projection.snapshot().imageById(60)).isPresent();
                    assertThat(http(web, "/diaries/11/2026/09?fragment=50").statusCode()).isEqualTo(503);

                    await().atMost(Duration.ofSeconds(12)).until(() -> projection.status().ready());
                    assertThat(projection.snapshot().generation()).isGreaterThan(generationBeforeReconnect);
                    assertThat(projection.snapshot().imageById(60)).isEmpty();
                    assertThat(projection.snapshot().imageById(62)).isPresent();
                    assertThat(projection.snapshot().fragmentsById()).containsOnlyKeys(50L);
                    assertThat(http(web, "/diaries/11/2026/09?fragment=50").body()).contains(
                            "Fresh reconnect image",
                            "data-media-state=\"AVAILABLE\"");
                }
            } finally {
                close(publisher);
            }
        }
    }

    @Test
    void missingImagePermissionDegradesProjectionWhileActualWebIdentityRemainsRpcFreeAndReadOnly() throws Exception {
        try (GenericContainer<?> broker = broker()) {
            broker.start();
            String uri = brokerUri(broker);
            MqttAsyncClient publisher = connected(uri, "publisher", PUBLISHER_PASSWORD, null);
            try {
                retain(publisher, "diaries/diaries/11", fixture("diary.json"));
                retain(publisher, "diaries/pages/22", fixture("page.json"));
                retain(publisher, "diaries/fragments/35", fixture("step12/fragment-image-35.json"));
                retain(publisher, "diaries/images/60", fixture("step12/image-60.json"));

                CountDownLatch rpcReceived = new CountDownLatch(1);
                MqttAsyncClient verifier = connected(uri, "publisher", PUBLISHER_PASSWORD,
                        new RecordingCallback((topic, message) -> rpcReceived.countDown()));
                verifier.subscribe(new MqttSubscription("diaries/rpc/request", 1)).waitForCompletion(3_000);
                try {
                    // Mosquitto file ACLs can return successful SUBACK while silently withholding
                    // unauthorized retained/live data. Verify the real application behavior for
                    // that negative-control identity rather than assuming SUBACK implies access.
                    try (ProjectionService projection = new ProjectionService(
                            Duration.ofMillis(300), Duration.ofSeconds(6));
                            MqttProjectionClient reader = new MqttProjectionClient(
                                    mqttConfig(broker, "step12-no-image-reader"),
                                    new MqttCredentials("reader-no-image", READER_NO_IMAGE_PASSWORD),
                                    projection);
                            WebServer web = web(projection)) {
                        web.start();
                        reader.start();
                        await().atMost(Duration.ofSeconds(8)).until(() -> projection.status().ready());
                        assertThat(projection.status().subscriptionsAcknowledged()).isTrue();
                        assertThat(projection.snapshot().fragmentsById()).containsKey(35L);
                        assertThat(projection.snapshot().imageCount()).isZero();
                        assertThat(projection.snapshot().relationshipDiagnostics().imagesReferencedButMissing())
                                .isEqualTo(1);
                        assertThat(http(web, "/health/ready").body())
                                .contains("\"status\":\"UP\"", "\"images\":0");
                        assertThat(http(web, "/diaries/11/2026/09?fragment=35").body()).contains(
                                "data-media-state=\"MISSING_METADATA\"", "Image unavailable");
                        // The projection subscriber is RPC-free; it must not emit a request merely
                        // because it connected or completed retained replay.
                        assertThat(rpcReceived.await(750, TimeUnit.MILLISECONDS)).isFalse();
                    }

                    // The actual diaries-web ACL must not allow writes or RPC.
                    MqttAsyncClient webIdentity = connected(uri, "diaries-web", READER_PASSWORD, null);
                    try {
                        assertPublishDenied(webIdentity, "diaries/rpc/request");
                        assertThat(rpcReceived.await(750, TimeUnit.MILLISECONDS)).isFalse();
                        assertPublishDenied(webIdentity, "diaries/images/999");
                    } finally {
                        close(webIdentity);
                    }
                } finally {
                    close(verifier);
                }
            } finally {
                close(publisher);
            }
        }
    }

    @Test
    void productionSizedRetainedTreeReplaysWithinRuntimeLimitsAndPublishesOneCompleteHttpGeneration() throws Exception {
        try (GenericContainer<?> broker = broker()) {
            broker.start();
            String uri = brokerUri(broker);
            MqttAsyncClient publisher = connected(uri, "publisher", PUBLISHER_PASSWORD, null);
            try {
                publishProductionSizedTree(publisher);
                assertThat(PRODUCTION_TOPIC_COUNT).isEqualTo(5376);

                Instant started = Instant.now();
                try (ProjectionService projection = new ProjectionService(
                        Duration.ofMillis(1500), Duration.ofSeconds(30));
                        MqttProjectionClient reader = new MqttProjectionClient(
                                mqttConfig(broker, "step12-large-reader"),
                                new MqttCredentials("diaries-web", READER_PASSWORD),
                                projection);
                        WebServer web = web(projection)) {
                    web.start();
                    reader.start();
                    await().atMost(Duration.ofSeconds(30)).until(() -> projection.status().ready());

                    assertThat(Duration.between(started, Instant.now())).isLessThan(Duration.ofSeconds(30));
                    assertThat(projection.snapshot().diariesById()).hasSize(PRODUCTION_DIARIES);
                    assertThat(projection.snapshot().pagesById()).hasSize(PRODUCTION_PAGES);
                    assertThat(projection.snapshot().fragmentsById()).hasSize(PRODUCTION_FRAGMENTS);
                    assertThat(projection.snapshot().marqueesById()).hasSize(PRODUCTION_MARQUEES);
                    assertThat(projection.snapshot().imageCount()).isEqualTo(PRODUCTION_IMAGES);
                    assertThat(projection.status().invalidMessageCount()).isZero();
                    assertThat(projection.snapshot().relationshipDiagnostics().fragmentsWithoutPage()).isZero();
                    assertThat(projection.snapshot().relationshipDiagnostics().imagesReferencedButMissing()).isZero();
                    assertThat(projection.snapshot().sourceConnectionState()).isEqualTo(SourceConnectionState.READY);

                    HttpResponse<String> readiness = http(web, "/health/ready");
                    assertThat(readiness.statusCode()).isEqualTo(200);
                    assertThat(readiness.body()).contains(
                            "\"diaries\":10",
                            "\"pages\":683",
                            "\"fragments\":2329",
                            "\"marquees\":2269",
                            "\"images\":85");
                    assertThat(http(web, "/diaries/1/2026/09?fragment=12270").body()).contains(
                            "data-reader-fragment=\"12270\"",
                            "data-fragment-type=\"IMAGE\"",
                            "data-media-state=\"AVAILABLE\"");
                }
            } finally {
                close(publisher);
            }
        }
    }

    private static GenericContainer<?> broker() {
        return new GenericContainer<>(DockerImageName.parse("eclipse-mosquitto:2.0.22"))
                .withExposedPorts(1883)
                .withCopyToContainer(Transferable.of(brokerConfig(), 0644), "/tmp/mosquitto.conf")
                .withCopyToContainer(Transferable.of(brokerAcl(), 0644), "/tmp/diaries-web-acl")
                .withCommand("sh", "-c",
                        "mosquitto_passwd -b -c /tmp/diaries-web-passwords publisher " + PUBLISHER_PASSWORD
                                + " && mosquitto_passwd -b /tmp/diaries-web-passwords diaries-web " + READER_PASSWORD
                                + " && mosquitto_passwd -b /tmp/diaries-web-passwords reader-no-image "
                                + READER_NO_IMAGE_PASSWORD
                                + " && chmod 644 /tmp/diaries-web-passwords /tmp/diaries-web-acl"
                                + " && mosquitto -c /tmp/mosquitto.conf >/tmp/mosquitto.log 2>&1 &"
                                + " while true; do sleep 1; done");
    }

    private static byte[] brokerConfig() {
        return """
                listener 1883
                allow_anonymous false
                password_file /tmp/diaries-web-passwords
                acl_file /tmp/diaries-web-acl
                persistence false
                log_dest stdout
                max_inflight_messages 20
                max_queued_messages 10000
                """.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] brokerAcl() {
        try {
            String sharedAcl = Files.readString(Path.of("../config/mosquitto/aclfile.txt"));
            return (sharedAcl + """

                    user publisher
                    topic readwrite diaries/#
                    topic readwrite diaries-sync/#

                    user reader-no-image
                    topic deny diaries/rpc/#
                    topic read diaries/diaries/+
                    topic read diaries/pages/+
                    topic read diaries/fragments/+
                    topic read diaries/marquees/+
                    """).getBytes(StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot read the shared Diaries ACL", exception);
        }
    }

    private static void publishSmallMixedFixture(MqttAsyncClient publisher) throws Exception {
        retain(publisher, "diaries/diaries/11", fixture("diary.json"));
        retain(publisher, "diaries/pages/22", fixture("page.json"));
        retain(publisher, "diaries/fragments/33", fixture("fragment.json"));
        retain(publisher, "diaries/marquees/44", fixture("marquee.json"));
        retain(publisher, "diaries/images/60", fixture("step12/image-60.json"));
        retain(publisher, "diaries/fragments/35", fixture("step12/fragment-image-35.json"));
        retain(publisher, "diaries/fragments/36", fixture("step12/fragment-image-36.json"));
    }

    private static void publishChangedReconnectFixture(MqttAsyncClient publisher) throws Exception {
        retain(publisher, "diaries/diaries/11", fixture("diary.json"));
        retain(publisher, "diaries/pages/22", fixture("page.json"));
        retain(publisher, "diaries/images/62",
                imagePayload(62, 0, "1829/06/fresh reconnect.png", "Fresh reconnect image"));
        retain(publisher, "diaries/fragments/50", imageFragmentPayload(50, 62, 6, "Fresh reconnect fragment"));
    }

    private static void publishProductionSizedTree(MqttAsyncClient publisher) throws Exception {
        List<PendingPublish> batch = new ArrayList<>(10);
        for (int diary = 1; diary <= PRODUCTION_DIARIES; diary++) {
            batchRetain(publisher, batch, "diaries/diaries/" + diary,
                    "{\"id\":" + diary + ",\"version\":0,\"name\":\"Large diary " + diary
                            + "\",\"sequence\":" + diary + ".0}");
        }
        for (int index = 0; index < PRODUCTION_PAGES; index++) {
            long id = 1001L + index;
            long diaryId = (index % PRODUCTION_DIARIES) + 1L;
            batchRetain(publisher, batch, "diaries/pages/" + id,
                    "{\"id\":" + id + ",\"version\":0,\"diaryId\":" + diaryId
                            + ",\"name\":\"page " + id + "\",\"sequence\":" + (index + 1)
                            + ".0,\"extension\":\".jpg\",\"width\":1200,\"height\":800}");
        }
        for (int index = 0; index < PRODUCTION_IMAGES; index++) {
            long id = 30001L + index;
            batchRetain(publisher, batch, "diaries/images/" + id,
                    imagePayload(id, 0, "large/images/image-" + id + ".png", "Large image " + id));
        }
        for (int index = 0; index < PRODUCTION_FRAGMENTS; index++) {
            long id = 10001L + index;
            long pageId = 1001L + (index % PRODUCTION_PAGES);
            int day = (index % 28) + 1;
            if (index < PRODUCTION_MARQUEES) {
                long marqueeId = 20001L + index;
                batchRetain(publisher, batch, "diaries/fragments/" + id,
                        marqueeFragmentPayload(id, pageId, marqueeId, day));
            } else {
                long imageId = 30001L + ((index - PRODUCTION_MARQUEES) % 60);
                batchRetain(publisher, batch, "diaries/fragments/" + id,
                        imageFragmentPayload(id, imageId, pageId, day, "Large image fragment " + id));
            }
        }
        for (int index = 0; index < PRODUCTION_MARQUEES; index++) {
            long id = 20001L + index;
            long fragmentId = 10001L + index;
            long pageId = 1001L + (index % PRODUCTION_PAGES);
            batchRetain(publisher, batch, "diaries/marquees/" + id,
                    "{\"id\":" + id + ",\"version\":0,\"pageId\":" + pageId
                            + ",\"fragmentId\":" + fragmentId
                            + ",\"rectangle\":{\"x\":10.0,\"y\":20.0,\"width\":100.0,\"height\":80.0}}");
        }
        flush(batch);
    }

    private static String marqueeFragmentPayload(long id, long pageId, long marqueeId, int day) {
        return "{\"id\":" + id + ",\"version\":0,\"year\":2026,\"month\":9,\"day\":" + day
                + ",\"sequence\":" + id + ".0,\"text\":\"<p>Large marquee fragment " + id
                + "</p>\",\"pageId\":" + pageId + ",\"type\":\"MARQUEE\",\"imageId\":null,\"marqueeId\":"
                + marqueeId + "}";
    }

    private static String imageFragmentPayload(long id, long imageId, int day, String text) {
        return imageFragmentPayload(id, imageId, 22, day, text);
    }

    private static String imageFragmentPayload(long id, long imageId, long pageId, int day, String text) {
        return "{\"id\":" + id + ",\"version\":0,\"year\":2026,\"month\":9,\"day\":" + day
                + ",\"sequence\":" + id + ".0,\"text\":\"<p>" + text
                + "</p>\",\"pageId\":" + pageId + ",\"type\":\"IMAGE\",\"imageId\":" + imageId
                + ",\"marqueeId\":null}";
    }

    private static String imagePayload(long id, long version, String relativePath, String caption) {
        return "{\"id\":" + id + ",\"version\":" + version + ",\"relativePath\":\"" + relativePath
                + "\",\"mimeType\":\"image/png\",\"originalFilename\":\"image-" + id
                + ".png\",\"width\":1600,\"height\":900,\"checksum\":\"" + "ab".repeat(32)
                + "\",\"caption\":\"" + caption + "\",\"altText\":\"Image " + id + "\"}";
    }

    private static WebServer web(ProjectionService projection) {
        return new WebServer(
                TestData.config(""), projection,
                new BuildInfo("diaries-web", "step12", "test", "test", "test", "test", "test"));
    }

    private static MqttConfig mqttConfig(GenericContainer<?> broker, String clientId) {
        return new MqttConfig(
                broker.getHost(), broker.getMappedPort(1883), clientId, "diaries", 10, 3, 1, true);
    }

    private static String brokerUri(GenericContainer<?> broker) {
        return "tcp://" + broker.getHost() + ":" + broker.getMappedPort(1883);
    }

    private static void awaitBroker(GenericContainer<?> broker) {
        await().atMost(Duration.ofSeconds(8)).pollInterval(Duration.ofMillis(100)).until(() -> {
            try (Socket ignored = new Socket(broker.getHost(), broker.getMappedPort(1883))) {
                return true;
            } catch (Exception exception) {
                return false;
            }
        });
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

    private static void retain(MqttAsyncClient client, String topic, String payload) throws Exception {
        client.publish(topic, payload.getBytes(StandardCharsets.UTF_8), 1, true).waitForCompletion(5_000);
    }

    private static void tombstone(MqttAsyncClient client, String topic) throws Exception {
        client.publish(topic, new byte[0], 1, true).waitForCompletion(5_000);
    }

    private static void batchRetain(
            MqttAsyncClient client, List<PendingPublish> batch, String topic, String payload) throws Exception {
        batch.add(new PendingPublish(topic,
                client.publish(topic, payload.getBytes(StandardCharsets.UTF_8), 1, true)));
        if (batch.size() == 8) {
            flush(batch);
        }
    }

    private static void flush(List<PendingPublish> batch) throws Exception {
        for (PendingPublish pending : batch) {
            pending.token().waitForCompletion(5_000);
        }
        batch.clear();
    }

    private static HttpResponse<String> http(WebServer web, String path) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + web.port() + path))
                .GET()
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static void assertPublishDenied(MqttAsyncClient client, String topic) throws Exception {
        boolean denied = false;
        try {
            IMqttToken publish = client.publish(
                    topic, "forbidden".getBytes(StandardCharsets.UTF_8), 1, false);
            publish.waitForCompletion(3_000);
            int[] reasonCodes = publish.getReasonCodes();
            if (reasonCodes != null) {
                for (int code : reasonCodes) {
                    denied |= code >= 0x80;
                }
            }
        } catch (MqttException exception) {
            denied = true;
        }
        assertThat(denied).as("broker must reject diaries-web publish to %s", topic).isTrue();
    }

    private static String fixture(String name) throws Exception {
        try (var input = Step12MqttHttpIntegrationTest.class.getResourceAsStream("/fixtures/" + name)) {
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
            // A broker restart or denied publication may already have disconnected it.
        }
    }

    private record PendingPublish(String topic, IMqttToken token) {
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
