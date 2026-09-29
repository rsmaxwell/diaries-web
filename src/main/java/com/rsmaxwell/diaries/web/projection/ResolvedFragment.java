package com.rsmaxwell.diaries.web.projection;

import java.util.Objects;
import java.util.Optional;

import com.rsmaxwell.diaries.web.model.DiaryItem;
import com.rsmaxwell.diaries.web.model.FragmentItem;
import com.rsmaxwell.diaries.web.model.ImageItem;
import com.rsmaxwell.diaries.web.model.MarqueeItem;
import com.rsmaxwell.diaries.web.model.PageItem;

public record ResolvedFragment(
        FragmentItem fragment,
        Optional<MarqueeItem> marquee,
        Optional<ImageItem> image,
        MediaState mediaState,
        PageItem page,
        DiaryItem diary) {

    public enum MediaState {
        NOT_APPLICABLE,
        NO_SELECTION,
        AVAILABLE,
        MISSING_METADATA,
        INVALID_METADATA,
        UNSUPPORTED_TYPE
    }

    public ResolvedFragment {
        fragment = Objects.requireNonNull(fragment, "fragment");
        marquee = marquee == null ? Optional.empty() : marquee;
        image = image == null ? Optional.empty() : image;
        mediaState = Objects.requireNonNull(mediaState, "mediaState");
        page = Objects.requireNonNull(page, "page");
        diary = Objects.requireNonNull(diary, "diary");
    }

    /**
     * Source-compatible constructor for the pre-Image projection shape.
     */
    public ResolvedFragment(
            FragmentItem fragment,
            Optional<MarqueeItem> marquee,
            PageItem page,
            DiaryItem diary) {
        this(fragment, marquee, Optional.empty(), MediaState.NOT_APPLICABLE, page, diary);
    }
}
