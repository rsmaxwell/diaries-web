package com.rsmaxwell.diaries.web.config;

public record LoadedConfiguration(AppConfig config, MqttCredentials credentials) {
}
