package com.rsmaxwell.diaries.web.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ConfigLoaderTest {
    private final Path example = Path.of("config", "diaries-web.example.json");
    private final Map<String, String> credentials = Map.of(
            ConfigLoader.MQTT_USERNAME, "diaries-web",
            ConfigLoader.MQTT_PASSWORD, "secret-value");

    @Test
    void loadsStrictFileAndResolvesCredentialsOnlyFromEnvironment() throws Exception {
        LoadedConfiguration loaded = new ConfigLoader().load(example, credentials);

        assertThat(loaded.config().mqtt().topicPrefix()).isEqualTo("diaries");
        assertThat(loaded.config().content().publicResponderBaseUrl()).isEqualTo("/diaries-responder");
        assertThat(loaded.config().content().filesPath()).isEqualTo("files");
        assertThat(loaded.credentials().username()).isEqualTo("diaries-web");
        assertThat(loaded.credentials().toString())
                .contains("********")
                .doesNotContain("secret-value");
    }

    @Test
    void defaultsFilesPathForOlderConfigurationWhichOmitsIt() throws Exception {
        String json = Files.readString(example)
                .replace(",\n    \"filesPath\": \"files\"", "");
        Path legacy = writeTempConfiguration("diaries-web-legacy", json);
        try {
            LoadedConfiguration loaded = new ConfigLoader().load(legacy, credentials);

            assertThat(loaded.config().content().filesPath())
                    .isEqualTo(AppConfig.ContentConfig.DEFAULT_FILES_PATH);
        } finally {
            Files.deleteIfExists(legacy);
        }
    }

    @Test
    void acceptsAndNormalizesCustomNestedFilesRouteWithoutChangingPublicBase() throws Exception {
        String json = Files.readString(example)
                .replace("\"filesPath\": \"files\"", "\"filesPath\": \"/archive files/catalogue/\"");
        Path custom = writeTempConfiguration("diaries-web-custom-files", json);
        try {
            LoadedConfiguration loaded = new ConfigLoader().load(custom, credentials);

            assertThat(loaded.config().content().filesPath()).isEqualTo("archive files/catalogue");
            assertThat(loaded.config().content().responderBaseUrl()).isEqualTo("http://localhost:8081");
            assertThat(loaded.config().content().publicResponderBaseUrl()).isEqualTo("/diaries-responder");
        } finally {
            Files.deleteIfExists(custom);
        }
    }

    @Test
    void acceptsUnicodeAndPercentAsLiteralCharactersInNestedFilesRoute() throws Exception {
        String json = Files.readString(example)
                .replace("\"filesPath\": \"files\"",
                        "\"filesPath\": \"archive/été 100%/catalogue\"");
        Path custom = writeTempConfiguration("diaries-web-unicode-files", json);
        try {
            LoadedConfiguration loaded = new ConfigLoader().load(custom, credentials);

            assertThat(loaded.config().content().filesPath())
                    .isEqualTo("archive/été 100%/catalogue");
            assertThat(loaded.config().content().responderBaseUrl())
                    .isEqualTo("http://localhost:8081");
            assertThat(loaded.config().content().publicResponderBaseUrl())
                    .isEqualTo("/diaries-responder");
        } finally {
            Files.deleteIfExists(custom);
        }
    }

    @Test
    void rejectsUnsafeFilesRoutes() throws Exception {
        for (String value : new String[] {
                "../files",
                "files/../other",
                "files//nested",
                "files\\nested",
                "http://files",
                "files?query",
                "files#fragment"
        }) {
            String json = Files.readString(example)
                    .replace("\"filesPath\": \"files\"",
                            "\"filesPath\": \"" + value.replace("\\", "\\\\") + "\"");
            Path invalid = writeTempConfiguration("diaries-web-files-route", json);
            try {
                assertThatThrownBy(() -> new ConfigLoader().load(invalid, credentials))
                        .as("filesPath %s", value)
                        .isInstanceOf(com.fasterxml.jackson.databind.exc.ValueInstantiationException.class)
                        .hasRootCauseInstanceOf(IllegalArgumentException.class);
            } finally {
                Files.deleteIfExists(invalid);
            }
        }
    }

    @Test
    void rejectsUnsafeBrowserVisibleResponderBases() throws Exception {
        for (String value : new String[] {
                "//evil.example.test/diaries-responder",
                "ftp://content.example.test/diaries-responder",
                "javascript:alert(1)",
                "https://user@content.example.test/diaries-responder",
                "https://content.example.test/diaries-responder?next=/other",
                "https://content.example.test/diaries-responder#fragment",
                "relative/diaries-responder",
                "/diaries-responder/../other",
                "/diaries-responder/%2e%2e/other",
                "https://content.example.test/diaries-responder/%2Fother",
                "/diaries-responder//other"
        }) {
            String json = Files.readString(example)
                    .replace("\"publicResponderBaseUrl\": \"/diaries-responder\"",
                            "\"publicResponderBaseUrl\": \"" + value.replace("\\", "\\\\") + "\"");
            Path invalid = writeTempConfiguration("diaries-web-public-base", json);
            try {
                assertThatThrownBy(() -> new ConfigLoader().load(invalid, credentials))
                        .as("publicResponderBaseUrl %s", value)
                        .isInstanceOf(com.fasterxml.jackson.databind.exc.ValueInstantiationException.class)
                        .hasRootCauseInstanceOf(IllegalArgumentException.class);
            } finally {
                Files.deleteIfExists(invalid);
            }
        }
    }

    @Test
    void rejectsMissingCredentialAndUnknownConfigurationFields() throws Exception {
        assertThatThrownBy(() -> new ConfigLoader().load(example, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(ConfigLoader.MQTT_USERNAME);

        Path invalid = Files.createTempFile("diaries-web-invalid", ".json");
        try {
            Files.writeString(invalid,
                    Files.readString(example).replace(
                            "\"site\": {", "\"unknownSection\": {}, \"site\": {"));
            assertThatThrownBy(() -> new ConfigLoader().load(
                    invalid,
                    Map.of(
                            ConfigLoader.MQTT_USERNAME, "reader",
                            ConfigLoader.MQTT_PASSWORD, "secret")))
                    .isInstanceOf(com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException.class);
        } finally {
            Files.deleteIfExists(invalid);
        }
    }

    private static Path writeTempConfiguration(String prefix, String json) throws Exception {
        Path path = Files.createTempFile(prefix, ".json");
        Files.writeString(path, json);
        return path;
    }
}
