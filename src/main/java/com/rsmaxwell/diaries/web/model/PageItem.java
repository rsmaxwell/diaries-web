package com.rsmaxwell.diaries.web.model;

import java.math.BigDecimal;

public record PageItem(
        long id,
        long version,
        long diaryId,
        String name,
        BigDecimal sequence,
        String extension,
        int width,
        int height) {

    public PageItem {
        Validation.positiveId(id, "page id");
        Validation.nonNegative(version, "page version");
        Validation.positiveId(diaryId, "page diaryId");
        name = Validation.notBlank(name, "page name");
        sequence = Validation.notNull(sequence, "page sequence");
        extension = Validation.notBlank(extension, "page extension");
        if (!extension.matches("\\.?[A-Za-z0-9]+")) {
            throw new IllegalArgumentException(
                    "page extension must contain an optional leading dot followed by letters and numbers");
        }
        if (extension.startsWith(".")) {
            extension = extension.substring(1);
        }
        Validation.positive(width, "page width");
        Validation.positive(height, "page height");
    }
}
