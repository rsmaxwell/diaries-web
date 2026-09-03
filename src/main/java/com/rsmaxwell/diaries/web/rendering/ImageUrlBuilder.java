package com.rsmaxwell.diaries.web.rendering;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import com.rsmaxwell.diaries.web.config.AppConfig.ContentConfig;
import com.rsmaxwell.diaries.web.model.DiaryItem;
import com.rsmaxwell.diaries.web.model.PageItem;

public final class ImageUrlBuilder {
    private final ContentConfig config;

    public ImageUrlBuilder(ContentConfig config) {
        this.config = config;
    }

    public String pageImageUrl(DiaryItem diary, PageItem page) {
        String extension = page.extension().startsWith(".")
                ? page.extension()
                : "." + page.extension();
        return config.publicResponderBaseUrl()
                + "/" + encodeSegment(config.diariesPath())
                + "/" + encodeSegment(diary.name())
                + "/" + encodeSegment(page.name())
                + extension;
    }

    static String encodeSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }
}
