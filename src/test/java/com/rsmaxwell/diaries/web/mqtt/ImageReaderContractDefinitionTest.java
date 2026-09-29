package com.rsmaxwell.diaries.web.mqtt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsmaxwell.diaries.web.model.FragmentItem;
import com.rsmaxwell.diaries.web.model.FragmentType;
import com.rsmaxwell.diaries.web.model.ImageItem;
import com.rsmaxwell.diaries.web.projection.ProjectionEvent;
import com.rsmaxwell.diaries.web.projection.ProjectionService;

/**
 * Executable anchors for the 0026 Step 2 reader contract.
 *
 * <p>This remains the Step 2 contract anchor. Step 3 implements Image decoding
 * and unknown-type preservation; Step 5 stores valid Image metadata in immutable
 * projection snapshots. Later steps consume the same contract for resolution and
 * rendering behavior.</p>
 */
class ImageReaderContractDefinitionTest {
    private static final Duration WAIT = Duration.ofSeconds(3);
    private final RetainedMessageDecoder decoder = new RetainedMessageDecoder(new TopicParser("diaries"));

    @Test
    void absentAndExplicitNullImageIdAreEquivalentNoSelectionStates() throws Exception {
        byte[] absent = imageFragmentJson("");
        byte[] explicitNull = imageFragmentJson(",\"imageId\":null");

        FragmentItem absentItem = ((ProjectionEvent.UpsertFragment)
                decoder.decode("diaries/fragments/50", absent)).value();
        FragmentItem explicitNullItem = ((ProjectionEvent.UpsertFragment)
                decoder.decode("diaries/fragments/50", explicitNull)).value();

        assertThat(absentItem.type()).isEqualTo(FragmentType.IMAGE);
        assertThat(absentItem.imageId()).isNull();
        assertThat(explicitNullItem.type()).isEqualTo(FragmentType.IMAGE);
        assertThat(explicitNullItem.imageId()).isNull();
        assertThat(explicitNullItem).isEqualTo(absentItem);
    }

    @Test
    void rejectedLiveReplacementLeavesLastValidEntityUntouchedAndCountsTheRejection() {
        FragmentItem valid = new FragmentItem(
                50, 0, 1829, 6, 26, BigDecimal.ONE, "valid",
                22L, FragmentType.IMAGE, null, null);

        try (ProjectionService service = new ProjectionService(Duration.ofMillis(20), Duration.ofSeconds(2))) {
            service.beginReplay(false).join();
            service.accept(new ProjectionEvent.UpsertFragment(valid)).join();
            service.subscriptionsAcknowledged().join();
            await().atMost(WAIT).until(() -> service.status().ready());

            long generation = service.snapshot().generation();
            long invalidBefore = service.status().invalidMessageCount();
            assertThat(service.snapshot().fragmentsById()).containsEntry(50L, valid);

            byte[] malformedReplacement = ("{\"id\":50,\"version\":1,\"year\":1829,"
                    + "\"month\":6,\"day\":26,\"sequence\":1,\"text\":\"bad\","
                    + "\"pageId\":0,\"type\":\"IMAGE\",\"imageId\":null}")
                    .getBytes(StandardCharsets.UTF_8);

            assertThatThrownBy(() -> decoder.decode("diaries/fragments/50", malformedReplacement))
                    .hasRootCauseInstanceOf(IllegalArgumentException.class);
            service.recordInvalidMessage().join();

            assertThat(service.status().invalidMessageCount()).isEqualTo(invalidBefore + 1);
            assertThat(service.snapshot().generation()).isEqualTo(generation);
            assertThat(service.snapshot().fragmentsById()).containsEntry(50L, valid);
        }
    }

