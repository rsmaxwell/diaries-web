package com.rsmaxwell.diaries.web.mqtt;

import java.io.IOException;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsmaxwell.diaries.web.model.DiaryItem;
import com.rsmaxwell.diaries.web.model.FragmentItem;
import com.rsmaxwell.diaries.web.model.MarqueeItem;
import com.rsmaxwell.diaries.web.model.PageItem;
import com.rsmaxwell.diaries.web.projection.ProjectionEvent;

public final class RetainedMessageDecoder {
    private final TopicParser topicParser;
    private final ObjectMapper objectMapper;

    public RetainedMessageDecoder(TopicParser topicParser) {
        this(topicParser, new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false));
    }

    public RetainedMessageDecoder(TopicParser topicParser, ObjectMapper objectMapper) {
        this.topicParser = topicParser;
        this.objectMapper = objectMapper;
    }

    public ProjectionEvent decode(String topic, byte[] payload) throws IOException {
        ParsedTopic parsed = topicParser.parse(topic);
        if (payload == null || payload.length == 0) {
            return new ProjectionEvent.Tombstone(parsed.type(), parsed.id());
        }

        ProjectionEvent event = switch (parsed.type()) {
            case DIARY -> new ProjectionEvent.UpsertDiary(objectMapper.readValue(payload, DiaryItem.class));
            case PAGE -> new ProjectionEvent.UpsertPage(objectMapper.readValue(payload, PageItem.class));
            case FRAGMENT -> new ProjectionEvent.UpsertFragment(objectMapper.readValue(payload, FragmentItem.class));
            case MARQUEE -> new ProjectionEvent.UpsertMarquee(objectMapper.readValue(payload, MarqueeItem.class));
        };

        long payloadId = switch (event) {
            case ProjectionEvent.UpsertDiary value -> value.value().id();
            case ProjectionEvent.UpsertPage value -> value.value().id();
            case ProjectionEvent.UpsertFragment value -> value.value().id();
            case ProjectionEvent.UpsertMarquee value -> value.value().id();
            case ProjectionEvent.Tombstone value -> value.id();
        };
        if (payloadId != parsed.id()) {
            throw new IllegalArgumentException("topic/payload id mismatch for " + topic);
        }
        return event;
    }
}
