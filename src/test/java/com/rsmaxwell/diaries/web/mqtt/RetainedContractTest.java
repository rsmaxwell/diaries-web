package com.rsmaxwell.diaries.web.mqtt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsmaxwell.diaries.web.model.DiaryItem;
import com.rsmaxwell.diaries.web.model.FragmentItem;
import com.rsmaxwell.diaries.web.model.FragmentType;
import com.rsmaxwell.diaries.web.model.ImageItem;
import com.rsmaxwell.diaries.web.model.MarqueeItem;
import com.rsmaxwell.diaries.web.model.PageItem;
import com.rsmaxwell.diaries.web.projection.ProjectionEvent;

class RetainedContractTest {
    private final TopicParser topics = new TopicParser("diaries");
    private final RetainedMessageDecoder decoder = new RetainedMessageDecoder(topics);
    private final ObjectMapper json = new ObjectMapper();

    static Stream<Arguments> contracts() {
        return Stream.of(
                Arguments.of("diaries/diaries/11", "/fixtures/diary.json", DiaryItem.class, EntityType.DIARY),
                Arguments.of("diaries/pages/22", "/fixtures/page.json", PageItem.class, EntityType.PAGE),
                Arguments.of("diaries/fragments/33", "/fixtures/fragment.json", FragmentItem.class, EntityType.FRAGMENT),
                Arguments.of("diaries/marquees/44", "/fixtures/marquee.json", MarqueeItem.class, EntityType.MARQUEE),
                Arguments.of("diaries/images/60", "/fixtures/image.json", ImageItem.class, EntityType.IMAGE));
    }

    @ParameterizedTest
    @MethodSource("contracts")
    void decodesExactResponderContractFixtures(
            String topic, String fixture, Class<?> expectedType, EntityType expectedEntityType) throws Exception {
        ProjectionEvent event = decoder.decode(topic, resource(fixture));

        EntityType actualEntityType = switch (event) {
            case ProjectionEvent.UpsertDiary ignored -> EntityType.DIARY;
            case ProjectionEvent.UpsertPage ignored -> EntityType.PAGE;
            case ProjectionEvent.UpsertFragment ignored -> EntityType.FRAGMENT;
            case ProjectionEvent.UpsertMarquee ignored -> EntityType.MARQUEE;
            case ProjectionEvent.UpsertImage ignored -> EntityType.IMAGE;
            case ProjectionEvent.Tombstone item -> item.type();
        };
        assertThat(actualEntityType).isEqualTo(expectedEntityType);
        Object value = switch (event) {
            case ProjectionEvent.UpsertDiary item -> item.value();
            case ProjectionEvent.UpsertPage item -> item.value();
            case ProjectionEvent.UpsertFragment item -> item.value();
            case ProjectionEvent.UpsertMarquee item -> item.value();
            case ProjectionEvent.UpsertImage item -> item.value();
            case ProjectionEvent.Tombstone ignored -> null;
        };
        assertThat(value).isInstanceOf(expectedType);
    }

