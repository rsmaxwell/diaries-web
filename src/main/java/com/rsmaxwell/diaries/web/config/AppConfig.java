package com.rsmaxwell.diaries.web.config;

import java.net.URI;
import java.time.ZoneId;
import java.util.Locale;

public record AppConfig(
        HttpConfig http,
        MqttConfig mqtt,
        ProjectionConfig projection,
        ContentConfig content,
        SiteConfig site) {

    public AppConfig {
        if (http == null || mqtt == null || projection == null || content == null || site == null) {
            throw new IllegalArgumentException("all configuration sections are required");
        }
    }

    public record HttpConfig(String host, int port, String basePath, String publicBaseUrl) {
        public HttpConfig {
            host = required(host, "http.host");
            if (port < 0 || port > 65535) {
                throw new IllegalArgumentException("http.port must be between 0 and 65535");
            }
            basePath = normalizeBasePath(basePath);
            publicBaseUrl = trimTrailingSlash(required(publicBaseUrl, "http.publicBaseUrl"));
            URI.create(publicBaseUrl);
        }
    }

    public record MqttConfig(
            String host,
            int port,
            String clientId,
            String topicPrefix,
            int keepAliveSeconds,
            int connectTimeoutSeconds,
            int reconnectDelaySeconds,
            boolean cleanStart) {
        public MqttConfig {
            host = required(host, "mqtt.host");
            if (port <= 0 || port > 65535) {
                throw new IllegalArgumentException("mqtt.port must be between 1 and 65535");
            }
            clientId = required(clientId, "mqtt.clientId");
            topicPrefix = required(topicPrefix, "mqtt.topicPrefix");
            if (topicPrefix.contains("/") || topicPrefix.contains("+") || topicPrefix.contains("#")) {
                throw new IllegalArgumentException("mqtt.topicPrefix must be one MQTT path segment");
            }
            positive(keepAliveSeconds, "mqtt.keepAliveSeconds");
            positive(connectTimeoutSeconds, "mqtt.connectTimeoutSeconds");
            positive(reconnectDelaySeconds, "mqtt.reconnectDelaySeconds");
        }

        public String serverUri() {
            return "tcp://" + host + ":" + port;
        }
    }

    public record ProjectionConfig(long initialReplayQuietPeriodMillis, int initialReplayTimeoutSeconds) {
        public ProjectionConfig {
            if (initialReplayQuietPeriodMillis <= 0) {
                throw new IllegalArgumentException("projection.initialReplayQuietPeriodMillis must be positive");
            }
            positive(initialReplayTimeoutSeconds, "projection.initialReplayTimeoutSeconds");
            if (initialReplayTimeoutSeconds * 1000L <= initialReplayQuietPeriodMillis) {
                throw new IllegalArgumentException("projection replay timeout must exceed quiet period");
            }
        }
    }

    public record ContentConfig(
            String responderBaseUrl,
            String publicResponderBaseUrl,
            String diariesPath) {
        public ContentConfig {
            responderBaseUrl = trimTrailingSlash(required(responderBaseUrl, "content.responderBaseUrl"));
            publicResponderBaseUrl = trimTrailingSlash(required(
                    publicResponderBaseUrl,
                    "content.publicResponderBaseUrl"));
            diariesPath = trimSlashes(required(diariesPath, "content.diariesPath"));
            URI.create(responderBaseUrl);
        }
    }

    public record SiteConfig(String title, String description, String defaultLocale, String zoneId) {
        public SiteConfig {
            title = required(title, "site.title");
            description = required(description, "site.description");
            defaultLocale = required(defaultLocale, "site.defaultLocale");
            zoneId = required(zoneId, "site.zoneId");
            Locale.forLanguageTag(defaultLocale);
            ZoneId.of(zoneId);
        }

        public Locale locale() {
            return Locale.forLanguageTag(defaultLocale);
        }

        public ZoneId zone() {
            return ZoneId.of(zoneId);
        }
    }

    public static String normalizeBasePath(String value) {
        if (value == null || value.isBlank() || "/".equals(value.trim())) {
            return "";
        }
        String normalized = value.trim();
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.contains("//") || normalized.contains("..")) {
            throw new IllegalArgumentException("http.basePath is invalid");
        }
        return normalized;
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }

    private static void positive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static String trimTrailingSlash(String value) {
        String result = value;
        while (result.length() > 1 && result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static String trimSlashes(String value) {
        String result = value;
        while (result.startsWith("/")) {
            result = result.substring(1);
        }
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        if (result.isBlank() || result.contains("..")) {
            throw new IllegalArgumentException("content.diariesPath is invalid");
        }
        return result;
    }
}
