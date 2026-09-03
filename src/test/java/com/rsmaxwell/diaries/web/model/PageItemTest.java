package com.rsmaxwell.diaries.web.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PageItemTest {
    @ParameterizedTest
    @CsvSource({
            "jpg, jpg",
            ".jpg, jpg",
            "JPEG, JPEG",
            ".j2k, j2k"
    })
    void acceptsSafeExtensionsAndNormalizesTheLeadingDot(String supplied, String expected) {
        PageItem page = pageWithExtension(supplied);

        assertThat(page.extension()).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            ".",
            "..jpg",
            "jpg.png",
            "../jpg",
            "jpg/png",
            "jpg\\png",
            " jpg",
            "jpg "
    })
    void rejectsUnsafeOrMalformedExtensions(String extension) {
        assertThatThrownBy(() -> pageWithExtension(extension))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("page extension");
    }

    private static PageItem pageWithExtension(String extension) {
        return new PageItem(
                22,
                3,
                11,
                "page 001",
                new BigDecimal("2.0000"),
                extension,
                1200,
                800);
    }
}
