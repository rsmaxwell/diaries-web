package com.rsmaxwell.diaries.web.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ImageItemTest {
    private static final String CHECKSUM =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void acceptsResponderCanonicalMetadataWithoutAnyDeploymentUrl() {
        ImageItem image = new ImageItem(
                60, 3, "1829/06/img2893-detail.jpg", "image/jpeg",
                "img2893-detail.jpg", 1600, 900, CHECKSUM, "caption", "");

        assertThat(image.id()).isEqualTo(60);
        assertThat(image.relativePath()).isEqualTo("1829/06/img2893-detail.jpg");
        assertThat(image.altText()).isEmpty();
    }

    @Test
    void constructorEnforcesCanonicalMetadataBoundary() {
        assertThatThrownBy(() -> new ImageItem(
                60, 3, "1829/../img.jpg", "image/jpeg",
                "img.jpg", 1600, 900, CHECKSUM, "", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("path segment");

        assertThatThrownBy(() -> new ImageItem(
                60, 3, "1829/img.jpg", "image/svg+xml",
                "img.jpg", 1600, 900, CHECKSUM, "", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MIME");

        assertThatThrownBy(() -> new ImageItem(
                60, 3, "1829/img.jpg", "image/jpeg",
                "img.jpg", 0, 900, CHECKSUM, "", ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("width");
    }

    @Test
    void explicitNullCaptionOrAltTextIsInvalidAtTheModelBoundary() {
        assertThatThrownBy(() -> new ImageItem(
                60, 3, "1829/img.jpg", "image/jpeg",
                "img.jpg", 1600, 900, CHECKSUM, null, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("caption");

        assertThatThrownBy(() -> new ImageItem(
                60, 3, "1829/img.jpg", "image/jpeg",
                "img.jpg", 1600, 900, CHECKSUM, "", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("altText");
    }
}
