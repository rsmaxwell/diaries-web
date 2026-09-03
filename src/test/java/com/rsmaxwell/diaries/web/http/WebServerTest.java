package com.rsmaxwell.diaries.web.http;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.rsmaxwell.diaries.web.TestData;
import com.rsmaxwell.diaries.web.buildinfo.BuildInfo;
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
            assertThat(content.statusCode()).isEqualTo(503);
            assertThat(content.headers().firstValue("Retry-After")).contains("3");
            assertThat(content.body()).contains("projection is synchronising");
            assertThat(content.body()).doesNotContain("broker", "password", "mqtt.host");
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
            assertThat(month.body()).contains("September 2026", "data-month-reader",
                    "data-viewer-svg", "data-viewer-marquee", "data-reader-fragment=\"33\"",
                    "data-reader-fragment=\"34\"", "Tuesday 1",
                    "Wednesday 2", "Fit page", "Fit selection",
                    "Next fragment", "https://content.example.test/diaries/Family%20diary/page%20001.jpg");
            assertThat(month.body()).containsPattern(
                    "(?s)<div class=\"fragment__content\" data-fragment-selector=\"33\" role=\"button\" tabindex=\"0\".*?A diary entry");
            assertThat(month.body()).doesNotContain("<script>", "alert(1)",
                    ">Transcription</h2>", "Tuesday, 1 September 2026", "Wednesday, 2 September 2026",
                    "Selected source", "Original source: page 001", "fragment__source",
                    ">Month reader</p>", "2 published fragments");
            assertThat(selected.statusCode()).isEqualTo(200);
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
                    ".fragment__content[data-fragment-selector]",
                    ".month-heading h1 { font-size: clamp(1.5rem, 2.5vw, 2.15rem)",
                    ".source-page-heading--untranscribed h1 { font-size: clamp(1.5rem, 2.5vw, 2.15rem)");
            assertThat(javascript.body()).contains("initialiseMonthReader", "zoomAt", "window.history.pushState",
                    "window.history.replaceState", "pointermove", "ResizeObserver", "keydown", "aria-pressed");
            assertThat(index.headers().firstValue("Content-Security-Policy"))
                    .hasValueSatisfying(value -> assertThat(value).contains("default-src 'none'", "form-action 'self'"));
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
