package com.rsmaxwell.diaries.web;

import java.math.BigDecimal;
import java.time.Duration;

import com.rsmaxwell.diaries.web.config.AppConfig;
import com.rsmaxwell.diaries.web.model.DiaryItem;
import com.rsmaxwell.diaries.web.model.FragmentItem;
import com.rsmaxwell.diaries.web.model.FragmentType;
import com.rsmaxwell.diaries.web.model.ImageItem;
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
                "<p>A diary entry<script>alert(1)</script></p>", 22L, FragmentType.MARQUEE, null, 44L);
    }

    public static MarqueeItem marquee() {
        return new MarqueeItem(44, 5, 22, 33, new RectangleItem(10.5, 20.5, 300, 200));
    }


    public static ImageItem catalogueImage() {
        return new ImageItem(
                60, 2,
                "diary-2026/images/shared detail +#?.png",
                "image/png",
                "shared detail +#?.png",
                1600,
                900,
                "ab".repeat(32),
                "Shared <caption> & detail",
                "Map <east> & west");
    }

    public static ImageItem catalogueImageWithEmptyAlt() {
        return new ImageItem(
                63, 0,
                "diary-2026/images/decorative scan.jpg",
                "image/jpeg",
                "decorative scan.jpg",
                800,
                600,
                "cd".repeat(32),
                "",
                "");
    }

    public static ImageItem tallCatalogueImage() {
        return new ImageItem(
                64, 0,
                "diary-2026/images/tall portrait.png",
                "image/png",
                "tall portrait.png",
                400,
                1600,
                "ef".repeat(32),
                "Long caption <strong>not markup</strong> " + "detail ".repeat(40),
                "Tall image");
    }

    public static ImageItem wideCatalogueImage() {
        return new ImageItem(
                65, 0,
                "diary-2026/images/wide panorama.png",
                "image/png",
                "wide panorama.png",
                2400,
                300,
                "12".repeat(32),
                "",
                "Wide image");
    }

    public static ImageItem smallCatalogueImage() {
        return new ImageItem(
                66, 0,
                "diary-2026/images/small scan.png",
                "image/png",
                "small scan.png",
                64,
                48,
                "34".repeat(32),
                "Small image",
                "Small image");
    }

    public static FragmentItem imageFragment(long id, int day, Long imageId, String text) {
        return new FragmentItem(
                id, 0, 2026, 9, day, new BigDecimal(id + ".0"), text,
                22L, FragmentType.IMAGE, imageId, null);
    }

    public static FragmentItem unknownFragment(long id, int day, String rawType, String text) {
        return new FragmentItem(
                id, 0, 2026, 9, day, new BigDecimal(id + ".0"), text,
                22L, FragmentType.UNKNOWN, rawType, null, null);
    }

    public static ProjectionService readyMixedMediaProjection() {
        ProjectionService service = readyProjection();
        service.accept(new ProjectionEvent.UpsertImage(catalogueImage())).join();
        service.accept(new ProjectionEvent.UpsertFragment(
                imageFragment(35, 3, 60L, "<p>Shared image one</p>"))).join();
        service.accept(new ProjectionEvent.UpsertFragment(
                imageFragment(36, 4, 60L, "<p>Shared image two</p>"))).join();
        service.accept(new ProjectionEvent.UpsertFragment(
                imageFragment(37, 5, 61L, "<p>Missing image metadata</p>"))).join();
        service.accept(new ProjectionEvent.UpsertFragment(
                unknownFragment(38, 6, "AUDIO", "<p>Unknown typed media</p>"))).join();
        service.accept(new ProjectionEvent.UpsertFragment(
                imageFragment(39, 7, null, "<p>No image selected</p>"))).join();
        service.accept(new ProjectionEvent.UpsertFragment(
                imageFragment(40, 8, 62L, "<p>Invalid image metadata</p>"))).join();
        // Deliberately inconsistent retained data: IMAGE selection must never expose
        // this Marquee to the HTTP view model.
        service.accept(new ProjectionEvent.UpsertMarquee(
                new MarqueeItem(46, 0, 22, 35, new RectangleItem(40, 50, 60, 70)))).join();
        service.recordInvalidImage(62).join();
        service.accept(new ProjectionEvent.UpsertImage(catalogueImageWithEmptyAlt())).join();
        service.accept(new ProjectionEvent.UpsertFragment(
                imageFragment(41, 9, 63L, "<p>Decorative image with empty alt</p>"))).join();
        service.accept(new ProjectionEvent.UpsertImage(tallCatalogueImage())).join();
        service.accept(new ProjectionEvent.UpsertFragment(
                imageFragment(42, 10, 64L, ""))).join();
        service.accept(new ProjectionEvent.UpsertImage(wideCatalogueImage())).join();
        service.accept(new ProjectionEvent.UpsertFragment(
                imageFragment(43, 11, 65L, "<p>Wide image</p>"))).join();
        service.accept(new ProjectionEvent.UpsertImage(smallCatalogueImage())).join();
        service.accept(new ProjectionEvent.UpsertFragment(
                imageFragment(44, 12, 66L, "<p>Small image</p>"))).join();
        return service;
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
                        "<p>An earlier entry</p>", 22L, FragmentType.MARQUEE, null, 43L))).join();
        service.accept(new ProjectionEvent.UpsertFragment(fragment())).join();
        service.accept(new ProjectionEvent.UpsertFragment(
                new FragmentItem(34, 1, 2026, 9, 2, new BigDecimal("4.0"),
                        "<p>A later entry</p>", 23L, FragmentType.MARQUEE, null, 45L))).join();
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
                        "diaries",
                        "files"),
                new AppConfig.SiteConfig("Diaries", "Read-only diary", "en-GB", "Europe/London"));
    }
}
