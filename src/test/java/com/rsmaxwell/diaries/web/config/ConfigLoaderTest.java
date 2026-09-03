package com.rsmaxwell.diaries.web.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ConfigLoaderTest {
    private final Path example = Path.of("config", "diaries-web.example.json");

    @Test
    void loadsStrictFileAndResolvesCredentialsOnlyFromEnvironment() throws Exception {
        LoadedConfiguration loaded = new ConfigLoader().load(
                example,
                Map.of(
                        ConfigLoader.MQTT_USERNAME, "diaries-web",
                        ConfigLoader.MQTT_PASSWORD, "secret-value"));

        assertThat(loaded.config().mqtt().topicPrefix()).isEqualTo("diaries");
        assertThat(loaded.credentials().username()).isEqualTo("diaries-web");
        assertThat(loaded.credentials().toString())
                .contains("********")
                .doesNotContain("secret-value");
    }

    @Test
    void rejectsMissingCredentialAndUnknownConfigurationFields() throws Exception {
        assertThatThrownBy(() -> new ConfigLoader().load(example, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(ConfigLoader.MQTT_USERNAME);

        Path invalid = java.nio.file.Files.createTempFile("diaries-web-invalid", ".json");
        try {
            java.nio.file.Files.writeString(invalid,
                    java.nio.file.Files.readString(example).replace(
                            "\"site\": {", "\"unknownSection\": {}, \"site\": {"));
            assertThatThrownBy(() -> new ConfigLoader().load(
                    invalid,
                    Map.of(
                            ConfigLoader.MQTT_USERNAME, "reader",
                            ConfigLoader.MQTT_PASSWORD, "secret")))
                    .isInstanceOf(com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException.class);
        } finally {
            java.nio.file.Files.deleteIfExists(invalid);
        }
    }
}
