package com.rsmaxwell.diaries.web.rendering;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import com.rsmaxwell.diaries.web.config.AppConfig.ContentConfig;
import com.rsmaxwell.diaries.web.model.DiaryItem;
import com.rsmaxwell.diaries.web.model.ImageItem;
import com.rsmaxwell.diaries.web.model.PageItem;

public final class ImageUrlBuilder {
    private final ContentConfig config;

    public ImageUrlBuilder(ContentConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("content configuration is required");
        }
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

    public String legacyFragmentImageBaseUrl(DiaryItem diary) {
        return appendPath(
                config.publicResponderBaseUrl(),
                encodeRoutePath(config.filesPath()),
                encodeSegment(diary.name()),
                "images");
    }

    /**
     * Builds the browser-visible URL for one catalogue Image relativePath.
     *
     * <p>The stored path is treated as literal path data, never as a URL. Its
     * canonical syntax is validated before each real '/' separator is split and
     * every segment is encoded exactly once. In particular, persisted percent
     * sequences are not decoded and are emitted with '%' encoded as "%25".</p>
     */
    public String catalogueImageUrl(String relativePath) {
        ImageItem.validateCanonicalRelativePath(relativePath);
        return appendPath(
                config.publicResponderBaseUrl(),
                encodeRoutePath(config.filesPath()),
                encodeRelativePath(relativePath));
    }

    private static String encodeRoutePath(String value) {
        return encodeRelativePath(value);
    }

    private static String encodeRelativePath(String value) {
        String[] segments = value.split("/", -1);
        StringBuilder encoded = new StringBuilder();
        for (String segment : segments) {
            if (!encoded.isEmpty()) {
                encoded.append('/');
            }
            encoded.append(encodeSegment(segment));
        }
        return encoded.toString();
    }

    private static String appendPath(String base, String... pathParts) {
        StringBuilder result = new StringBuilder(base);
        for (String part : pathParts) {
            if (part == null || part.isEmpty()) {
                continue;
            }
            if (result.isEmpty() || result.charAt(result.length() - 1) != '/') {
                result.append('/');
            }
            result.append(part);
        }
        return result.toString();
    }

    static String encodeSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20");
    }
}
