package com.rsmaxwell.diaries.web.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class ConfigLoader {
    public static final String MQTT_USERNAME = "DIARIES_WEB_MQTT_USERNAME";
    public static final String MQTT_PASSWORD = "DIARIES_WEB_MQTT_PASSWORD";

    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

    public LoadedConfiguration load(String[] args) throws IOException, ParseException {
        return load(args, System.getenv());
    }

    public LoadedConfiguration load(String[] args, Map<String, String> environment)
            throws IOException, ParseException {
        Options options = options();
        CommandLine commandLine = new DefaultParser().parse(options, args);
        if (commandLine.hasOption("help")) {
            new HelpFormatter().printHelp("diaries-web", options);
            throw new IllegalArgumentException("help requested");
        }
        String configPath = commandLine.getOptionValue("config");
        if (configPath == null || configPath.isBlank()) {
            throw new IllegalArgumentException("--config is required");
        }
        return load(Path.of(configPath), environment);
    }

    public LoadedConfiguration load(Path path, Map<String, String> environment) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("configuration file does not exist: " + path);
        }
        AppConfig config = objectMapper.readValue(path.toFile(), AppConfig.class);
        MqttCredentials credentials = new MqttCredentials(
                environment.get(MQTT_USERNAME),
                environment.get(MQTT_PASSWORD));
        return new LoadedConfiguration(config, credentials);
    }

    private static Options options() {
        Options options = new Options();
        options.addOption(Option.builder("c")
                .longOpt("config")
                .hasArg()
                .argName("file")
                .desc("Path to diaries-web JSON configuration")
                .build());
        options.addOption(Option.builder("h")
                .longOpt("help")
                .desc("Show command help")
                .build());
        return options;
    }
}
