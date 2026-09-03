package com.rsmaxwell.diaries.web.projection;

import com.rsmaxwell.diaries.web.model.DiaryItem;
import com.rsmaxwell.diaries.web.model.FragmentItem;
import com.rsmaxwell.diaries.web.model.MarqueeItem;
import com.rsmaxwell.diaries.web.model.PageItem;
import com.rsmaxwell.diaries.web.mqtt.EntityType;

public sealed interface ProjectionEvent permits ProjectionEvent.UpsertDiary,
        ProjectionEvent.UpsertPage, ProjectionEvent.UpsertFragment,
        ProjectionEvent.UpsertMarquee, ProjectionEvent.Tombstone {

    record UpsertDiary(DiaryItem value) implements ProjectionEvent {
    }

    record UpsertPage(PageItem value) implements ProjectionEvent {
    }

    record UpsertFragment(FragmentItem value) implements ProjectionEvent {
    }

    record UpsertMarquee(MarqueeItem value) implements ProjectionEvent {
    }

    record Tombstone(EntityType type, long id) implements ProjectionEvent {
    }
}
