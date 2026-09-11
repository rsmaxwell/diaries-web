package com.rsmaxwell.diaries.web.projection;

import java.util.Optional;

import com.rsmaxwell.diaries.web.model.DiaryItem;
import com.rsmaxwell.diaries.web.model.FragmentItem;
import com.rsmaxwell.diaries.web.model.MarqueeItem;
import com.rsmaxwell.diaries.web.model.PageItem;

public record ResolvedFragment(
        FragmentItem fragment,
        Optional<MarqueeItem> marquee,
        PageItem page,
        DiaryItem diary) {

    public ResolvedFragment {
        marquee = marquee == null ? Optional.empty() : marquee;
    }
}
