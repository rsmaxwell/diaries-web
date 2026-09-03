package com.rsmaxwell.diaries.web;

import java.util.concurrent.CountDownLatch;

import com.rsmaxwell.diaries.web.buildinfo.BuildInfo;
import com.rsmaxwell.diaries.web.config.AppConfig;
import com.rsmaxwell.diaries.web.http.WebServer;
import com.rsmaxwell.diaries.web.projection.ProjectionService;

/** Synthetic-content browser smoke harness; never packaged in the application JAR. */
public final class SyntheticSiteMain {
    private SyntheticSiteMain() {
    }

    public static void main(String[] args) throws Exception {
        AppConfig original = TestData.config("");
        AppConfig config = new AppConfig(
                new AppConfig.HttpConfig("127.0.0.1", 18082, "", "http://127.0.0.1:18082"),
                original.mqtt(), original.projection(), original.content(), original.site());
        ProjectionService projection = TestData.readyProjection();
        WebServer server = new WebServer(
                config,
                projection,
                new BuildInfo("diaries-web", "browser-smoke", "synthetic", "synthetic",
                        "synthetic", "synthetic", "synthetic"));
        CountDownLatch stop = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(stop::countDown));
        try (projection; server) {
            server.start();
            stop.await();
        }
    }
}
