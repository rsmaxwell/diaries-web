package com.rsmaxwell.diaries.web;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import com.rsmaxwell.diaries.web.buildinfo.BuildInfo;
import com.rsmaxwell.diaries.web.config.AppConfig;
import com.rsmaxwell.diaries.web.http.WebServer;
import com.rsmaxwell.diaries.web.model.ImageItem;
import com.rsmaxwell.diaries.web.projection.ProjectionEvent;
import com.rsmaxwell.diaries.web.projection.ProjectionService;

/** Synthetic-content browser smoke harness; never packaged in the application JAR. */
public final class SyntheticSiteMain {
    private static final int SITE_PORT = 18082;
    private static final int CONTENT_PORT = 18083;

    // Small visible PNG. The browser performs real image decoding while the fixture
    // stays deterministic and source-control friendly.
    private static final byte[] VALID_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAIAAAD8GO2jAAAAQklEQVR4nGNce+MZAynAg+0sSeqZSFJNBhi1YNSCUQtGLRi1gBqARe33W5I0zBa7S5L6oR9EoxaMWjBqwagFI8ICAKrUB2T0egMzAAAAAElFTkSuQmCC");

    private SyntheticSiteMain() {
    }

    public static void main(String[] args) throws Exception {
        AppConfig original = TestData.config("");
        AppConfig.ContentConfig content = new AppConfig.ContentConfig(
                original.content().responderBaseUrl(),
                "http://127.0.0.1:" + CONTENT_PORT,
                original.content().diariesPath(),
                original.content().filesPath());
        AppConfig config = new AppConfig(
                new AppConfig.HttpConfig("127.0.0.1", SITE_PORT, "", "http://127.0.0.1:" + SITE_PORT),
                original.mqtt(), original.projection(), content, original.site());

        ProjectionService projection = TestData.readyMixedMediaProjection();
        addFileFailureFixtures(projection);
        WebServer server = new WebServer(
                config,
                projection,
                new BuildInfo("diaries-web", "browser-smoke", "synthetic", "synthetic",
                        "synthetic", "synthetic", "synthetic"));
        HttpServer contentServer = syntheticContentServer();

        CountDownLatch stop = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(stop::countDown));
        try (projection; server) {
            contentServer.start();
            server.start();
            System.out.println("Synthetic site:    http://127.0.0.1:" + SITE_PORT);
            System.out.println("Synthetic content: http://127.0.0.1:" + CONTENT_PORT);
            stop.await();
        } finally {
            contentServer.stop(0);
        }
    }

    private static void addFileFailureFixtures(ProjectionService projection) {
        projection.accept(new ProjectionEvent.UpsertImage(new ImageItem(
                67, 0, "diary-2026/images/invalid bytes.png", "image/png", "invalid bytes.png",
                640, 480, "56".repeat(32), "HTTP 200 with invalid image bytes", "Invalid bytes fixture"))).join();
        projection.accept(new ProjectionEvent.UpsertFragment(
                TestData.imageFragment(45, 13, 67L, "<p>Invalid image bytes</p>"))).join();

        projection.accept(new ProjectionEvent.UpsertImage(new ImageItem(
                68, 0, "diary-2026/images/missing file.png", "image/png", "missing file.png",
                640, 480, "78".repeat(32), "HTTP 404 file fixture", "Missing file fixture"))).join();
        projection.accept(new ProjectionEvent.UpsertFragment(
                TestData.imageFragment(46, 14, 68L, "<p>Missing image file</p>"))).join();
    }

    private static HttpServer syntheticContentServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", CONTENT_PORT), 0);
        server.createContext("/", SyntheticSiteMain::serveSyntheticContent);
        return server;
    }

    private static void serveSyntheticContent(HttpExchange exchange) throws IOException {
        try (exchange) {
            String rawPath = exchange.getRequestURI().getRawPath();
            if (!"GET".equals(exchange.getRequestMethod()) && !"HEAD".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Allow", "GET, HEAD");
                exchange.sendResponseHeaders(405, -1);
                return;
            }

            if (rawPath.endsWith("/missing%20file.png")) {
                send(exchange, 404, "text/plain; charset=utf-8",
                        "synthetic missing image".getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (rawPath.endsWith("/invalid%20bytes.png")) {
                send(exchange, 200, "image/png",
                        "this is deliberately not a PNG".getBytes(StandardCharsets.UTF_8));
                return;
            }

            if (rawPath.startsWith("/diaries/") || rawPath.startsWith("/files/")) {
                send(exchange, 200, "image/png", VALID_PNG);
                return;
            }

            send(exchange, 404, "text/plain; charset=utf-8",
                    "unknown synthetic content".getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void send(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        if ("HEAD".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(status, -1);
            return;
        }
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }
}
