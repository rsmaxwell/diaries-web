package com.rsmaxwell.diaries.web.rendering;

import java.net.URI;
import java.net.URISyntaxException;

/** Validate untrusted retained destinations independently of the writer. */
public final class ImageLinkUrlPolicy {
    private ImageLinkUrlPolicy() { }

    public static String safeUrl(String raw) {
        if (raw == null || raw.isEmpty() || raw.codePointCount(0, raw.length()) > 4096)
            return null;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c <= ' ' || c == 0x7f) return null;
        }
        try {
            URI uri = new URI(raw);
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    || uri.isOpaque() || uri.getRawAuthority() == null || uri.getHost() == null
                    || uri.getHost().isEmpty() || uri.getRawUserInfo() != null
                    || uri.getPort() > 65535) return null;
            return raw; // Do not normalise encoded query or fragment.
        } catch (URISyntaxException invalid) {
            return null;
        }
    }
}
