package com.rsmaxwell.diaries.web.rendering;

import static org.assertj.core.api.Assertions.assertThat;

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
    void buildsSegmentEncodedResponderImageUrlUsingExistingConvention() {
        ImageUrlBuilder builder = new ImageUrlBuilder(new ContentConfig(
                "http://responder:8080", "https://content.example.test", "diaries"));
        DiaryItem diary = new DiaryItem(1, 0, "Family & Friends", BigDecimal.ONE);
        PageItem page = new PageItem(2, 0, 1, "page 001", BigDecimal.ONE, "jpg", 100, 200);

        assertThat(builder.pageImageUrl(diary, page))
                .isEqualTo("https://content.example.test/diaries/Family%20%26%20Friends/page%20001.jpg");
    }
}
