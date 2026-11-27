package com.rsmaxwell.diaries.web.rendering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

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
    void preservesQuillSafeColoursAndFontsButRejectsExecutableStyles() {
        String result = new FragmentHtmlSanitizer().sanitize(
                "<p><span class=\"ql-font-monospace ql-size-large\" "
                        + "style=\"color: rgb(230, 0, 0); background-color: #ffff00; "
                        + "background-image: url(javascript:alert(1))\">Colours</span></p>");
        assertThat(result).contains("ql-font-monospace", "ql-size-large")
                .contains("ql-color-e60000", "ql-background-ffff00")
                .doesNotContain("style=");
        assertThat(result).doesNotContain("javascript", "background-image", "alert(1)");
    }

    @Test
    void convertsSavedAnnualBackgroundIntoCspCompatibleClass() {
        String result = new FragmentHtmlSanitizer().sanitize(
                "<p><span style=\"background-color:rgb(230, 0, 0)\">xxxx</span></p>");
        assertThat(result).contains("class=\"ql-background-e60000\"", "xxxx")
                .doesNotContain("style=");
    }

    @Test
    void rejectsNonPaletteColoursAndKeepsCspSafeOutput() {
        String result = new FragmentHtmlSanitizer().sanitize(
                "<p><span style=\"color:#123456;background-color:rgb(1,2,3)\">text</span></p>");
        assertThat(result).doesNotContain("style=", "ql-color-123456", "ql-background-010203");
    }

    @Test
    void includesDefaultPaletteRulesInReaderStylesheet() throws Exception {
        String css = resourceText("/static/css/diaries.css");
        assertThat(css).contains(".ql-background-e60000 { background-color: #e60000; }")
                .contains(".ql-color-0066cc { color: #0066cc; }");
    }

    @Test
    void preservesAllSharedToolbarFormatsThroughReaderSanitization() {
        String html = "<h2 class=\"ql-align-center\">Heading</h2>"
                + "<p><strong>bold</strong><em>italic</em><u>underline</u>"
                + "<span class=\"ql-font-serif ql-size-large\">Serif large</span>"
                + "<span class=\"ql-font-monospace ql-size-small\">Mono small</span>"
                + "<span class=\"ql-size-huge\">Huge</span>"
                + "<span style=\"color:rgb(0,102,204);background-color:rgb(230,0,0)\">Combined</span>"
                + "<a href=\"https://example.org/reference\">Link</a></p>"
                + "<ol><li data-list=\"bullet\">Bullet</li><li data-list=\"ordered\">Numbered</li></ol>"
                + "<p class=\"ql-indent-2\">Indented</p>"
                + "<p>Unformatted after clear</p>";
        String result = new FragmentHtmlSanitizer().sanitize(html);
        assertThat(result).contains("<h2 class=\"ql-align-center\">Heading</h2>",
                "<strong>bold</strong>", "<em>italic</em>", "<u>underline</u>",
                "ql-font-serif", "ql-size-large", "ql-font-monospace", "ql-size-small", "ql-size-huge",
                "ql-color-0066cc", "ql-background-e60000", "https://example.org/reference",
                "ql-list-bullet", "Bullet", "Numbered", "ql-indent-2", "Unformatted after clear")
                .doesNotContain("style=", "data-list=");
    }

    @Test
    void preservesBulletListSemanticsWithoutTrustingArbitraryAttributes() {
        String html = "<ol><li class=\"ql-indent-1\" data-list=\"bullet\" onclick=\"alert(1)\">A</li>"
                + "<li data-list=\"ordered\">B</li><li data-list=\"evil\">C</li></ol>";
        String result = new FragmentHtmlSanitizer().sanitize(html);
        assertThat(result).contains("ql-list-bullet", "ql-indent-1", "<ol>", "A", "B", "C")
                .doesNotContain("onclick", "alert", "data-list", "ql-list-evil");
    }

    @Test
    void sharedToolbarFormatsHaveCspCompatibleReaderCss() throws Exception {
        String css = resourceText("/static/css/diaries.css");
        assertThat(css).contains(".ql-align-center", ".ql-align-right", ".ql-align-justify",
                ".ql-font-serif", ".ql-font-monospace", ".ql-size-small", ".ql-size-large",
                ".ql-size-huge", ".ql-list-bullet", ".ql-indent-1", ".ql-indent-5",
                ".ql-color-0066cc", ".ql-background-e60000");
    }

    @Test
    void rejectsUnsafeLinksElementsAndStylesInCombinedRichText() {
        String html = "<p><a href=\"javascript:alert(1)\">Unsafe</a>"
                + "<span style=\"background-image:url(javascript:alert(2));color:#123456\">Other</span>"
                + "<img src=\"javascript:alert(3)\"><script>alert(4)</script></p>";
        String result = new FragmentHtmlSanitizer().sanitize(html);
        assertThat(result).doesNotContain("javascript", "onclick", "style=", "ql-color-123456", "<script", "alert(");
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
    void browserVisibleCatalogueUrlsUseOnlyThePublicBaseAndEncodeLiteralRouteData() {
        ImageUrlBuilder builder = new ImageUrlBuilder(new ContentConfig(
                "http://private-responder.internal:8081/internal",
                "/public-responder",
                "diaries",
                "archive/été 100%/catalogue"));

        String url = builder.catalogueImageUrl("diary-1830/images/café 50% +#?.png");

        assertThat(url)
                .isEqualTo("/public-responder/archive/%C3%A9t%C3%A9%20100%25/catalogue/diary-1830/images/"
                        + "caf%C3%A9%2050%25%20%2B%23%3F.png")
                .doesNotContain("private-responder.internal", "http://private-responder.internal:8081");
        assertThatThrownBy(() -> builder.catalogueImageUrl("https://evil.example.test/image.png"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void templatesReserveRawRenderingForSanitizedFragmentHtmlOnly() throws Exception {
        String month = resourceText("/templates/month-reader.peb");
        String source = resourceText("/templates/source-page.peb");

        assertThat(occurrences(month, "fragment.html | raw")).isEqualTo(3);
        assertThat(occurrences(month, "| raw")).isEqualTo(3);
        assertThat(occurrences(source, "| raw")).isEqualTo(1);
        assertThat(month).contains("fragment.html | raw")
                .doesNotContain("mediaAltText | raw", "mediaCaption | raw", "mediaUrl | raw");
        assertThat(source).contains("fragment.html | raw")
                .doesNotContain("mediaAltText | raw", "mediaCaption | raw", "mediaUrl | raw");
    }

    @Test
    void customFilesRouteAlsoAppliesToLegacyBaseWithoutChangingItsShape() {
        ImageUrlBuilder builder = new ImageUrlBuilder(new ContentConfig(
                "http://responder:8080", "/proxy/responder", "diaries", "content/files"));
        DiaryItem diary = new DiaryItem(1, 0, "Family & Friends", BigDecimal.ONE);

        assertThat(builder.legacyFragmentImageBaseUrl(diary))
                .isEqualTo("/proxy/responder/content/files/Family%20%26%20Friends/images");
    }

    private static String resourceText(String name) throws IOException {
        try (var input = RenderingSafetyTest.class.getResourceAsStream(name)) {
            if (input == null) {
                throw new IOException("missing test resource " + name);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }
}
