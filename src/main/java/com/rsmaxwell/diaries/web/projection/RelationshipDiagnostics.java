package com.rsmaxwell.diaries.web.projection;

public record RelationshipDiagnostics(
        int pagesWithoutDiary,
        int marqueesWithoutPage,
        int marqueesWithoutFragment,
        int fragmentsWithoutMarquee,
        int inconsistentFragmentMarqueeLinks) {
}
