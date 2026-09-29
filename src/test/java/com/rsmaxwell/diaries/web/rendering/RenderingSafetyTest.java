package com.rsmaxwell.diaries.web.rendering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.rsmaxwell.diaries.web.config.AppConfig.ContentConfig;
import com.rsmaxwell.diaries.web.model.DiaryItem;
import com.rsmaxwell.diaries.web.model.PageItem;

class RenderingSafetyTest {
    @Test
    void keepsNormalQuillMarkupAndRemovesExecutableContent() {
        String result = new FragmentHtmlSanitizer().sanitize(
                "<p class=\"ql-align-center\"><strong>Safe</strong>"
                        + "<script>alert(1)</script><a href=\"javascript:alert(2)\">bad</a></p>");

        assertThat(result).contains("ql-align-center", "<strong>Safe</strong>");
        assertThat(result).doesNotContain("script", "javascript", "alert");
    }

    @Test
    void convertsNonBreakingSpacesToNormalWordBreakOpportunities() {
        String result = new FragmentHtmlSanitizer().sanitize(
                "<p>A&nbsp;fine&#160;morning&#xA0;rather\u00a0frosty</p>");

        assertThat(result).contains("A fine morning rather frosty");
        assertThat(result).doesNotContain("&nbsp;", "&#160;", "&#xa0;", "\u00a0");
    }

    @Test
    void resolvesOnlySafeLegacyImagePaths() {
        String result = new FragmentHtmlSanitizer().sanitize(
                "<a href=\"images/map large.png\"><img src=\"images/map large.png\"></a>"
                        + "<img src=\"images/maps/nested.png\"><img src=\"https://example.test/map.png\">",
                "http://localhost:8081/files/diary%20one/images");

        assertThat(result)
                .contains("href=\"http://localhost:8081/files/diary%20one/images/map%20large.png\"")
                .contains("src=\"http://localhost:8081/files/diary%20one/images/map%20large.png\"")
                .contains("src=\"https://example.test/map.png\"")
                .doesNotContain("images/maps/nested.png");
    }

    @Test
    void preservesPageAndDefaultLegacyUrlConventions() {
        ImageUrlBuilder builder = new ImageUrlBuilder(new ContentConfig(
                "http://responder:8080", "https://content.example.test", "diaries", "files"));
        DiaryItem diary = new DiaryItem(1, 0, "Family & Friends", BigDecimal.ONE);
        PageItem page = new PageItem(2, 0, 1, "page 001", BigDecimal.ONE, "jpg", 100, 200);

        assertThat(builder.pageImageUrl(diary, page))
                .isEqualTo("https://content.example.test/diaries/Family%20%26%20Friends/page%20001.jpg");
        assertThat(builder.legacyFragmentImageBaseUrl(diary))
                .isEqualTo("https://content.example.test/files/Family%20%26%20Friends/images");
    }

    @Test
    void buildsCatalogueUrlFromPublicBaseConfiguredFilesRouteAndRelativePath() {
        ImageUrlBuilder builder = new ImageUrlBuilder(new ContentConfig(
                "http://diaries-responder:8081",
                "/diaries-responder",
                "diaries",
                "/archive files/catalogue/"));

        assertThat(builder.catalogueImageUrl(
                "diary-1830/images/Screenshot 2026-09-25 121352.png"))
                .isEqualTo("/diaries-responder/archive%20files/catalogue/diary-1830/images/"
                        + "Screenshot%202026-09-25%20121352.png");
    }

    @Test
    void catalogueUrlEncodesEveryLiteralSegmentExactlyOnce() {
        ImageUrlBuilder builder = new ImageUrlBuilder(new ContentConfig(
                "http://responder:8080", "https://content.example.test/base", "diaries", "files"));

        assertThat(builder.catalogueImageUrl("diary-1830/images/A+B 100% #?\" café.png"))
                .isEqualTo("https://content.example.test/base/files/diary-1830/images/"
                        + "A%2BB%20100%25%20%23%3F%22%20caf%C3%A9.png");
        assertThat(builder.catalogueImageUrl("diary-1830/images/%2e%2e%2fsecret.png"))
                .isEqualTo("https://content.example.test/base/files/diary-1830/images/"
                        + "%252e%252e%252fsecret.png");
    }

    @Test
    void catalogueUrlRejectsAbsoluteAmbiguousAndTraversalPathsBeforeEncoding() {
        ImageUrlBuilder builder = new ImageUrlBuilder(new ContentConfig(
                "http://responder:8080", "https://content.example.test", "diaries", "files"));

        for (String path : new String[] {
                "/absolute/image.jpg",
                "C:/images/image.jpg",
                "\\\\server\\share\\image.jpg",
                "diary\\images\\image.jpg",
                "https://evil.example.test/image.jpg",
                "diary/../image.jpg",
                "diary/./image.jpg",
                "diary//image.jpg",
                "diary/images/",
                "diary/\u0001image.jpg"
        }) {
            assertThatThrownBy(() -> builder.catalogueImageUrl(path))
                    .as("relativePath %s", path)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void customFilesRouteAlsoAppliesToLegacyBaseWithoutChangingItsShape() {
        ImageUrlBuilder builder = new ImageUrlBuilder(new ContentConfig(
                "http://responder:8080", "/proxy/responder", "diaries", "content/files"));
        DiaryItem diary = new DiaryItem(1, 0, "Family & Friends", BigDecimal.ONE);

        assertThat(builder.legacyFragmentImageBaseUrl(diary))
                .isEqualTo("/proxy/responder/content/files/Family%20%26%20Friends/images");
    }
}
