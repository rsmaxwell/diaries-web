package com.rsmaxwell.diaries.web.model;

import java.text.Normalizer;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Immutable metadata for one reusable catalogue Image.
 *
 * <p>The retained contract intentionally contains metadata only. Browser URLs and
 * file bytes are derived/loaded elsewhere.</p>
 */
public record ImageItem(
        long id,
        long version,
        String relativePath,
        String mimeType,
        String originalFilename,
        int width,
        int height,
        String checksum,
        String caption,
        String altText) {

    private static final Set<String> SUPPORTED_MIME_TYPES =
            Set.of("image/jpeg", "image/png", "image/gif", "image/webp");

    public ImageItem {
        Validation.positiveId(id, "image id");
        Validation.nonNegative(version, "image version");
        validateCanonicalRelativePath(relativePath);
        if (mimeType == null || !SUPPORTED_MIME_TYPES.contains(mimeType)) {
            throw new IllegalArgumentException("image MIME type is unsupported");
        }
        Validation.positive(width, "image width");
        Validation.positive(height, "image height");
        if (checksum == null || !checksum.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "image checksum must contain 64 lowercase hexadecimal characters");
        }
        if (originalFilename == null || originalFilename.isBlank()
                || originalFilename.indexOf('/') >= 0
                || originalFilename.indexOf('\\') >= 0
                || originalFilename.indexOf(':') >= 0
                || originalFilename.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    "image originalFilename must be a filename without a directory");
        }
        if (caption == null || altText == null) {
            throw new IllegalArgumentException("image caption and altText must not be null");
        }
    }

    /**
     * Jackson creator that preserves the 0026 reader contract distinction between
     * an absent optional text property (default to empty) and explicit JSON null
     * (invalid metadata). Unknown additive properties are intentionally ignored.
     */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static ImageItem fromJson(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("image payload must be a JSON object");
        }
        return new ImageItem(
                requiredLong(node, "id"),
                requiredLong(node, "version"),
                requiredText(node, "relativePath"),
                requiredText(node, "mimeType"),
                requiredText(node, "originalFilename"),
                requiredInt(node, "width"),
                requiredInt(node, "height"),
                requiredText(node, "checksum"),
                optionalTextDefaultEmpty(node, "caption"),
                optionalTextDefaultEmpty(node, "altText"));
    }

    /** Mirrors responder Image.validateCanonicalRelativePath exactly. */
    public static void validateCanonicalRelativePath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()
                || relativePath.indexOf('\\') >= 0
                || relativePath.indexOf(':') >= 0
                || relativePath.codePoints().anyMatch(Character::isISOControl)
                || !Normalizer.isNormalized(relativePath, Normalizer.Form.NFC)) {
            throw new IllegalArgumentException("image relativePath must be a canonical NFC relative path");
        }
        for (String segment : relativePath.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("image relativePath contains an invalid path segment");
            }
        }
    }

    private static long requiredLong(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IllegalArgumentException("image " + name + " must be an integer");
        }
        return value.longValue();
    }

    private static int requiredInt(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException("image " + name + " must be an integer");
        }
        return value.intValue();
    }

    private static String requiredText(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException("image " + name + " must be text");
        }
        return value.textValue();
    }

    private static String optionalTextDefaultEmpty(JsonNode node, String name) {
        if (!node.has(name)) {
            return "";
        }
        JsonNode value = node.get(name);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException("image " + name + " must be text when present");
        }
        return value.textValue();
    }
}
