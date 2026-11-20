package com.rsmaxwell.diaries.web.rendering;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ImageLinkUrlPolicyTest {
    @Test void preservesWholeSafeUrl() {
        String url = "http://example.test/a?one=1&two=2#part";
        assertThat(ImageLinkUrlPolicy.safeUrl(url)).isEqualTo(url);
        assertThat(ImageLinkUrlPolicy.safeUrl("HTTPS://example.test/%C3%A9?q=a%26b#x")).isEqualTo(
                "HTTPS://example.test/%C3%A9?q=a%26b#x");
    }

    @Test void unsafeOrBrokenDestinationsRemainPlainText() {
        for (String url : new String[] {"javascript:alert(1)", "//example.test", "ftp://example.test/x",
                "https://alice:secret@example.test", "https://example.test/space here",
                "https://example.test/%", "https://example.test/\u0001"}) {
            assertThat(ImageLinkUrlPolicy.safeUrl(url)).as(url).isNull();
        }
        assertThat(ImageLinkUrlPolicy.safeUrl(null)).isNull();
    }
}
