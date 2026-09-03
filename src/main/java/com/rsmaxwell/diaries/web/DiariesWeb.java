package com.rsmaxwell.diaries.web;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rsmaxwell.diaries.web.buildinfo.BuildInfo;
import com.rsmaxwell.diaries.web.config.AppConfig;
import com.rsmaxwell.diaries.web.config.ConfigLoader;
import com.rsmaxwell.diaries.web.config.LoadedConfiguration;
import com.rsmaxwell.diaries.web.http.WebServer;
import com.rsmaxwell.diaries.web.mqtt.MqttProjectionClient;
import com.rsmaxwell.diaries.web.projection.ProjectionService;

public final class DiariesWeb {
    private static final Logger log = LoggerFactory.getLogger(DiariesWeb.class);

    private DiariesWeb() {
    }

    public static void main(String[] args) {
        try {
            LoadedConfiguration loaded = new ConfigLoader().load(args);
            run(loaded);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            System.err.println("Unable to start diaries-web: " + exception.getMessage());
            System.exit(1);
        }
    }

    static void run(LoadedConfiguration loaded) throws Exception {
        AppConfig config = loaded.config();
        AppConfig.ProjectionConfig projectionConfig = config.projection();
        CountDownLatch stopped = new CountDownLatch(1);

        try (ProjectionService projection = new ProjectionService(
                Duration.ofMillis(projectionConfig.initialReplayQuietPeriodMillis()),
                Duration.ofSeconds(projectionConfig.initialReplayTimeoutSeconds()));
                WebServer webServer = new WebServer(config, projection, BuildInfo.load());
                MqttProjectionClient mqtt = new MqttProjectionClient(
                        config.mqtt(), loaded.credentials(), projection)) {
            Thread shutdownHook = new Thread(stopped::countDown, "diaries-web-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            try {
                webServer.start();
                mqtt.start();
                log.info("diaries-web is listening on {}:{}{}",
                        config.http().host(), webServer.port(), config.http().basePath());
                stopped.await();
            } finally {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdownHook);
                } catch (IllegalStateException ignored) {
                    // The JVM is already shutting down.
                }
            }
        }
    }
}
