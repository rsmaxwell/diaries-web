package com.rsmaxwell.diaries.web.mqtt;

import java.util.Arrays;
import java.util.List;

public final class TopicParser {
    private final String prefix;

    public TopicParser(String prefix) {
        if (prefix == null || prefix.isBlank() || prefix.contains("/") || prefix.contains("+") || prefix.contains("#")) {
            throw new IllegalArgumentException("topic prefix must be one MQTT path segment");
        }
        this.prefix = prefix;
    }

    public List<String> canonicalFilters() {
        return Arrays.stream(EntityType.values())
                .map(type -> prefix + "/" + type.topicSegment() + "/+")
                .toList();
    }

    public ParsedTopic parse(String topic) {
        String[] parts = topic == null ? new String[0] : topic.split("/", -1);
        if (parts.length != 3 || !prefix.equals(parts[0])) {
            throw new IllegalArgumentException("unexpected canonical topic: " + topic);
        }

        EntityType type = Arrays.stream(EntityType.values())
                .filter(value -> value.topicSegment().equals(parts[1]))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unexpected entity topic: " + topic));

        try {
            long id = Long.parseLong(parts[2]);
            if (id <= 0) {
                throw new IllegalArgumentException("topic id must be positive: " + topic);
            }
            return new ParsedTopic(type, id);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("topic id must be numeric: " + topic, exception);
        }
    }
}
