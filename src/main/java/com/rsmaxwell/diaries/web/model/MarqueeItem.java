package com.rsmaxwell.diaries.web.model;

public record MarqueeItem(
        long id,
        long version,
        long pageId,
        long fragmentId,
        RectangleItem rectangle) {

    public MarqueeItem {
        Validation.positiveId(id, "marquee id");
        Validation.nonNegative(version, "marquee version");
        Validation.positiveId(pageId, "marquee pageId");
        Validation.positiveId(fragmentId, "marquee fragmentId");
        rectangle = Validation.notNull(rectangle, "marquee rectangle");
    }
}
