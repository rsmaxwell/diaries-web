package com.rsmaxwell.diaries.web.model;

import java.math.BigDecimal;

public record DiaryItem(long id, long version, String name, BigDecimal sequence) {
    public DiaryItem {
        Validation.positiveId(id, "diary id");
        Validation.nonNegative(version, "diary version");
        name = Validation.notBlank(name, "diary name");
        sequence = Validation.notNull(sequence, "diary sequence");
    }
}
