package com.rsmaxwell.diaries.web.rendering;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;

public final class FragmentHtmlSanitizer {
    private static final Pattern NON_BREAKING_SPACE_ENTITY = Pattern.compile(
            "&(?:nbsp|#0*160|#x0*a0);",
            Pattern.CASE_INSENSITIVE);

    // Only recognized Quill classes (including safe combinations) survive sanitization.
    private static final String QUILL_CLASS_TOKEN = "ql-(?:list-bullet|align-(?:center|right|justify)"
            + "|indent-[1-5]|size-(?:small|large|huge)|font-(?:serif|monospace))";
    private static final Pattern SAFE_QUILL_CLASSES = Pattern.compile(
            QUILL_CLASS_TOKEN + "(?:\\s+" + QUILL_CLASS_TOKEN + ")*");

    // Match only sanitized output, never raw user HTML. All emitted class names
    // are selected from the fixed Quill palette and backed by our own stylesheet.
    private static final Pattern STYLED_OPEN_TAG = Pattern.compile(
            "<([a-z][a-z0-9]*)([^>]*\\sstyle=\"[^\"]*\"[^>]*)>");
    // Quill 2 represents bullet and numbered items inside <ol> with a data-list
    // attribute. Convert only its bullet marker to a fixed safe class before
    // OWASP sanitization strips the attribute. The sanitizer still validates
    // every resulting class token and strips arbitrary attributes.
    private static final Pattern LIST_ITEM_OPEN = Pattern.compile("<li\\b([^>]*)>", Pattern.CASE_INSENSITIVE);
    private static final Pattern BULLET_DATA_LIST = Pattern.compile(
            "\\sdata-list\\s*=\\s*([\"'])bullet\\1", Pattern.CASE_INSENSITIVE);
    private static final Pattern QUOTED_CLASS = Pattern.compile("\\bclass\\s*=\\s*([\"'])(.*?)\\1", Pattern.CASE_INSENSITIVE);
    private static final Pattern STYLE_ATTRIBUTE = Pattern.compile("\\sstyle=\"([^\"]*)\"");
    private static final Pattern CLASS_ATTRIBUTE = Pattern.compile("\\sclass=\"([^\"]*)\"");
    private static final Pattern HEX_COLOUR = Pattern.compile("#[0-9a-f]{6}");
    private static final Pattern RGB_COLOUR = Pattern.compile(
            "rgb\\(\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})\\s*\\)");
    private static final Set<String> QUILL_PALETTE = Set.of(
            "000000", "e60000", "ff9900", "ffff00", "008a00", "0066cc", "9933ff",
            "ffffff", "facccc", "ffebcc", "ffffcc", "cce8cc", "cce0f5", "ebd6ff",
            "bbbbbb", "f06666", "ffc266", "ffff66", "66b966", "66a3e0", "c285ff",
            "888888", "a10000", "b26b00", "b2b200", "006100", "0047b2", "6b24b2",
            "444444", "5c0000", "663d00", "666600", "003700", "002966", "3d1466");

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
                .allowAttributes("class").matching(SAFE_QUILL_CLASSES)
                .onElements("div", "span", "p", "h1", "h2", "h3", "li")
                // OWASP's CSS schema filters unsafe properties/values; never allow raw style attributes.
                .allowStyling()
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
        return normalizeNonBreakingSpaces(convertQuillColoursToClasses(policy.sanitize(preserveQuillBullets(normalized))));
    }

    private static String preserveQuillBullets(String html) {
        Matcher matcher = LIST_ITEM_OPEN.matcher(html);
        StringBuffer output = new StringBuffer();
        while (matcher.find()) {
            String attributes = matcher.group(1);
            Matcher bullet = BULLET_DATA_LIST.matcher(attributes);
            if (!bullet.find()) continue;
            attributes = bullet.replaceFirst("");
            Matcher classes = QUOTED_CLASS.matcher(attributes);
            if (classes.find()) {
                String combined = classes.group(2) + " ql-list-bullet";
                attributes = classes.replaceFirst(Matcher.quoteReplacement("class=\"" + combined + "\""));
            } else {
                attributes += " class=\"ql-list-bullet\"";
            }
            matcher.appendReplacement(output, Matcher.quoteReplacement("<li" + attributes + ">"));
        }
        matcher.appendTail(output);
        return output.toString();
    }

    private static String convertQuillColoursToClasses(String sanitizedHtml) {
        Matcher tagMatcher = STYLED_OPEN_TAG.matcher(sanitizedHtml);
        StringBuffer converted = new StringBuffer();
        while (tagMatcher.find()) {
            String attributes = tagMatcher.group(2);
            Matcher styleMatcher = STYLE_ATTRIBUTE.matcher(attributes);
            if (!styleMatcher.find()) {
                continue;
            }
            StringBuilder paletteClasses = new StringBuilder();
            for (String declaration : styleMatcher.group(1).split(";")) {
                int separator = declaration.indexOf(':');
                if (separator < 0) continue;
                String property = declaration.substring(0, separator).trim().toLowerCase(Locale.ROOT);
                if (!property.equals("color") && !property.equals("background-color")) continue;
                String colour = canonicalPaletteColour(declaration.substring(separator + 1).trim());
                if (colour != null) {
                    paletteClasses.append(' ').append(property.equals("color") ? "ql-color-" : "ql-background-")
                            .append(colour);
                }
            }
            String updated = styleMatcher.replaceFirst("");
            if (!paletteClasses.isEmpty()) {
                Matcher classMatcher = CLASS_ATTRIBUTE.matcher(updated);
                if (classMatcher.find()) {
                    updated = classMatcher.replaceFirst(Matcher.quoteReplacement(
                            " class=\"" + classMatcher.group(1) + paletteClasses + "\""));
                } else {
                    updated += " class=\"" + paletteClasses.substring(1) + "\"";
                }
            }
            tagMatcher.appendReplacement(converted, Matcher.quoteReplacement("<" + tagMatcher.group(1) + updated + ">"));
        }
        tagMatcher.appendTail(converted);
        return converted.toString();
    }

    private static String canonicalPaletteColour(String value) {
        String normalized = value.toLowerCase(Locale.ROOT);
        String hex;
        if (HEX_COLOUR.matcher(normalized).matches()) {
            hex = normalized.substring(1);
        } else {
            Matcher rgb = RGB_COLOUR.matcher(normalized);
            if (!rgb.matches()) return null;
            int r = Integer.parseInt(rgb.group(1));
            int g = Integer.parseInt(rgb.group(2));
            int b = Integer.parseInt(rgb.group(3));
            if (r > 255 || g > 255 || b > 255) return null;
            hex = String.format(Locale.ROOT, "%02x%02x%02x", r, g, b);
        }
        return QUILL_PALETTE.contains(hex) ? hex : null;
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
