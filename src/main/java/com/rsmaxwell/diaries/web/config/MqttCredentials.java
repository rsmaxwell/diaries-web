package com.rsmaxwell.diaries.web.config;

public record MqttCredentials(String username, String password) {
    public MqttCredentials {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("DIARIES_WEB_MQTT_USERNAME is required");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("DIARIES_WEB_MQTT_PASSWORD is required");
        }
    }

    @Override
    public String toString() {
        return "MqttCredentials[username=" + username + ", password=********]";
    }
}
