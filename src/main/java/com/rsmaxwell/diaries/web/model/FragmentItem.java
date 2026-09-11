package com.rsmaxwell.diaries.web.model;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;

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
        try {
            LocalDate.of(year, month, day);
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException("invalid fragment date", exception);
        }
    }

    public LocalDate date() {
        return LocalDate.of(year, month, day);
    }

    /** Null is the documented rolling-migration compatibility state. */
    public FragmentType effectiveType() {
        return type == null ? FragmentType.MARQUEE : type;
    }
}
