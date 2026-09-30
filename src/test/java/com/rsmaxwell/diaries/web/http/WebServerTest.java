package com.rsmaxwell.diaries.web.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.rsmaxwell.diaries.web.TestData;
import com.rsmaxwell.diaries.web.buildinfo.BuildInfo;
import com.rsmaxwell.diaries.web.model.MarqueeItem;
import com.rsmaxwell.diaries.web.model.FragmentItem;
import com.rsmaxwell.diaries.web.model.FragmentType;
import com.rsmaxwell.diaries.web.model.ImageItem;
import com.rsmaxwell.diaries.web.model.RectangleItem;
import com.rsmaxwell.diaries.web.projection.ProjectionEvent;
import com.rsmaxwell.diaries.web.projection.ProjectionService;

class WebServerTest {
    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(3)).build();

    @Test
    void distinguishesProcessLivenessFromProjectionReadiness() throws Exception {
        try (ProjectionService projection = new ProjectionService(Duration.ofMillis(20), Duration.ofSeconds(2));
                WebServer server = server(projection)) {
            server.start();
            HttpResponse<String> live = get(server, "/reader/health/live");
            HttpResponse<String> ready = get(server, "/reader/health/ready");
            HttpResponse<String> content = get(server, "/reader/");
            assertThat(live.statusCode()).isEqualTo(200);
            assertThat(live.body()).contains("\"status\":\"UP\"");
            assertThat(ready.statusCode()).isEqualTo(503);
            assertThat(ready.body()).contains("\"relationships\"", "\"fragmentsWithoutPageId\"", "\"images\":0");
            assertThat(content.statusCode()).isEqualTo(503);
            assertThat(content.headers().firstValue("Retry-After")).contains("3");
            assertThat(content.body()).contains("projection is synchronising");
            assertThat(content.body()).doesNotContain("broker", "password", "mqtt.host");
        }
    }

    @Test
    void readinessReportsProjectedImageCount() throws Exception {
        try (ProjectionService projection = TestData.readyProjection(); WebServer server = server(projection)) {
            projection.accept(new ProjectionEvent.UpsertImage(new ImageItem(
                    60, 0, "maps/image.png", "image/png", "image.png",
                    1600, 900, "ab".repeat(32), "caption", "alt"))).join();
            server.start();

            HttpResponse<String> ready = get(server, "/reader/health/ready");

            assertThat(ready.statusCode()).isEqualTo(200);
            assertThat(ready.body()).contains("\"status\":\"UP\"", "\"images\":1");
        }
    }

    @Test
    void rendersDiaryContentsAndMonthReaderFromOneSnapshot() throws Exception {
        try (ProjectionService projection = TestData.readyProjection(); WebServer server = server(projection)) {
            server.start();
            HttpResponse<String> index = get(server, "/reader/");
            HttpResponse<String> diary = get(server, "/reader/diaries/11");
            HttpResponse<String> month = get(server, "/reader/diaries/11/2026/09");
            HttpResponse<String> selected = get(server, "/reader/diaries/11/2026/09?fragment=34");
            HttpResponse<String> source = get(server, "/reader/diaries/11/pages/22");
            HttpResponse<String> untranscribedSource = get(server, "/reader/diaries/11/pages/24");
            HttpResponse<String> css = get(server, "/reader/assets/css/diaries.css");
            HttpResponse<String> javascript = get(server, "/reader/assets/js/diaries.js");

            assertThat(index.statusCode()).isEqualTo(200);
            assertThat(index.body()).contains("Family diary", "href=\"/reader/diaries/11\"");
            assertThat(diary.statusCode()).isEqualTo(200);
            assertThat(diary.body()).contains("Published months", "September 2026", "Original pages", "page 001",
                    "name=\"month\"", "rel=\"prev\" href=\"/reader/diaries/10\"",
                    "rel=\"next\" href=\"/reader/diaries/12\"");
            assertThat(diary.body()).doesNotContain("1 September 2026");
            assertThat(month.statusCode()).isEqualTo(200);
            assertThat(month.body()).contains("<title>Diaries Browser</title>", "September 2026", "data-month-reader",
                    "data-viewer-svg", "data-viewer-marquee", "data-reader-fragment=\"33\"",
                    "data-reader-fragment=\"34\"", "Tuesday 1",
                    "Wednesday 2", "Fit page", "Fit selection", "Use highlight style",
                    "data-viewer-dimming", "data-viewer-mask-selection", "data-viewer-marquee-targets",
                    "Next fragment", "https://content.example.test/diaries/Family%20diary/page%20001.jpg");
            assertThat(month.body()).containsPattern(
                    "(?s)<div class=\"fragment__content\" data-fragment-selector=\"33\" role=\"button\" tabindex=\"0\".*?A diary entry");
            assertThat(month.body()).doesNotContain("<script>", "alert(1)",
                    ">Transcription</h2>", "Tuesday, 1 September 2026", "Wednesday, 2 September 2026",
                    "Selected source", "Original source: page 001", "fragment__source",
                    ">Month reader</p>", "2 published fragments", "data-viewer-action=\"reset\"");
            assertThat(selected.statusCode()).isEqualTo(200);
            assertThat(month.body()).contains("reader-viewer is-focus-mode has-selection");
            assertThat(month.body()).doesNotContain("data-viewer-action=\"fit-selection\" disabled");
            assertThat(selected.body()).contains("Previous fragment", "page 002",
                    "https://content.example.test/diaries/Family%20diary/page%20002.jpg");
            assertThat(selected.body()).containsPattern("(?s)data-reader-fragment=\"34\".*?aria-current=\"true\"");
            assertThat(month.headers().firstValue("ETag")).isNotEqualTo(selected.headers().firstValue("ETag"));
            assertThat(source.statusCode()).isEqualTo(200);
            assertThat(source.body()).contains("Original diary page");
            assertThat(source.body()).doesNotContain("No published transcript is linked");
            assertThat(source.body()).contains("/reader/diaries/11/2026/09?fragment=33#fragment-33");
            assertThat(untranscribedSource.statusCode()).isEqualTo(200);
            assertThat(untranscribedSource.body()).contains("page 003", "No published transcript is linked");
            assertThat(untranscribedSource.body()).doesNotContain("Original diary page");
            assertThat(css.body()).contains(".month-reader", ".reader-viewer.is-expanded", ".marquee.is-unavailable",
                    ".reader-viewer.is-focus-mode.has-selection .viewer-dimming", ".marquee-target:focus",
                    "fill: rgba(40, 40, 40, .65)",
                    ".fragment__content[data-fragment-selector]",
                    ".fragment-media__image", "height: auto", ".fragment-media__status", ".fragment-media__file-status",
                    "@media (max-width: 48rem)",
                    ".contents-layout, .source-layout, .month-reader { grid-template-columns: 1fr; }",
                    ".month-heading h1 { font-size: clamp(1.5rem, 2.5vw, 2.15rem)",
                    ".source-page-heading--untranscribed h1 { font-size: clamp(1.5rem, 2.5vw, 2.15rem)");
            assertThat(javascript.body()).contains("initialiseMonthReader", "zoomAt", "window.history.pushState",
                    "window.history.replaceState", "pointermove", "ResizeObserver", "keydown", "aria-pressed",
                    "const previousSourcePoint = sourcePoint", "const currentSourcePoint = sourcePoint");
            assertThat(javascript.body()).contains("toggle-display", "is-focus-mode", "Use highlight style");
            assertThat(javascript.body()).contains("renderMarqueeTargets", "document.createElementNS",
                    "selectFromMarquee", "scrollIntoView", "event.key !== 'Enter' && event.key !== ' '");
            assertThat(javascript.body()).contains(
                    "initialiseCatalogueMedia", "FILE_LOAD_FAILED", "mediaLoadHandlers",
                    "mediaImage.hidden = true", "data-media-file-status", "fragmentType",
                    "renderSelectedRegion", "fitSelectionButton.disabled = !data.hasMarquee",
                    "replaceSourceImage", "sourceRequestId", "selectionViewerMessage",
                    "window.addEventListener('hashchange', restoreFromLocation)",
                    "window.history.pushState({ fragmentId: id }, '', `#fragment-${id}`)");
            assertThat(javascript.body()).doesNotContain("view.x = clamp", "view.y = clamp",
                    "mediaImage.setAttribute('src'", "mediaImage.src =");
            assertThat(index.headers().firstValue("Content-Security-Policy"))
                    .hasValueSatisfying(value -> assertThat(value)
                            .contains("default-src 'none'", "form-action 'self'",
                                    "img-src https://content.example.test data:")
                            .doesNotContain("http://diaries-responder:8080", "unsafe-inline", "unsafe-eval"));
        }
    }

    @Test
    void acceptsAndClipsAMarqueeWhichExtendsBeyondThePageOrigin() throws Exception {
        try (ProjectionService projection = TestData.readyProjection(); WebServer server = server(projection)) {
            projection.accept(new ProjectionEvent.UpsertMarquee(
                    new MarqueeItem(44, 6, 22, 33, new RectangleItem(-10, -20, 300, 200)))).join();
            server.start();

            HttpResponse<String> month = get(server, "/reader/diaries/11/2026/09?fragment=33");

            assertThat(month.statusCode()).isEqualTo(200);
            assertThat(month.body()).contains(
                    "data-marquee-x=\"0.0\"", "data-marquee-y=\"0.0\"",
                    "data-marquee-width=\"290.0\"", "data-marquee-height=\"180.0\"");
        }
    }

    @Test
    void rendersPageOwnedFragmentWhenItsMarqueeIsUnavailable() throws Exception {
        try (ProjectionService projection = TestData.readyProjection(); WebServer server = server(projection)) {
            projection.accept(new ProjectionEvent.UpsertFragment(new FragmentItem(
                    35, 0, 2026, 9, 3, java.math.BigDecimal.ONE, "<p>No marquee text</p>",
                    22L, FragmentType.MARQUEE, null, null))).join();
            server.start();

            HttpResponse<String> month = get(server, "/reader/diaries/11/2026/09?fragment=35");
            HttpResponse<String> source = get(server, "/reader/diaries/11/pages/22");

            assertThat(month.statusCode()).isEqualTo(200);
            assertThat(month.body()).contains("No marquee text", "data-has-marquee=\"false\"");
            assertThat(month.body()).doesNotContain("reader-viewer is-focus-mode has-selection");
            assertThat(source.statusCode()).isEqualTo(200);
            assertThat(source.body()).contains("No marquee text", "data-fragment-selector=\"35\"", "Select fragment");
            assertThat(source.body()).doesNotContain("data-marquee-fragment=\"35\"");
        }
    }


    @Test
    void suppliesOneTypedMediaViewForMonthAndSourceRendering() {
        try (ProjectionService projection = TestData.readyMixedMediaProjection(); WebServer server = server(projection)) {
            Map<String, Object> marquee = server.fragmentMediaView(
                    projection.snapshot().resolveFragment(33).orElseThrow());
            Map<String, Object> first = server.fragmentMediaView(
                    projection.snapshot().resolveFragment(35).orElseThrow());
            Map<String, Object> second = server.fragmentMediaView(
                    projection.snapshot().resolveFragment(36).orElseThrow());
            Map<String, Object> missing = server.fragmentMediaView(
                    projection.snapshot().resolveFragment(37).orElseThrow());
            Map<String, Object> unknown = server.fragmentMediaView(
                    projection.snapshot().resolveFragment(38).orElseThrow());
            Map<String, Object> noSelection = server.fragmentMediaView(
                    projection.snapshot().resolveFragment(39).orElseThrow());
            Map<String, Object> invalid = server.fragmentMediaView(
                    projection.snapshot().resolveFragment(40).orElseThrow());

            assertThat(marquee).containsEntry("fragmentType", "MARQUEE")
                    .containsEntry("mediaState", "NOT_APPLICABLE")
                    .containsEntry("hasMarquee", true)
                    .containsEntry("mediaUrl", null)
                    .containsEntry("mediaUnavailableText", null);
            assertThat(first).containsEntry("fragmentType", "IMAGE")
                    .containsEntry("mediaState", "AVAILABLE")
                    .containsEntry("hasMarquee", false)
                    .containsEntry("mediaUrl",
                            "https://content.example.test/files/diary-2026/images/shared%20detail%20%2B%23%3F.png")
                    .containsEntry("mediaAltText", "Map <east> & west")
                    .containsEntry("mediaCaption", "Shared <caption> & detail")
                    .containsEntry("mediaWidth", 1600)
                    .containsEntry("mediaHeight", 900)
                    .containsEntry("mediaUnavailableText", null);
            assertThat(second).containsEntry("mediaUrl", first.get("mediaUrl"))
                    .containsEntry("mediaCaption", first.get("mediaCaption"));
            assertThat(missing).containsEntry("fragmentType", "IMAGE")
                    .containsEntry("mediaState", "MISSING_METADATA")
                    .containsEntry("hasMarquee", false)
                    .containsEntry("mediaUrl", null)
                    .containsEntry("mediaUnavailableText", "Image unavailable");
            assertThat(unknown).containsEntry("fragmentType", "AUDIO")
                    .containsEntry("mediaState", "UNSUPPORTED_TYPE")
                    .containsEntry("hasMarquee", false)
                    .containsEntry("mediaUrl", null)
                    .containsEntry("mediaUnavailableText", "Unsupported fragment type");
            assertThat(noSelection).containsEntry("fragmentType", "IMAGE")
                    .containsEntry("mediaState", "NO_SELECTION")
                    .containsEntry("mediaUrl", null)
                    .containsEntry("mediaUnavailableText", "No image selected");
            assertThat(invalid).containsEntry("fragmentType", "IMAGE")
                    .containsEntry("mediaState", "INVALID_METADATA")
                    .containsEntry("hasMarquee", false)
                    .containsEntry("mediaUrl", null)
                    .containsEntry("mediaUnavailableText", "Image unavailable");
        }
    }

    @Test
    void mixedTypedFragmentsRemainAvailableThroughMonthAndSourceHttpResponses() throws Exception {
        try (ProjectionService projection = TestData.readyMixedMediaProjection(); WebServer server = server(projection)) {
            server.start();

            HttpResponse<String> selectedImage = get(server, "/reader/diaries/11/2026/09?fragment=35");
            HttpResponse<String> source = get(server, "/reader/diaries/11/pages/22");
            HttpResponse<String> fragmentRedirect = get(server, "/reader/fragments/35");
            HttpResponse<String> head = send(server, "/reader/diaries/11/2026/09?fragment=35", "HEAD", null, null);

            assertThat(selectedImage.statusCode()).isEqualTo(200);
            String sharedImageUrl =
                    "https://content.example.test/files/diary-2026/images/shared%20detail%20%2B%23%3F.png";
            String emptyAltImageUrl =
                    "https://content.example.test/files/diary-2026/images/decorative%20scan.jpg";
            String tallImageUrl =
                    "https://content.example.test/files/diary-2026/images/tall%20portrait.png";
            String wideImageUrl =
                    "https://content.example.test/files/diary-2026/images/wide%20panorama.png";
            String smallImageUrl =
                    "https://content.example.test/files/diary-2026/images/small%20scan.png";

            assertThat(selectedImage.body()).contains(
                    "Shared image one", "Shared image two", "Missing image metadata",
                    "Unknown typed media", "No image selected", "Invalid image metadata",
                    "Decorative image with empty alt",
                    "data-reader-fragment=\"35\"", "data-has-marquee=\"false\"",
                    "data-fragment-type=\"IMAGE\"", "data-media-state=\"AVAILABLE\"",
                    "class=\"fragment-media__image\"", sharedImageUrl,
                    "alt=\"Map &lt;east&gt; &amp; west\"", "width=\"1600\"", "height=\"900\"",
                    "Shared &lt;caption&gt; &amp; detail", "Open image directly",
                    "Image unavailable", "Unsupported fragment type", "No image selected",
                    emptyAltImageUrl, "alt=\"\"", "width=\"800\"", "height=\"600\"",
                    tallImageUrl, "alt=\"Tall image\"", "width=\"400\"", "height=\"1600\"",
                    wideImageUrl, "alt=\"Wide image\"", "width=\"2400\"", "height=\"300\"",
                    smallImageUrl, "alt=\"Small image\"", "width=\"64\"", "height=\"48\"",
                    "Long caption &lt;strong&gt;not markup&lt;/strong&gt; detail detail detail",
                    "data-reader-fragment=\"42\"");
            assertThat(selectedImage.body()).containsPattern(
                    "(?s)data-reader-fragment=\"35\".*?aria-current=\"true\"");
            assertThat(selectedImage.body()).contains("data-viewer-action=\"fit-selection\" disabled");
            assertThat(selectedImage.body()).containsPattern(
                    "(?s)data-viewer-action=\"toggle-display\".*?disabled.*?>Use highlight style</button>");
            assertThat(occurrences(selectedImage.body(), sharedImageUrl)).isEqualTo(4);
            assertThat(occurrences(selectedImage.body(), "data-fragment-media=\"35\"")).isEqualTo(1);
            assertThat(occurrences(selectedImage.body(), "data-fragment-media=\"36\"")).isEqualTo(1);
            assertThat(selectedImage.body()).containsPattern(
                    "(?s)data-reader-fragment=\"35\".*?Show the corresponding original source page\\.");
            assertThat(selectedImage.body()).doesNotContain(
                    "reader-viewer is-focus-mode has-selection",
                    "Shared <caption> & detail", "Map <east> & west",
                    "<strong>not markup</strong>",
                    "src=\"\"", "src=\"null\"");

            assertThat(source.statusCode()).isEqualTo(200);
            assertThat(source.body()).contains(
                    "Shared image one", "Shared image two", "Missing image metadata",
                    "Unknown typed media", "No image selected", "Invalid image metadata",
                    "Decorative image with empty alt", sharedImageUrl, emptyAltImageUrl,
                    tallImageUrl, wideImageUrl, smallImageUrl,
                    "alt=\"Map &lt;east&gt; &amp; west\"", "alt=\"\"",
                    "alt=\"Tall image\"", "alt=\"Wide image\"", "alt=\"Small image\"",
                    "Shared &lt;caption&gt; &amp; detail", "Open image directly",
                    "Long caption &lt;strong&gt;not markup&lt;/strong&gt; detail detail detail",
                    "Image unavailable", "Unsupported fragment type", "No image selected",
                    "data-fragment-selector=\"35\"", "Select image fragment",
                    "data-fragment-selector=\"38\"", "Select fragment");
            assertThat(occurrences(source.body(), sharedImageUrl)).isEqualTo(4);
            assertThat(source.body()).doesNotContain(
                    "data-marquee-fragment=\"35\"",
                    "src=\"\"", "src=\"null\"");

            assertThat(fragmentRedirect.statusCode()).isEqualTo(302);
            assertThat(fragmentRedirect.headers().firstValue("Location"))
                    .contains("/reader/diaries/11/2026/09?fragment=35#fragment-35");
            assertThat(head.statusCode()).isEqualTo(200);
            assertThat(head.body()).isEmpty();
        }
    }

    @Test
    void step11CoversMixedChronologyDegradedMediaMissingOwnershipAndReadOnlyHttpBoundaries() throws Exception {
        try (ProjectionService projection = TestData.readyMixedMediaProjection(); WebServer server = server(projection)) {
            projection.accept(new ProjectionEvent.UpsertFragment(new FragmentItem(90, 0, 2026, 9, 13,
                    java.math.BigDecimal.ONE, "<p>Must not render without Page ownership</p>",
                    null, FragmentType.IMAGE, 60L, null))).join();
            projection.accept(new ProjectionEvent.UpsertFragment(new FragmentItem(91, 0, 2026, 9, 13,
                    new java.math.BigDecimal("2"), "<p>Must not render with missing Page</p>",
                    999L, FragmentType.IMAGE, 60L, null))).join();
            server.start();

            HttpResponse<String> month = get(server, "/reader/diaries/11/2026/09?fragment=35");
            HttpResponse<String> source = get(server, "/reader/diaries/11/pages/22");
            HttpResponse<String> sourceHead = send(server, "/reader/diaries/11/pages/22", "HEAD", null, null);
            HttpResponse<String> sourcePost = send(server, "/reader/diaries/11/pages/22", "POST", null, null);
            HttpResponse<String> imageRedirect = get(server, "/reader/fragments/35");

            assertThat(month.statusCode()).isEqualTo(200);
            assertAppearsInOrder(month.body(),
                    "data-reader-fragment=\"33\"",
                    "data-reader-fragment=\"34\"",
                    "data-reader-fragment=\"35\"",
                    "data-reader-fragment=\"36\"",
                    "data-reader-fragment=\"37\"",
                    "data-reader-fragment=\"38\"",
                    "data-reader-fragment=\"39\"",
                    "data-reader-fragment=\"40\"");
            assertThat(month.body()).contains(
                    "data-fragment-type=\"IMAGE\"",
                    "data-media-state=\"AVAILABLE\"",
                    "data-media-state=\"MISSING_METADATA\"",
                    "data-media-state=\"INVALID_METADATA\"",
                    "data-media-state=\"NO_SELECTION\"",
                    "data-fragment-type=\"AUDIO\"",
                    "data-media-state=\"UNSUPPORTED_TYPE\"",
                    "Image unavailable", "No image selected", "Unsupported fragment type")
                    .doesNotContain("Must not render without Page ownership", "Must not render with missing Page",
                            "http://diaries-responder:8080");
            assertThat(month.body()).doesNotContain("data-marquee-fragment=\"35\"");

            assertThat(source.statusCode()).isEqualTo(200);
            assertThat(source.body()).contains(
                    "data-transcript-fragment=\"35\"",
                    "data-fragment-selector=\"35\"",
                    "Select image fragment",
                    "data-transcript-fragment=\"38\"",
                    "Select fragment")
                    .doesNotContain("data-marquee-fragment=\"35\"",
                            "Must not render without Page ownership", "Must not render with missing Page",
                            "http://diaries-responder:8080");

            assertThat(sourceHead.statusCode()).isEqualTo(200);
            assertThat(sourceHead.body()).isEmpty();
            assertThat(sourcePost.statusCode()).isEqualTo(405);
            assertThat(sourcePost.headers().firstValue("Allow")).contains("GET, HEAD");
            assertThat(imageRedirect.statusCode()).isEqualTo(302);
            assertThat(imageRedirect.headers().firstValue("Location"))
                    .contains("/reader/diaries/11/2026/09?fragment=35#fragment-35");

            assertThat(month.headers().firstValue("Content-Security-Policy"))
                    .hasValueSatisfying(value -> assertThat(value)
                            .contains("default-src 'none'", "script-src 'self'",
                                    "img-src https://content.example.test data:")
                            .doesNotContain("http://diaries-responder:8080", "unsafe-inline", "unsafe-eval"));
        }
    }

    @Test
    void redirectsLegacyAndCanonicalRoutesToTheMonthReader() throws Exception {
        try (ProjectionService projection = TestData.readyProjection(); WebServer server = server(projection)) {
            server.start();
            HttpResponse<String> day = get(server, "/reader/diaries/11/2026/09/01");
            HttpResponse<String> fragment = get(server, "/reader/fragments/33");
            HttpResponse<String> chooser = get(server, "/reader/diaries/11?month=2026-09");
            HttpResponse<String> mismatched = get(server, "/reader/diaries/11/2026/07?fragment=33");
            assertThat(day.headers().firstValue("Location"))
                    .contains("/reader/diaries/11/2026/09?fragment=33#fragment-33");
            assertThat(fragment.headers().firstValue("Location"))
                    .contains("/reader/diaries/11/2026/09?fragment=33#fragment-33");
            assertThat(chooser.headers().firstValue("Location")).contains("/reader/diaries/11/2026/09");
            assertThat(mismatched.headers().firstValue("Location"))
                    .contains("/reader/diaries/11/2026/09?fragment=33#fragment-33");
        }
    }

    @Test
    void implementsHeadConditionalGetControlledErrorsAndMutationRejection() throws Exception {
        try (ProjectionService projection = TestData.readyProjection(); WebServer server = server(projection)) {
            server.start();
            HttpResponse<String> get = get(server, "/reader/diaries/11/2026/09");
            HttpResponse<String> head = send(server, "/reader/diaries/11/2026/09", "HEAD", null, null);
            HttpResponse<String> conditional = send(server, "/reader/diaries/11/2026/09", "GET",
                    "If-None-Match", get.headers().firstValue("ETag").orElseThrow());
            HttpResponse<String> post = send(server, "/reader/diaries/11/2026/09", "POST", null, null);
            HttpResponse<String> missing = get(server, "/reader/diaries/11/2025/01");
            HttpResponse<String> invalid = get(server, "/reader/diaries/11/2026/not-a-month");
            HttpResponse<String> unknownFragment = get(server, "/reader/diaries/11/2026/09?fragment=999");
            assertThat(head.statusCode()).isEqualTo(200);
            assertThat(head.body()).isEmpty();
            assertThat(conditional.statusCode()).isEqualTo(304);
            assertThat(post.statusCode()).isEqualTo(405);
            assertThat(post.headers().firstValue("Allow")).contains("GET, HEAD");
            assertThat(missing.statusCode()).isEqualTo(404);
            assertThat(invalid.statusCode()).isEqualTo(404);
            assertThat(unknownFragment.statusCode()).isEqualTo(404);
            assertThat(invalid.body()).doesNotContain("Exception", "invalid diary month");
        }
    }

    private static void assertAppearsInOrder(String text, String... needles) {
        int previous = -1;
        for (String needle : needles) {
            int current = text.indexOf(needle, previous + 1);
            assertThat(current).as("expected %s after index %s", needle, previous).isGreaterThan(previous);
            previous = current;
        }
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private WebServer server(ProjectionService projection) {
        return new WebServer(TestData.config("/reader"), projection,
                new BuildInfo("diaries-web", "test", "build", "date", "commit", "branch", "url"));
    }

    private HttpResponse<String> get(WebServer server, String path) throws Exception {
        return send(server, path, "GET", null, null);
    }

    private HttpResponse<String> send(WebServer server, String path, String method,
            String header, String value) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + server.port() + path)).timeout(Duration.ofSeconds(5));
        if (header != null) request.header(header, value);
        request.method(method, HttpRequest.BodyPublishers.noBody());
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
