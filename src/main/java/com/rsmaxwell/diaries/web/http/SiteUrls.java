package com.rsmaxwell.diaries.web.http;

import java.time.LocalDate;
import java.time.YearMonth;

public final class SiteUrls {
    private final String basePath;

    public SiteUrls(String basePath) {
        this.basePath = basePath;
    }

    public String path(String path) {
        if (path == null || path.isBlank() || "/".equals(path)) {
            return basePath.isEmpty() ? "/" : basePath;
        }
        String normalized = path.startsWith("/") ? path : "/" + path;
        return basePath + normalized;
    }

    public String diaries() {
        return path("/diaries");
    }

    public String diary(long diaryId) {
        return path("/diaries/" + diaryId);
    }

    public String day(long diaryId, LocalDate date) {
        return path(String.format(
                "/diaries/%d/%04d/%02d/%02d",
                diaryId,
                date.getYear(),
                date.getMonthValue(),
                date.getDayOfMonth()));
    }

    public String month(long diaryId, YearMonth month) {
        return path(String.format(
                "/diaries/%d/%04d/%02d",
                diaryId,
                month.getYear(),
                month.getMonthValue()));
    }

    public String monthFragment(long diaryId, YearMonth month, long fragmentId) {
        return month(diaryId, month) + "?fragment=" + fragmentId + "#fragment-" + fragmentId;
    }

    public String page(long diaryId, long pageId) {
        return path("/diaries/" + diaryId + "/pages/" + pageId);
    }

    public String fragment(long fragmentId) {
        return path("/fragments/" + fragmentId);
    }

    public String asset(String relative) {
        return path("/assets/" + relative);
    }
}
