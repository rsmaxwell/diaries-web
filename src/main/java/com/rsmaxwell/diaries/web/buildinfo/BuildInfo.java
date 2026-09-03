package com.rsmaxwell.diaries.web.buildinfo;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

public record BuildInfo(
        String name,
        String version,
        String buildId,
        String buildDate,
        String gitCommit,
        String gitBranch,
        String gitUrl) {

    public static BuildInfo load() {
        Properties properties = new Properties();
        try (InputStream input = BuildInfo.class.getResourceAsStream("/build-info.properties")) {
            if (input != null) {
                properties.load(input);
            }
        } catch (IOException ignored) {
            // Safe fallback below keeps local builds runnable.
        }
        return new BuildInfo(
                properties.getProperty("name", "diaries-web"),
                properties.getProperty("version", "unknown"),
                properties.getProperty("buildId", "(none)"),
                properties.getProperty("buildDate", "unknown"),
                properties.getProperty("gitCommit", "(none)"),
                properties.getProperty("gitBranch", "(none)"),
                properties.getProperty("gitUrl", "(none)"));
    }

    public Map<String, Object> asMap() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", name);
        values.put("version", version);
        values.put("buildId", buildId);
        values.put("buildDate", buildDate);
        values.put("gitCommit", gitCommit);
        values.put("gitBranch", gitBranch);
        values.put("gitUrl", gitUrl);
        return values;
    }
}
