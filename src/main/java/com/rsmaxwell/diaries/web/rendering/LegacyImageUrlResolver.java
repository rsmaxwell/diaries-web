package com.rsmaxwell.diaries.web.rendering;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

final class LegacyImageUrlResolver {
    private static final String PREFIX = "images/";
    private static final Set<String> IMAGE_EXTENSIONS = Set.of("gif", "jpg", "jpeg", "png", "webp");

    private LegacyImageUrlResolver() {
    }

    /**
     * Rewrites only the importer-era `images/<filename>` shape. A null result
     * tells the HTML sanitizer to remove an unsafe legacy-looking attribute.
     */
    static String resolve(String value, String legacyImageBaseUrl) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();
        if (!trimmed.toLowerCase(Locale.ROOT).startsWith(PREFIX)) {
            return value;
        }

        String encodedFilename = trimmed.substring(PREFIX.length());
        if (encodedFilename.isBlank() || containsPathSyntax(encodedFilename)) {
            return null;
        }

        final String filename;
        try {
            filename = URLDecoder.decode(encodedFilename, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            return null;
        }

        if (filename.isBlank()
                || ".".equals(filename)
                || "..".equals(filename)
                || containsPathSyntax(filename)
                || filename.chars().anyMatch(character -> character < 0x20 || character == 0x7f)
                || !hasApprovedImageExtension(filename)) {
            return null;
        }

        String base = legacyImageBaseUrl == null ? "" : legacyImageBaseUrl.replaceAll("/+$", "");
        return base.isBlank() ? null : base + "/" + ImageUrlBuilder.encodeSegment(filename);
    }

    private static boolean containsPathSyntax(String value) {
        return value.indexOf('/') >= 0
                || value.indexOf('\\') >= 0
                || value.indexOf('?') >= 0
                || value.indexOf('#') >= 0;
    }

    private static boolean hasApprovedImageExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot >= 0
                && dot < filename.length() - 1
                && IMAGE_EXTENSIONS.contains(filename.substring(dot + 1).toLowerCase(Locale.ROOT));
    }
}
