package com.rsmaxwell.diaries.web.projection;

import java.util.HashMap;
import java.util.Map;

import com.rsmaxwell.diaries.web.model.DiaryItem;
import com.rsmaxwell.diaries.web.model.FragmentItem;
import com.rsmaxwell.diaries.web.model.MarqueeItem;
import com.rsmaxwell.diaries.web.model.PageItem;

final class MutableProjectionState {
    final Map<Long, DiaryItem> diaries = new HashMap<>();
    final Map<Long, PageItem> pages = new HashMap<>();
    final Map<Long, FragmentItem> fragments = new HashMap<>();
    final Map<Long, MarqueeItem> marquees = new HashMap<>();

    boolean apply(ProjectionEvent event) {
        return switch (event) {
            case ProjectionEvent.UpsertDiary value -> putChanged(diaries, value.value().id(), value.value());
            case ProjectionEvent.UpsertPage value -> putChanged(pages, value.value().id(), value.value());
            case ProjectionEvent.UpsertFragment value -> putChanged(fragments, value.value().id(), value.value());
            case ProjectionEvent.UpsertMarquee value -> putChanged(marquees, value.value().id(), value.value());
            case ProjectionEvent.Tombstone value -> switch (value.type()) {
                case DIARY -> diaries.remove(value.id()) != null;
                case PAGE -> pages.remove(value.id()) != null;
                case FRAGMENT -> fragments.remove(value.id()) != null;
                case MARQUEE -> marquees.remove(value.id()) != null;
            };
        };
    }

    private static <T> boolean putChanged(Map<Long, T> map, long id, T value) {
        T previous = map.put(id, value);
        return !value.equals(previous);
    }
}
