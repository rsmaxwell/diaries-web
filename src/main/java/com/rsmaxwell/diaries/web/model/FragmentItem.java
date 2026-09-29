package com.rsmaxwell.diaries.web.model;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

public record FragmentItem(
        long id,
        long version,
        int year,
        int month,
        int day,
        BigDecimal sequence,
        String text,
        Long pageId,
        FragmentType type,
        @JsonIgnore String rawType,
        Long imageId,
        Long marqueeId) {

    public FragmentItem {
        Validation.positiveId(id, "fragment id");
        Validation.nonNegative(version, "fragment version");
        sequence = Validation.notNull(sequence, "fragment sequence");
        text = Validation.notNull(text, "fragment text");
        if (pageId != null) {
            Validation.positiveId(pageId, "fragment pageId");
        }
        if (imageId != null) {
            Validation.positiveId(imageId, "fragment imageId");
        }
        if (marqueeId != null) {
            Validation.positiveId(marqueeId, "fragment marqueeId");
        }
        if (type == FragmentType.UNKNOWN) {
            if (rawType == null) {
                throw new IllegalArgumentException("unknown fragment type requires its raw value");
            }
        } else if (rawType != null) {
            throw new IllegalArgumentException("raw fragment type is only valid for UNKNOWN");
        }
        try {
            LocalDate.of(year, month, day);
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException("invalid fragment date", exception);
        }
    }

    /** Source-compatible constructor for known/legacy fragment types. */
    public FragmentItem(
            long id,
            long version,
            int year,
            int month,
            int day,
            BigDecimal sequence,
            String text,
            Long pageId,
            FragmentType type,
            Long imageId,
            Long marqueeId) {
        this(id, version, year, month, day, sequence, text, pageId, type, null, imageId, marqueeId);
    }

    /**
     * Retained-payload creator preserving unknown future string types rather than
     * coercing them to the legacy null/MARQUEE compatibility state.
     */
    @JsonCreator(mode = JsonCreator.Mode.PROPERTIES)
    public static FragmentItem fromJson(
            @JsonProperty("id") long id,
            @JsonProperty("version") long version,
            @JsonProperty("year") int year,
            @JsonProperty("month") int month,
            @JsonProperty("day") int day,
            @JsonProperty("sequence") BigDecimal sequence,
            @JsonProperty("text") String text,
            @JsonProperty("pageId") Long pageId,
            @JsonProperty("type") JsonNode typeNode,
            @JsonProperty("imageId") Long imageId,
            @JsonProperty("marqueeId") Long marqueeId) {
        ParsedFragmentType parsed = parseType(typeNode);
        return new FragmentItem(
                id, version, year, month, day, sequence, text, pageId,
                parsed.type(), parsed.rawType(), imageId, marqueeId);
    }

    private static ParsedFragmentType parseType(JsonNode typeNode) {
        if (typeNode == null || typeNode.isNull()) {
            return new ParsedFragmentType(null, null);
        }
        if (!typeNode.isTextual()) {
            throw new IllegalArgumentException("fragment type must be a string or null");
        }
        String raw = typeNode.textValue();
        return switch (raw) {
            case "MARQUEE" -> new ParsedFragmentType(FragmentType.MARQUEE, null);
            case "IMAGE" -> new ParsedFragmentType(FragmentType.IMAGE, null);
            default -> new ParsedFragmentType(FragmentType.UNKNOWN, raw);
        };
    }

    public LocalDate date() {
        return LocalDate.of(year, month, day);
    }

    /** Null is the documented rolling-migration compatibility state. */
    public FragmentType effectiveType() {
        return type == null ? FragmentType.MARQUEE : type;
    }

    private record ParsedFragmentType(FragmentType type, String rawType) {
    }
}
