package com.rsmaxwell.diaries.web.mqtt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.rsmaxwell.diaries.web.model.DiaryItem;
import com.rsmaxwell.diaries.web.model.FragmentItem;
import com.rsmaxwell.diaries.web.model.MarqueeItem;
import com.rsmaxwell.diaries.web.model.PageItem;
import com.rsmaxwell.diaries.web.projection.ProjectionEvent;

class RetainedContractTest {
    private final TopicParser topics = new TopicParser("diaries");
    private final RetainedMessageDecoder decoder = new RetainedMessageDecoder(topics);

    static Stream<Arguments> contracts() {
        return Stream.of(
                Arguments.of("diaries/diaries/11", "/fixtures/diary.json", DiaryItem.class, EntityType.DIARY),
                Arguments.of("diaries/pages/22", "/fixtures/page.json", PageItem.class, EntityType.PAGE),
                Arguments.of("diaries/fragments/33", "/fixtures/fragment.json", FragmentItem.class, EntityType.FRAGMENT),
                Arguments.of("diaries/marquees/44", "/fixtures/marquee.json", MarqueeItem.class, EntityType.MARQUEE));
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
            case ProjectionEvent.Tombstone item -> item.type();
        };
        assertThat(actualEntityType).isEqualTo(expectedEntityType);
        Object value = switch (event) {
            case ProjectionEvent.UpsertDiary item -> item.value();
            case ProjectionEvent.UpsertPage item -> item.value();
            case ProjectionEvent.UpsertFragment item -> item.value();
            case ProjectionEvent.UpsertMarquee item -> item.value();
            case ProjectionEvent.Tombstone ignored -> null;
        };
        assertThat(value).isInstanceOf(expectedType);
    }

    @Test
    void exposesOnlyCanonicalLookupFilters() {
        assertThat(topics.canonicalFilters()).containsExactly(
                "diaries/diaries/+",
                "diaries/pages/+",
                "diaries/fragments/+",
                "diaries/marquees/+");
        assertThat(topics.parse("diaries/fragments/33"))
                .isEqualTo(new ParsedTopic(EntityType.FRAGMENT, 33));
        assertThatThrownBy(() -> topics.parse("diaries/dates/2026/9/1/33"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> topics.parse("diaries/diaries/11/22"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyPayloadIsATombstone() throws Exception {
        assertThat(decoder.decode("diaries/pages/22", new byte[0]))
                .isEqualTo(new ProjectionEvent.Tombstone(EntityType.PAGE, 22));
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

    private static byte[] resource(String name) throws Exception {
        try (var input = RetainedContractTest.class.getResourceAsStream(name)) {
            if (input == null) {
                throw new IllegalArgumentException("missing fixture " + name);
            }
            return input.readAllBytes();
        }
    }
}