    @Test
    void rejectedLiveImageReplacementLeavesLastValidImageUntouchedAndCountsTheRejection() {
        ImageItem valid = new ImageItem(
                60, 0, "maps/image.png", "image/png", "image.png",
                1600, 900, "ab".repeat(32), "valid", "valid image");

        try (ProjectionService service = new ProjectionService(Duration.ofMillis(20), Duration.ofSeconds(2))) {
            service.beginReplay(false).join();
            service.accept(new ProjectionEvent.UpsertImage(valid)).join();
            service.subscriptionsAcknowledged().join();
            await().atMost(WAIT).until(() -> service.status().ready());

            long generation = service.snapshot().generation();
            long invalidBefore = service.status().invalidMessageCount();
            assertThat(service.snapshot().imageById(60)).contains(valid);

            byte[] malformedReplacement = ("{\"id\":60,\"version\":1,"
                    + "\"relativePath\":\"../escape.png\",\"mimeType\":\"image/png\","
                    + "\"originalFilename\":\"escape.png\",\"width\":16,\"height\":12,"
                    + "\"checksum\":\"" + "ab".repeat(32) + "\",\"caption\":\"bad\",\"altText\":\"\"}")
                    .getBytes(StandardCharsets.UTF_8);

            assertThatThrownBy(() -> decoder.decode("diaries/images/60", malformedReplacement))
                    .hasRootCauseInstanceOf(IllegalArgumentException.class);
            service.recordInvalidMessage().join();

            assertThat(service.status().invalidMessageCount()).isEqualTo(invalidBefore + 1);
            assertThat(service.snapshot().generation()).isEqualTo(generation);
            assertThat(service.snapshot().imageById(60)).contains(valid);
        }
    }

    @Test
    void contractFixtureIsValidJsonAndContainsEveryRequiredStep2Category() throws Exception {
        JsonNode root;
        try (var input = ImageReaderContractDefinitionTest.class
                .getResourceAsStream("/fixtures/image-reader-contract-cases.json")) {
            if (input == null) {
                throw new IllegalArgumentException("missing image-reader-contract-cases.json");
            }
            root = new ObjectMapper().readTree(input);
        }

        assertThat(root.path("contractVersion").asInt()).isEqualTo(1);
        assertThat(root.path("fragmentReferenceCases").isArray()).isTrue();
        assertThat(root.path("fragmentTypeCases").isArray()).isTrue();
        assertThat(root.path("imageMetadataCases").isArray()).isTrue();
        assertThat(root.path("mediaResolutionCases").isArray()).isTrue();
        assertThat(root.path("replacementCases").isArray()).isTrue();

        Set<String> ids = new HashSet<>();
        for (String group : Set.of(
                "fragmentReferenceCases",
                "fragmentTypeCases",
                "imageMetadataCases",
                "mediaResolutionCases",
                "replacementCases")) {
            for (JsonNode item : root.path(group)) {
                String id = item.path("id").asText();
                assertThat(id).as("case id in %s", group).isNotBlank();
                assertThat(ids.add(id)).as("unique contract case id %s", id).isTrue();
                assertThat(item.has("expected") || item.has("expectedKind"))
                        .as("expected outcome for %s", id).isTrue();
            }
        }

        assertThat(ids).contains(
                "image-reference-explicit-null",
                "image-reference-absent",
                "type-future-string",
                "metadata-future-field",
                "metadata-topic-id-mismatch",
                "metadata-zero-byte-tombstone",
                "metadata-json-null",
                "media-image-missing",
                "media-image-invalid",
                "media-image-file-failure",
                "replacement-malformed-live",
                "replacement-malformed-fresh-replay");
    }

    private static byte[] imageFragmentJson(String imageIdMember) {
        return ("{\"id\":50,\"version\":0,\"year\":1829,\"month\":6,\"day\":26,"
                + "\"sequence\":1,\"text\":\"image\",\"pageId\":22,\"type\":\"IMAGE\""
                + imageIdMember + ",\"marqueeId\":null}")
                .getBytes(StandardCharsets.UTF_8);
    }
}
