package com.rsmaxwell.diaries.web.http;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rsmaxwell.diaries.web.buildinfo.BuildInfo;
import com.rsmaxwell.diaries.web.config.AppConfig;
import com.rsmaxwell.diaries.web.model.DiaryItem;
import com.rsmaxwell.diaries.web.model.FragmentItem;
import com.rsmaxwell.diaries.web.model.PageItem;
import com.rsmaxwell.diaries.web.model.RectangleItem;
import com.rsmaxwell.diaries.web.projection.ProjectionService;
import com.rsmaxwell.diaries.web.projection.ProjectionSnapshot;
import com.rsmaxwell.diaries.web.projection.ProjectionStatus;
import com.rsmaxwell.diaries.web.projection.ResolvedFragment;
import com.rsmaxwell.diaries.web.rendering.FragmentHtmlSanitizer;
import com.rsmaxwell.diaries.web.rendering.ImageUrlBuilder;
import com.rsmaxwell.diaries.web.rendering.PebbleRenderer;

import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;

public final class WebServer implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(WebServer.class);

    private final AppConfig config;
    private final ProjectionService projection;
    private final BuildInfo buildInfo;
    private final FragmentHtmlSanitizer sanitizer;
    private final ImageUrlBuilder imageUrlBuilder;
    private final PebbleRenderer renderer;
    private final SiteUrls urls;
    private final DateTimeFormatter dateFormatter;
    private final DateTimeFormatter dayHeadingFormatter;
    private final String representationVersion;
    private final Javalin app;

    public WebServer(AppConfig config, ProjectionService projection, BuildInfo buildInfo) {
        this.config = config;
        this.projection = projection;
        this.buildInfo = buildInfo;
        sanitizer = new FragmentHtmlSanitizer();
        imageUrlBuilder = new ImageUrlBuilder(config.content());
        renderer = new PebbleRenderer();
        urls = new SiteUrls(config.http().basePath());
        dateFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(config.site().locale());
        dayHeadingFormatter = DateTimeFormatter.ofPattern("EEEE d", config.site().locale());
        representationVersion = Integer.toUnsignedString((buildInfo.version() + "|" + buildInfo.buildId()
                + "|" + buildInfo.buildDate() + "|" + buildInfo.gitCommit()).hashCode(), 16);

        String rootPath = urls.path("/");
        String diariesPath = urls.diaries();
        String diaryPath = urls.path("/diaries/{diaryId}");
        String monthPath = urls.path("/diaries/{diaryId}/{year}/{month}");
        String dayPath = urls.path("/diaries/{diaryId}/{year}/{month}/{day}");
        String pagePath = urls.path("/diaries/{diaryId}/pages/{pageId}");
        String fragmentPath = urls.path("/fragments/{fragmentId}");
        String aboutPath = urls.path("/about");
        String livePath = urls.path("/health/live");
        String readyPath = urls.path("/health/ready");
        String cssPath = urls.asset("css/diaries.css");
        String jsPath = urls.asset("js/diaries.js");

        app = Javalin.create(javalin -> {
            javalin.routes.before(this::applyCommonHeaders);
            javalin.routes.get(rootPath, ctx -> renderDiaryIndex(ctx, false));
            javalin.routes.head(rootPath, ctx -> renderDiaryIndex(ctx, true));
            if (!rootPath.endsWith("/") && !rootPath.equals("/")) {
                javalin.routes.get(rootPath + "/", ctx -> redirect(ctx, rootPath));
                javalin.routes.head(rootPath + "/", ctx -> redirect(ctx, rootPath));
            }
            javalin.routes.get(diariesPath, ctx -> renderDiaryIndex(ctx, false));
            javalin.routes.head(diariesPath, ctx -> renderDiaryIndex(ctx, true));
            javalin.routes.get(diaryPath, ctx -> renderDiary(ctx, false));
            javalin.routes.head(diaryPath, ctx -> renderDiary(ctx, true));
            // Register the literal /pages route before the similarly-shaped
            // /{year}/{month} route so "pages" cannot be captured as a year.
            javalin.routes.get(pagePath, ctx -> renderSourcePage(ctx, false));
            javalin.routes.head(pagePath, ctx -> renderSourcePage(ctx, true));
            javalin.routes.get(monthPath, ctx -> renderMonth(ctx, false));
            javalin.routes.head(monthPath, ctx -> renderMonth(ctx, true));
            javalin.routes.get(dayPath, ctx -> redirectDay(ctx, false));
            javalin.routes.head(dayPath, ctx -> redirectDay(ctx, true));
            javalin.routes.get(fragmentPath, ctx -> redirectFragment(ctx, false));
            javalin.routes.head(fragmentPath, ctx -> redirectFragment(ctx, true));
            javalin.routes.get(aboutPath, ctx -> renderAbout(ctx, false));
            javalin.routes.head(aboutPath, ctx -> renderAbout(ctx, true));
            javalin.routes.get(livePath, this::liveness);
            javalin.routes.head(livePath, this::liveness);
            javalin.routes.get(readyPath, this::readiness);
            javalin.routes.head(readyPath, this::readiness);
            javalin.routes.get(cssPath, ctx -> serveAsset(ctx, "/static/css/diaries.css", "text/css; charset=utf-8"));
            javalin.routes.head(cssPath, ctx -> serveAssetHead(ctx, "text/css; charset=utf-8"));
            javalin.routes.get(jsPath, ctx -> serveAsset(ctx, "/static/js/diaries.js", "text/javascript; charset=utf-8"));
            javalin.routes.head(jsPath, ctx -> serveAssetHead(ctx, "text/javascript; charset=utf-8"));

            for (String path : List.of(rootPath, diariesPath, diaryPath, monthPath, dayPath,
                    pagePath, fragmentPath, aboutPath)) {
                javalin.routes.post(path, this::methodNotAllowed);
                javalin.routes.put(path, this::methodNotAllowed);
                javalin.routes.patch(path, this::methodNotAllowed);
                javalin.routes.delete(path, this::methodNotAllowed);
            }
            javalin.routes.error(HttpStatus.NOT_FOUND, ctx -> renderError(ctx, HttpStatus.NOT_FOUND,
                    "Page not found", "The requested diary page could not be found.", null, false));
            javalin.routes.exception(IllegalArgumentException.class, (exception, ctx) -> renderError(ctx,
                    HttpStatus.NOT_FOUND, "Page not found", "The requested diary page could not be found.", null, false));
            javalin.routes.exception(Exception.class, (exception, ctx) -> {
                String requestId = requestId(ctx);
                log.error("Unhandled HTTP rendering error, requestId={}", requestId, exception);
                renderError(ctx, HttpStatus.INTERNAL_SERVER_ERROR, "Unable to render page",
                        "The page could not be rendered. Please try again later.", requestId, false);
            });
        });
    }

    public void start() {
        app.start(config.http().host(), config.http().port());
        log.info("diaries-web HTTP server listening on {}:{}{}", config.http().host(), port(), config.http().basePath());
    }

    public int port() {
        return app.port();
    }

    private void renderDiaryIndex(Context ctx, boolean head) {
        if (!requireReady(ctx, head)) return;
        ProjectionSnapshot snapshot = projection.snapshot();
        List<Map<String, Object>> diaries = snapshot.orderedDiaries().stream()
                .map(diary -> link(diary.name(), urls.diary(diary.id()))).toList();
        Map<String, Object> model = commonModel(config.site().title(), ctx);
        model.put("diaries", diaries);
        model.put("empty", diaries.isEmpty());
        renderSnapshot(ctx, "diary-index.peb", model, snapshot, "diary-index", head);
    }

    private void renderDiary(Context ctx, boolean head) {
        if (!requireReady(ctx, head)) return;
        ProjectionSnapshot snapshot = projection.snapshot();
        long diaryId = positivePathLong(ctx, "diaryId");
        DiaryItem diary = snapshot.diariesById().get(diaryId);
        if (diary == null) {
            renderNotFound(ctx, "Diary not found", head);
            return;
        }
        List<YearMonth> months = snapshot.monthsForDiary(diaryId);
        String requestedMonth = ctx.queryParam("month");
        if (requestedMonth != null) {
            YearMonth month = parseMonth(requestedMonth);
            if (!months.contains(month)) {
                renderNotFound(ctx, "Diary month not found", head);
                return;
            }
            redirect(ctx, urls.month(diaryId, month));
            return;
        }

        List<Map<String, Object>> pages = snapshot.pagesForDiary(diaryId).stream()
                .map(page -> link(page.name(), urls.page(diaryId, page.id()))).toList();
        List<Map<String, Object>> monthViews = months.stream().map(month -> monthLink(diaryId, month)).toList();
        List<DiaryItem> ordered = snapshot.orderedDiaries();
        int index = ordered.indexOf(diary);
        Map<String, Object> model = commonModel(diary.name(), ctx);
        model.put("diary", Map.of("id", diary.id(), "name", diary.name(), "url", urls.diary(diary.id())));
        model.put("months", monthViews);
        model.put("monthOptions", monthViews);
        model.put("pages", pages);
        model.put("hasMonths", !months.isEmpty());
        model.put("hasPages", !pages.isEmpty());
        Map<String, Object> previousDiary = index > 0
                ? link(ordered.get(index - 1).name(), urls.diary(ordered.get(index - 1).id())) : null;
        Map<String, Object> nextDiary = index >= 0 && index + 1 < ordered.size()
                ? link(ordered.get(index + 1).name(), urls.diary(ordered.get(index + 1).id())) : null;
        model.put("previousDiary", previousDiary);
        model.put("nextDiary", nextDiary);
        model.put("hasPreviousDiary", previousDiary != null);
        model.put("hasNextDiary", nextDiary != null);
        renderSnapshot(ctx, "diary.peb", model, snapshot, "diary-" + diaryId, head);
    }

    private void renderMonth(Context ctx, boolean head) {
        if (!requireReady(ctx, head)) return;
        ProjectionSnapshot snapshot = projection.snapshot();
        long diaryId = positivePathLong(ctx, "diaryId");
        DiaryItem diary = snapshot.diariesById().get(diaryId);
        if (diary == null) {
            renderNotFound(ctx, "Diary not found", head);
            return;
        }
        YearMonth month = pathMonth(ctx);
        String canonicalPath = urls.month(diaryId, month);
        if (!canonicalPath.equals(ctx.path())) {
            redirect(ctx, canonicalPath);
            return;
        }
        ResolvedFragment requestedSelection = requestedSelection(ctx, snapshot, diaryId, month, head);
        if (ctx.queryParam("fragment") != null && requestedSelection == null) return;
        List<ResolvedFragment> fragments = snapshot.fragmentsForMonth(diaryId, month);
        if (fragments.isEmpty()) {
            renderNotFound(ctx, "Diary month not found", head);
            return;
        }
        ResolvedFragment selected = requestedSelection == null ? fragments.get(0) : requestedSelection;

        List<Map<String, Object>> fragmentViews = fragments.stream()
                .map(value -> fragmentView(value, month, value.fragment().id() == selected.fragment().id())).toList();
        List<Map<String, Object>> days = groupByDay(fragments, fragmentViews);
        int selectedIndex = fragments.indexOf(selected);
        Map<String, Object> previousFragment = selectedIndex > 0 ? fragmentViews.get(selectedIndex - 1) : null;
        Map<String, Object> nextFragment = selectedIndex + 1 < fragmentViews.size()
                ? fragmentViews.get(selectedIndex + 1) : null;
        List<YearMonth> months = snapshot.monthsForDiary(diaryId);
        int monthIndex = months.indexOf(month);
        Map<String, Object> previousMonth = monthIndex > 0 ? monthLink(diaryId, months.get(monthIndex - 1)) : null;
        Map<String, Object> nextMonth = monthIndex + 1 < months.size() ? monthLink(diaryId, months.get(monthIndex + 1)) : null;

        Map<String, Object> model = commonModel(monthLabel(month) + " – " + diary.name(), ctx);
        model.put("diary", Map.of("id", diary.id(), "name", diary.name(), "url", urls.diary(diary.id())));
        model.put("month", Map.of("label", monthLabel(month), "iso", month.toString()));
        model.put("days", days);
        model.put("viewer", fragmentViews.get(selectedIndex));
        model.put("monthOptions", months.stream().map(value -> {
            Map<String, Object> option = monthLink(diaryId, value);
            option.put("selected", value.equals(month));
            return option;
        }).toList());
        model.put("previousMonth", previousMonth);
        model.put("nextMonth", nextMonth);
        model.put("hasPreviousMonth", previousMonth != null);
        model.put("hasNextMonth", nextMonth != null);
        model.put("previousFragment", previousFragment);
        model.put("nextFragment", nextFragment);
        model.put("hasPreviousFragment", previousFragment != null);
        model.put("hasNextFragment", nextFragment != null);
        renderSnapshot(ctx, "month-reader.peb", model, snapshot,
                "month-" + diaryId + "-" + month + "-fragment-" + selected.fragment().id(), head);
    }

    private List<Map<String, Object>> groupByDay(
            List<ResolvedFragment> fragments, List<Map<String, Object>> fragmentViews) {
        List<Map<String, Object>> days = new ArrayList<>();
        LocalDate currentDate = null;
        Map<String, Object> currentDay = null;
        for (int index = 0; index < fragments.size(); index++) {
            LocalDate date = fragments.get(index).fragment().date();
            if (!date.equals(currentDate)) {
                currentDate = date;
                currentDay = new LinkedHashMap<>();
                currentDay.put("date", dayHeadingFormatter.format(date));
                currentDay.put("iso", date.toString());
                currentDay.put("anchor", "day-" + date);
                currentDay.put("fragments", new ArrayList<Map<String, Object>>());
                days.add(currentDay);
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> dayFragments = (List<Map<String, Object>>) currentDay.get("fragments");
            dayFragments.add(fragmentViews.get(index));
        }
        return days;
    }

    private ResolvedFragment requestedSelection(Context ctx, ProjectionSnapshot snapshot, long diaryId,
            YearMonth month, boolean head) {
        String requested = ctx.queryParam("fragment");
        if (requested == null) return null;
        long fragmentId = positiveLong(requested, "fragment query parameter");
        ResolvedFragment resolved = snapshot.resolveFragment(fragmentId).orElse(null);
        if (resolved == null) {
            renderNotFound(ctx, "Fragment not found", head);
            return null;
        }
        YearMonth fragmentMonth = YearMonth.from(resolved.fragment().date());
        if (resolved.diary().id() != diaryId || !fragmentMonth.equals(month)) {
            redirect(ctx, urls.monthFragment(resolved.diary().id(), fragmentMonth, fragmentId));
            return null;
        }
        return resolved;
    }

    private Map<String, Object> fragmentView(ResolvedFragment resolved, YearMonth month, boolean selected) {
        FragmentItem fragment = resolved.fragment();
        PageItem page = resolved.page();
        RectangleItem rectangle = resolved.marquee().rectangle();
        double x = Math.min(page.width(), Math.max(0, rectangle.x()));
        double y = Math.min(page.height(), Math.max(0, rectangle.y()));
        double right = Math.min(page.width(), rectangle.x() + rectangle.width());
        double bottom = Math.min(page.height(), rectangle.y() + rectangle.height());
        boolean hasMarquee = right > x && bottom > y;
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", fragment.id());
        view.put("anchor", "fragment-" + fragment.id());
        view.put("html", sanitizer.sanitize(fragment.text()));
        view.put("url", urls.monthFragment(resolved.diary().id(), month, fragment.id()));
        view.put("date", dateFormatter.format(fragment.date()));
        view.put("selected", selected);
        view.put("pageId", page.id());
        view.put("pageName", page.name());
        view.put("imageUrl", imageUrlBuilder.pageImageUrl(resolved.diary(), page));
        view.put("pageWidth", page.width());
        view.put("pageHeight", page.height());
        view.put("hasMarquee", hasMarquee);
        view.put("x", hasMarquee ? x : 0);
        view.put("y", hasMarquee ? y : 0);
        view.put("width", hasMarquee ? right - x : 0);
        view.put("height", hasMarquee ? bottom - y : 0);
        return view;
    }

    private void redirectDay(Context ctx, boolean head) {
        if (!requireReady(ctx, head)) return;
        ProjectionSnapshot snapshot = projection.snapshot();
        long diaryId = positivePathLong(ctx, "diaryId");
        if (!snapshot.diariesById().containsKey(diaryId)) {
            renderNotFound(ctx, "Diary not found", head);
            return;
        }
        LocalDate date = pathDate(ctx);
        List<FragmentItem> fragments = snapshot.fragmentsForDay(diaryId, date);
        if (fragments.isEmpty()) {
            renderNotFound(ctx, "Diary day not found", head);
            return;
        }
        long fragmentId = fragments.get(0).id();
        redirect(ctx, urls.monthFragment(diaryId, YearMonth.from(date), fragmentId));
    }

    private void renderSourcePage(Context ctx, boolean head) {
        if (!requireReady(ctx, head)) return;
        ProjectionSnapshot snapshot = projection.snapshot();
        long diaryId = positivePathLong(ctx, "diaryId");
        long pageId = positivePathLong(ctx, "pageId");
        DiaryItem diary = snapshot.diariesById().get(diaryId);
        PageItem page = snapshot.pagesById().get(pageId);
        if (diary == null || page == null || page.diaryId() != diaryId) {
            renderNotFound(ctx, "Source page not found", head);
            return;
        }
        List<Map<String, Object>> fragments = new ArrayList<>();
        for (ResolvedFragment resolved : snapshot.fragmentsForPage(pageId)) {
            FragmentItem fragment = resolved.fragment();
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", fragment.id());
            view.put("anchor", "fragment-" + fragment.id());
            view.put("html", sanitizer.sanitize(fragment.text()));
            view.put("monthUrl", urls.monthFragment(diaryId, YearMonth.from(fragment.date()), fragment.id()));
            view.put("date", dateFormatter.format(fragment.date()));
            view.put("x", resolved.marquee().rectangle().x());
            view.put("y", resolved.marquee().rectangle().y());
            view.put("width", resolved.marquee().rectangle().width());
            view.put("height", resolved.marquee().rectangle().height());
            fragments.add(view);
        }
        Map<String, Object> model = commonModel(page.name() + " – " + diary.name(), ctx);
        model.put("diary", Map.of("name", diary.name(), "url", urls.diary(diaryId)));
        model.put("page", Map.of("name", page.name(), "width", page.width(), "height", page.height(),
                "imageUrl", imageUrlBuilder.pageImageUrl(diary, page)));
        model.put("fragments", fragments);
        model.put("hasFragments", !fragments.isEmpty());
        renderSnapshot(ctx, "source-page.peb", model, snapshot, "page-" + pageId, head);
    }

    private void redirectFragment(Context ctx, boolean head) {
        if (!requireReady(ctx, head)) return;
        ProjectionSnapshot snapshot = projection.snapshot();
        long fragmentId = positivePathLong(ctx, "fragmentId");
        ResolvedFragment resolved = snapshot.resolveFragment(fragmentId).orElse(null);
        if (resolved == null) {
            renderNotFound(ctx, "Fragment not found", head);
            return;
        }
        redirect(ctx, urls.monthFragment(resolved.diary().id(),
                YearMonth.from(resolved.fragment().date()), fragmentId));
    }

    private void renderAbout(Context ctx, boolean head) {
        Map<String, Object> model = commonModel("About", ctx);
        model.put("build", buildInfo.asMap());
        model.put("projection", statusView(projection.status(), projection.snapshot()));
        renderHtml(ctx, "about.peb", model, HttpStatus.OK, head);
    }

    private void liveness(Context ctx) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", "UP");
        payload.put("service", buildInfo.name());
        payload.put("version", buildInfo.version());
        ctx.status(HttpStatus.OK).json(payload);
    }

    private void readiness(Context ctx) {
        ProjectionStatus status = projection.status();
        ProjectionSnapshot snapshot = projection.snapshot();
        ctx.status(status.ready() ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).json(statusView(status, snapshot));
    }

    private Map<String, Object> statusView(ProjectionStatus status, ProjectionSnapshot snapshot) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", status.ready() ? "UP" : "DOWN");
        payload.put("reason", status.reason());
        payload.put("mqttConnected", status.mqttConnected());
        payload.put("subscriptionsAcknowledged", status.subscriptionsAcknowledged());
        payload.put("generation", snapshot.generation());
        payload.put("diaries", snapshot.diariesById().size());
        payload.put("pages", snapshot.pagesById().size());
        payload.put("fragments", snapshot.fragmentsById().size());
        payload.put("marquees", snapshot.marqueesById().size());
        payload.put("invalidMessages", status.invalidMessageCount());
        payload.put("tombstones", status.tombstoneCount());
        payload.put("lastAcceptedUpdateAt", status.lastAcceptedUpdateAt() == null
                ? null : status.lastAcceptedUpdateAt().toString());
        return payload;
    }

    private boolean requireReady(Context ctx, boolean head) {
        ProjectionStatus status = projection.status();
        if (status.ready()) return true;
        ctx.header("Retry-After", "3");
        Map<String, Object> model = commonModel("Temporarily unavailable", ctx);
        model.put("reason", "The diary projection is synchronising. Please try again shortly.");
        renderHtml(ctx, "unavailable.peb", model, HttpStatus.SERVICE_UNAVAILABLE, head);
        return false;
    }

    private void renderSnapshot(Context ctx, String template, Map<String, Object> model,
            ProjectionSnapshot snapshot, String identity, boolean head) {
        String etag = "\"b" + representationVersion + "-g" + snapshot.generation()
                + "-" + Integer.toUnsignedString(identity.hashCode(), 16) + "\"";
        ctx.header("ETag", etag);
        ctx.header("Cache-Control", "public, max-age=0, must-revalidate");
        if (etag.equals(ctx.header("If-None-Match"))) {
            ctx.status(HttpStatus.NOT_MODIFIED);
            return;
        }
        renderHtml(ctx, template, model, HttpStatus.OK, head);
    }

    private void renderNotFound(Context ctx, String title, boolean head) {
        renderError(ctx, HttpStatus.NOT_FOUND, title, "The requested content is not available.", null, head);
    }

    private void renderError(Context ctx, HttpStatus status, String title, String message,
            String requestId, boolean head) {
        Map<String, Object> model = commonModel(title, ctx);
        model.put("statusCode", status.getCode());
        model.put("message", message);
        model.put("requestId", requestId);
        renderHtml(ctx, "error.peb", model, status, head);
    }

    private void renderHtml(Context ctx, String template, Map<String, Object> model,
            HttpStatus status, boolean head) {
        ctx.status(status).contentType("text/html; charset=utf-8");
        if (!head) ctx.html(renderer.render(template, model));
    }

    private Map<String, Object> commonModel(String pageTitle, Context ctx) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("siteTitle", config.site().title());
        model.put("siteDescription", config.site().description());
        model.put("pageTitle", pageTitle);
        model.put("homeUrl", urls.path("/"));
        model.put("diariesUrl", urls.diaries());
        model.put("aboutUrl", urls.path("/about"));
        model.put("cssUrl", urls.asset("css/diaries.css") + "?v=" + representationVersion);
        model.put("jsUrl", urls.asset("js/diaries.js") + "?v=" + representationVersion);
        model.put("canonicalUrl", config.http().publicBaseUrl() + ctx.path());
        model.put("version", buildInfo.version());
        return model;
    }

    private Map<String, Object> monthLink(long diaryId, YearMonth month) {
        Map<String, Object> item = link(monthLabel(month), urls.month(diaryId, month));
        item.put("value", month.toString());
        return item;
    }

    private String monthLabel(YearMonth month) {
        return month.getMonth().getDisplayName(TextStyle.FULL, config.site().locale()) + " " + month.getYear();
    }

    private static Map<String, Object> link(String label, String url) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("label", label);
        result.put("url", url);
        return result;
    }

    private long positivePathLong(Context ctx, String name) {
        return positiveLong(ctx.pathParam(name), "path parameter " + name);
    }

    private static long positiveLong(String text, String name) {
        try {
            long value = Long.parseLong(text);
            if (value <= 0) throw new IllegalArgumentException();
            return value;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("invalid " + name);
        }
    }

    private YearMonth pathMonth(Context ctx) {
        try {
            return YearMonth.of(Integer.parseInt(ctx.pathParam("year")), Integer.parseInt(ctx.pathParam("month")));
        } catch (NumberFormatException | DateTimeException exception) {
            throw new IllegalArgumentException("invalid diary month", exception);
        }
    }

    private static YearMonth parseMonth(String value) {
        try {
            return YearMonth.parse(value);
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException("invalid diary month", exception);
        }
    }

    private LocalDate pathDate(Context ctx) {
        try {
            return LocalDate.of(Integer.parseInt(ctx.pathParam("year")),
                    Integer.parseInt(ctx.pathParam("month")), Integer.parseInt(ctx.pathParam("day")));
        } catch (NumberFormatException | DateTimeException exception) {
            throw new IllegalArgumentException("invalid diary date", exception);
        }
    }

    private void methodNotAllowed(Context ctx) {
        ctx.header("Allow", "GET, HEAD");
        renderError(ctx, HttpStatus.METHOD_NOT_ALLOWED, "Read-only site",
                "This site accepts only GET and HEAD requests for diary content.", null, false);
    }

    private void applyCommonHeaders(Context ctx) {
        ctx.attribute("requestId", UUID.randomUUID().toString());
        ctx.header("X-Content-Type-Options", "nosniff");
        ctx.header("Referrer-Policy", "strict-origin-when-cross-origin");
        ctx.header("X-Frame-Options", "DENY");
        ctx.header("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        ctx.header("Content-Security-Policy", "default-src 'none'; style-src 'self'; script-src 'self'; img-src "
                + imageCspSource()
                + " data:; connect-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'");
    }

    private String imageCspSource() {
        String configured = config.content().publicResponderBaseUrl();
        if (configured.startsWith("/")) return "'self'";
        URI uri = URI.create(configured);
        return uri.getScheme() + "://" + uri.getAuthority();
    }

    private String requestId(Context ctx) {
        String value = ctx.attribute("requestId");
        return value == null ? UUID.randomUUID().toString() : value;
    }

    private void serveAsset(Context ctx, String resource, String contentType) throws IOException {
        try (InputStream input = WebServer.class.getResourceAsStream(resource)) {
            if (input == null) {
                ctx.status(HttpStatus.NOT_FOUND);
                return;
            }
            ctx.contentType(contentType);
            ctx.header("Cache-Control", "public, max-age=3600");
            ctx.result(input.readAllBytes());
        }
    }

    private void serveAssetHead(Context ctx, String contentType) {
        ctx.status(HttpStatus.OK).contentType(contentType);
        ctx.header("Cache-Control", "public, max-age=3600");
    }

    private static void redirect(Context ctx, String location) {
        ctx.status(HttpStatus.FOUND).header("Location", location);
    }

    @Override
    public void close() {
        app.stop();
    }
}
