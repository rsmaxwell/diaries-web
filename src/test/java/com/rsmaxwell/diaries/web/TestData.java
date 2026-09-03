package com.rsmaxwell.diaries.web;

import java.math.BigDecimal;
import java.time.Duration;

import com.rsmaxwell.diaries.web.config.AppConfig;
import com.rsmaxwell.diaries.web.model.DiaryItem;
import com.rsmaxwell.diaries.web.model.FragmentItem;
import com.rsmaxwell.diaries.web.model.MarqueeItem;
import com.rsmaxwell.diaries.web.model.PageItem;
import com.rsmaxwell.diaries.web.model.RectangleItem;
import com.rsmaxwell.diaries.web.projection.ProjectionEvent;
import com.rsmaxwell.diaries.web.projection.ProjectionService;

public final class TestData {
    private TestData() {
    }

    public static DiaryItem diary() {
        return new DiaryItem(11, 2, "Family diary", new BigDecimal("1.25"));
    }

    public static PageItem page() {
        return new PageItem(22, 3, 11, "page 001", new BigDecimal("2.0"), "jpg", 1200, 800);
    }

    public static FragmentItem fragment() {
        return new FragmentItem(
                33, 4, 2026, 9, 1, new BigDecimal("3.5"),
                "<p>A diary entry<script>alert(1)</script></p>", 44L);
    }

    public static MarqueeItem marquee() {
        return new MarqueeItem(44, 5, 22, 33, new RectangleItem(10.5, 20.5, 300, 200));
    }

    public static ProjectionService readyProjection() {
        ProjectionService service = new ProjectionService(Duration.ofMillis(20), Duration.ofSeconds(2));
        service.beginReplay(false).join();
        service.accept(new ProjectionEvent.UpsertDiary(
                new DiaryItem(10, 1, "Earlier diary", new BigDecimal("1.0")))).join();
        service.accept(new ProjectionEvent.UpsertDiary(diary())).join();
        service.accept(new ProjectionEvent.UpsertDiary(
                new DiaryItem(12, 1, "Later diary", new BigDecimal("2.0")))).join();
        service.accept(new ProjectionEvent.UpsertPage(page())).join();
        service.accept(new ProjectionEvent.UpsertPage(
                new PageItem(23, 1, 11, "page 002", new BigDecimal("3.0"), "jpg", 1200, 800))).join();
        service.accept(new ProjectionEvent.UpsertPage(
                new PageItem(24, 1, 11, "page 003", new BigDecimal("4.0"), "jpg", 1200, 800))).join();
        service.accept(new ProjectionEvent.UpsertFragment(
                new FragmentItem(32, 1, 2026, 8, 31, new BigDecimal("1.0"),
                        "<p>An earlier entry</p>", 43L))).join();
        service.accept(new ProjectionEvent.UpsertFragment(fragment())).join();
        service.accept(new ProjectionEvent.UpsertFragment(
                new FragmentItem(34, 1, 2026, 9, 2, new BigDecimal("4.0"),
                        "<p>A later entry</p>", 45L))).join();
        service.accept(new ProjectionEvent.UpsertMarquee(
                new MarqueeItem(43, 1, 22, 32, new RectangleItem(5, 10, 100, 75)))).join();
        service.accept(new ProjectionEvent.UpsertMarquee(marquee())).join();
        service.accept(new ProjectionEvent.UpsertMarquee(
                new MarqueeItem(45, 1, 23, 34, new RectangleItem(15, 25, 125, 80)))).join();
        service.subscriptionsAcknowledged().join();
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(2))
                .until(() -> service.status().ready());
        return service;
    }

    public static AppConfig config(String basePath) {
        return new AppConfig(
                new AppConfig.HttpConfig("127.0.0.1", 0, basePath, "https://reader.example.test"),
                new AppConfig.MqttConfig("broker", 1883, "diaries-web", "diaries", 30, 2, 1, true),
                new AppConfig.ProjectionConfig(20, 2),
                new AppConfig.ContentConfig(
                        "http://diaries-responder:8080",
                        "https://content.example.test",
                        "diaries"),
                new AppConfig.SiteConfig("Diaries", "Read-only diary", "en-GB", "Europe/London"));
    }
}
