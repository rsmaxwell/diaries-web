package com.rsmaxwell.diaries.web.projection;

public record RelationshipDiagnostics(
        int pagesWithoutDiary,
        int marqueesWithoutPage,
        int marqueesWithoutFragment,
        int fragmentsWithoutPageId,
        int fragmentsWithMissingPage,
        int fragmentPagesWithoutDiary,
        int marqueeFragmentsWithoutMarquee,
        int inconsistentFragmentMarqueeLinks,
        int inconsistentFragmentMarqueePages,
        int unsupportedImageFragments,
        int legacyTypeFallbacks) {
}