    @Test
    void exposesExactlyFiveCanonicalReaderFiltersIncludingImages() {
        assertThat(topics.canonicalFilters()).containsExactly(
                "diaries/diaries/+",
                "diaries/pages/+",
                "diaries/fragments/+",
                "diaries/marquees/+",
                "diaries/images/+");
        assertThat(topics.canonicalFilter(EntityType.IMAGE)).isEqualTo("diaries/images/+");
        assertThat(topics.parse("diaries/fragments/33"))
                .isEqualTo(new ParsedTopic(EntityType.FRAGMENT, 33));
        assertThat(topics.parse("diaries/images/60"))
                .isEqualTo(new ParsedTopic(EntityType.IMAGE, 60));
        assertThatThrownBy(() -> topics.parse("diaries/dates/2026/9/1/33"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> topics.parse("diaries/diaries/11/22"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyPayloadIsATombstoneForExistingAndImageEntities() throws Exception {
        assertThat(decoder.decode("diaries/pages/22", new byte[0]))
                .isEqualTo(new ProjectionEvent.Tombstone(EntityType.PAGE, 22));
        assertThat(decoder.decode("diaries/images/60", new byte[0]))
                .isEqualTo(new ProjectionEvent.Tombstone(EntityType.IMAGE, 60));
    }

    @Test
    void normalizesTheRespondersDottedPageExtension() throws Exception {
        ProjectionEvent event = decoder.decode("diaries/pages/22", resource("/fixtures/page.json"));

        assertThat(event)
                .isEqualTo(new ProjectionEvent.UpsertPage(
                        new PageItem(22, 3, 11, "page 001", new BigDecimal("2.0000"),
                                "jpg", 1200, 800)));
    }

    @Test
    void decodesTypedLegacyAndUnknownFragmentPayloadsWithoutCoercingUnknownType() throws Exception {
        ProjectionEvent typedEvent = decoder.decode("diaries/fragments/33", resource("/fixtures/fragment.json"));
        FragmentItem typed = ((ProjectionEvent.UpsertFragment) typedEvent).value();
        assertThat(typed.pageId()).isEqualTo(22);
        assertThat(typed.type()).isEqualTo(FragmentType.MARQUEE);
        assertThat(typed.rawType()).isNull();
        assertThat(typed.effectiveType()).isEqualTo(FragmentType.MARQUEE);

        ProjectionEvent legacyEvent = decoder.decode(
                "diaries/fragments/33", resource("/fixtures/fragment-legacy.json"));
        FragmentItem legacy = ((ProjectionEvent.UpsertFragment) legacyEvent).value();
        assertThat(legacy.pageId()).isNull();
        assertThat(legacy.type()).isNull();
        assertThat(legacy.rawType()).isNull();
        assertThat(legacy.effectiveType()).isEqualTo(FragmentType.MARQUEE);

        byte[] future = ("{\"id\":51,\"version\":0,\"year\":2026,\"month\":9,\"day\":2,"
                + "\"sequence\":1,\"text\":\"future\",\"pageId\":22,\"type\":\"VIDEO\"}")
                .getBytes(StandardCharsets.UTF_8);
        FragmentItem unknown = ((ProjectionEvent.UpsertFragment)
                decoder.decode("diaries/fragments/51", future)).value();
        assertThat(unknown.type()).isEqualTo(FragmentType.UNKNOWN);
        assertThat(unknown.effectiveType()).isEqualTo(FragmentType.UNKNOWN);
        assertThat(unknown.rawType()).isEqualTo("VIDEO");
    }

    @Test
    void decodesExplicitImageFragmentAndIgnoresUnknownFields() throws Exception {
        byte[] payload = ("{\"id\":50,\"version\":0,\"year\":2026,\"month\":9,\"day\":2,"
                + "\"sequence\":1,\"text\":\"image\",\"pageId\":22,\"type\":\"IMAGE\","
                + "\"imageId\":60,\"futureField\":true}").getBytes(StandardCharsets.UTF_8);

        FragmentItem image = ((ProjectionEvent.UpsertFragment)
                decoder.decode("diaries/fragments/50", payload)).value();
        assertThat(image.type()).isEqualTo(FragmentType.IMAGE);
        assertThat(image.rawType()).isNull();
        assertThat(image.imageId()).isEqualTo(60);
        assertThat(image.marqueeId()).isNull();
    }

    @Test
    void decodesCanonicalImageMetadataAndIgnoresUnknownAdditiveFields() throws Exception {
        ImageItem image = ((ProjectionEvent.UpsertImage)
                decoder.decode("diaries/images/60", resource("/fixtures/image.json"))).value();

        assertThat(image).isEqualTo(new ImageItem(
                60,
                3,
                "1829/06/img2893-detail.jpg",
                "image/jpeg",
                "img2893-detail.jpg",
                1600,
                900,
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "Detail from the source page",
                ""));
    }

    @Test
    void absentCaptionAndAltTextDefaultToEmptyButExplicitNullIsRejected() throws Exception {
        Map<String, Object> absent = validImage();
        absent.remove("caption");
        absent.remove("altText");

        ImageItem image = ((ProjectionEvent.UpsertImage)
                decoder.decode("diaries/images/60", json.writeValueAsBytes(absent))).value();
        assertThat(image.caption()).isEmpty();
        assertThat(image.altText()).isEmpty();

        Map<String, Object> nullCaption = validImage();
        nullCaption.put("caption", null);
        assertThatThrownBy(() -> decoder.decode("diaries/images/60", json.writeValueAsBytes(nullCaption)))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("caption");

        Map<String, Object> nullAlt = validImage();
        nullAlt.put("altText", null);
        assertThatThrownBy(() -> decoder.decode("diaries/images/60", json.writeValueAsBytes(nullAlt)))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("altText");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidImageCases")
    void rejectsInvalidCanonicalImageMetadata(ImageMutationCase testCase) {
        Map<String, Object> image = validImage();
        testCase.mutation().accept(image);

        assertThatThrownBy(() -> decoder.decode("diaries/images/60", json.writeValueAsBytes(image)))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    static Stream<ImageMutationCase> invalidImageCases() {
        return Stream.of(
                imageCase("missing id", value -> value.remove("id")),
                imageCase("zero id", value -> value.put("id", 0)),
                imageCase("id wrong type", value -> value.put("id", "60")),
                imageCase("negative version", value -> value.put("version", -1)),
                imageCase("version wrong type", value -> value.put("version", "3")),
                imageCase("missing path", value -> value.remove("relativePath")),
                imageCase("blank path", value -> value.put("relativePath", " ")),
                imageCase("leading slash", value -> value.put("relativePath", "/1829/img.jpg")),
                imageCase("trailing slash", value -> value.put("relativePath", "1829/img.jpg/")),
                imageCase("empty segment", value -> value.put("relativePath", "1829//img.jpg")),
                imageCase("dot segment", value -> value.put("relativePath", "1829/./img.jpg")),
                imageCase("dotdot segment", value -> value.put("relativePath", "1829/../img.jpg")),
                imageCase("backslash path", value -> value.put("relativePath", "1829\\img.jpg")),
                imageCase("colon path", value -> value.put("relativePath", "https:example/img.jpg")),
                imageCase("control path", value -> value.put("relativePath", "1829/\u0001img.jpg")),
                imageCase("non NFC path", value -> value.put("relativePath", "1829/e\u0301.jpg")),
                imageCase("missing mime", value -> value.remove("mimeType")),
                imageCase("unsupported mime", value -> value.put("mimeType", "image/svg+xml")),
                imageCase("missing width", value -> value.remove("width")),
                imageCase("zero width", value -> value.put("width", 0)),
                imageCase("missing height", value -> value.remove("height")),
                imageCase("zero height", value -> value.put("height", 0)),
                imageCase("width wrong type", value -> value.put("width", "1600")),
                imageCase("missing checksum", value -> value.remove("checksum")),
                imageCase("uppercase checksum", value -> value.put("checksum",
                        "ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789")),
                imageCase("short checksum", value -> value.put("checksum", "abc123")),
                imageCase("missing filename", value -> value.remove("originalFilename")),
                imageCase("blank filename", value -> value.put("originalFilename", " ")),
                imageCase("directory filename", value -> value.put("originalFilename", "sub/img.jpg")),
                imageCase("backslash filename", value -> value.put("originalFilename", "sub\\img.jpg")),
                imageCase("colon filename", value -> value.put("originalFilename", "C:img.jpg")),
                imageCase("caption wrong type", value -> value.put("caption", 42)),
                imageCase("altText wrong type", value -> value.put("altText", true)));
    }

    @Test
    void rejectsImageTopicPayloadMismatchJsonNullAndInvalidJson() throws Exception {
        assertThatThrownBy(() -> decoder.decode("diaries/images/61", resource("/fixtures/image.json")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id mismatch");

        assertThatThrownBy(() -> decoder.decode(
                "diaries/images/60", "null".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON object");

        assertThatThrownBy(() -> decoder.decode(
                "diaries/images/60", "{not-json".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(java.io.IOException.class);
    }

    @Test
    void rejectsPayloadIdMismatchAndInvalidRequiredFields() {
        assertThatThrownBy(() -> decoder.decode("diaries/diaries/12", resource("/fixtures/diary.json")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id mismatch");
        assertThatThrownBy(() -> decoder.decode(
                "diaries/pages/22",
                "{\"id\":22,\"version\":0,\"diaryId\":11,\"name\":\"p\",\"sequence\":1,\"extension\":\"../jpg\",\"width\":1,\"height\":1}"
                        .getBytes(StandardCharsets.UTF_8)))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidPresentFragmentRelationshipsAndNonStringType() {
        assertThatThrownBy(() -> decoder.decode(
                "diaries/fragments/50",
                ("{\"id\":50,\"version\":0,\"year\":2026,\"month\":9,\"day\":2,"
                        + "\"sequence\":1,\"text\":\"invalid page\",\"pageId\":0,\"type\":\"MARQUEE\"}")
                        .getBytes(StandardCharsets.UTF_8)))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pageId");

        assertThatThrownBy(() -> decoder.decode(
                "diaries/fragments/50",
                ("{\"id\":50,\"version\":0,\"year\":2026,\"month\":9,\"day\":2,"
                        + "\"sequence\":1,\"text\":\"invalid type\",\"pageId\":22,\"type\":42}")
                        .getBytes(StandardCharsets.UTF_8)))
                .hasRootCauseInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fragment type");
    }

    @Test
    void step11KeepsRetainedImageAndFragmentCompatibilityBoundariesDistinct() throws Exception {
        Map<String, Object> additiveImage = validImage();
        additiveImage.put("futureObject", Map.of("nested", true));
        ImageItem decoded = ((ProjectionEvent.UpsertImage) decoder.decode(
                "diaries/images/60", json.writeValueAsBytes(additiveImage))).value();
        assertThat(decoded.id()).isEqualTo(60L);
        assertThat(decoded.relativePath()).isEqualTo("1829/06/img2893-detail.jpg");

        assertThat(decoder.decode("diaries/images/60", new byte[0]))
                .isEqualTo(new ProjectionEvent.Tombstone(EntityType.IMAGE, 60));
        assertThatThrownBy(() -> decoder.decode("diaries/images/61", json.writeValueAsBytes(additiveImage)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id mismatch");

        byte[] explicitLegacyNull = ("{\"id\":70,\"version\":0,\"year\":2026,\"month\":9,\"day\":20,"
                + "\"sequence\":1,\"text\":\"legacy\",\"pageId\":22,\"type\":null}")
                .getBytes(StandardCharsets.UTF_8);
        FragmentItem legacy = ((ProjectionEvent.UpsertFragment)
                decoder.decode("diaries/fragments/70", explicitLegacyNull)).value();
        assertThat(legacy.type()).isNull();
        assertThat(legacy.rawType()).isNull();
        assertThat(legacy.effectiveType()).isEqualTo(FragmentType.MARQUEE);

        byte[] future = ("{\"id\":71,\"version\":0,\"year\":2026,\"month\":9,\"day\":20,"
                + "\"sequence\":2,\"text\":\"future\",\"pageId\":22,\"type\":\"VIDEO\","
                + "\"imageId\":60,\"futureField\":\"ignored\"}")
                .getBytes(StandardCharsets.UTF_8);
        FragmentItem unknown = ((ProjectionEvent.UpsertFragment)
                decoder.decode("diaries/fragments/71", future)).value();
        assertThat(unknown.type()).isEqualTo(FragmentType.UNKNOWN);
        assertThat(unknown.rawType()).isEqualTo("VIDEO");
        assertThat(unknown.effectiveType()).isEqualTo(FragmentType.UNKNOWN);
        assertThat(unknown.imageId()).isEqualTo(60L);
    }

    private static ImageMutationCase imageCase(String name, Consumer<Map<String, Object>> mutation) {
        return new ImageMutationCase(name, mutation);
    }

    private static Map<String, Object> validImage() {
        Map<String, Object> image = new HashMap<>();
        image.put("id", 60L);
        image.put("version", 3L);
        image.put("relativePath", "1829/06/img2893-detail.jpg");
        image.put("mimeType", "image/jpeg");
        image.put("originalFilename", "img2893-detail.jpg");
        image.put("width", 1600);
        image.put("height", 900);
        image.put("checksum", "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        image.put("caption", "Detail from the source page");
        image.put("altText", "");
        return image;
    }

    private static byte[] resource(String name) throws Exception {
        try (var input = RetainedContractTest.class.getResourceAsStream(name)) {
            if (input == null) {
                throw new IllegalArgumentException("missing fixture " + name);
            }
            return input.readAllBytes();
        }
    }

    private record ImageMutationCase(String name, Consumer<Map<String, Object>> mutation) {
        @Override
        public String toString() {
            return name;
        }
    }
}
