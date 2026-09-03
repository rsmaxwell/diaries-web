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
        Long marqueeId) {

    public FragmentItem {
        Validation.positiveId(id, "fragment id");
        Validation.nonNegative(version, "fragment version");
        sequence = Validation.notNull(sequence, "fragment sequence");
        text = Validation.notNull(text, "fragment text");
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
}
