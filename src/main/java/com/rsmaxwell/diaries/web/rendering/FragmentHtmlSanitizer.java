package com.rsmaxwell.diaries.web.rendering;

import java.util.regex.Pattern;

import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;

public final class FragmentHtmlSanitizer {
    private static final Pattern NON_BREAKING_SPACE_ENTITY = Pattern.compile(
            "&(?:nbsp|#0*160|#x0*a0);",
            Pattern.CASE_INSENSITIVE);

    private final PolicyFactory policy;

    public FragmentHtmlSanitizer() {
        this.policy = createPolicy(null);
    }

    private static PolicyFactory createPolicy(String legacyImageBaseUrl) {
        return new HtmlPolicyBuilder()
                .allowElements(
                        "p", "div", "span", "br", "strong", "b", "em", "i", "u", "s", "strike",
                        "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "li",
                        "sub", "sup", "blockquote", "pre", "code", "a", "img", "figure", "figcaption")
                .allowAttributes("class").matching(true, "ql-align-center", "ql-align-right", "ql-align-justify",
                        "ql-indent-1", "ql-indent-2", "ql-indent-3", "ql-indent-4", "ql-indent-5",
                        "ql-size-small", "ql-size-large", "ql-size-huge")
                .onElements("div", "span", "p", "h1", "h2", "h3", "li")
                .allowAttributes("title").onElements("a")
                .allowAttributes("href")
                    .matching((elementName, attributeName, value) ->
                            rewriteLegacyImageAttribute(value, legacyImageBaseUrl))
                    .onElements("a")
                .allowAttributes("alt", "title", "width", "height").onElements("img")
                .allowAttributes("src")
                    .matching((elementName, attributeName, value) ->
                            rewriteLegacyImageAttribute(value, legacyImageBaseUrl))
                    .onElements("img")
                .allowStandardUrlProtocols()
                .requireRelNofollowOnLinks()
                .toFactory();
    }

    public String sanitize(String html) {
        return sanitizeWithPolicy(html, policy);
    }

    public String sanitize(String html, String legacyImageBaseUrl) {
        return sanitizeWithPolicy(html, createPolicy(legacyImageBaseUrl));
    }

    private static String sanitizeWithPolicy(String html, PolicyFactory policy) {
        String normalized = normalizeNonBreakingSpaces(html == null ? "" : html);
        return normalizeNonBreakingSpaces(policy.sanitize(normalized));
    }

    private static String rewriteLegacyImageAttribute(String value, String legacyImageBaseUrl) {
        if (legacyImageBaseUrl == null || legacyImageBaseUrl.isBlank()) {
            return value;
        }
        return LegacyImageUrlResolver.resolve(value, legacyImageBaseUrl);
    }

    private static String normalizeNonBreakingSpaces(String html) {
        return NON_BREAKING_SPACE_ENTITY.matcher(html.replace('\u00a0', ' ')).replaceAll(" ");
    }
}
