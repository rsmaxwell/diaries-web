package com.rsmaxwell.diaries.web.projection;

public record RelationshipDiagnostics(
        int pagesWithoutDiary,
        int marqueesWithoutPage,
        int marqueesWithoutFragment,
        int fragmentsWithoutPageId,
        int fragmentsWithMissingPage,
        int fragmentPagesWithoutDiary,
        int fragmentsWithoutPage,
        int fragmentsWithUnknownType,
        int marqueeFragmentsWithoutMarquee,
        int marqueeFragmentsWithImage,
        int imageFragmentsWithMarquee,
        int imageFragmentsWithoutImage,
        int imagesReferencedButMissing,
        int imagesReferencedButInvalid,
        int inconsistentFragmentMarqueeLinks,
        int inconsistentFragmentMarqueePages,
        int unsupportedImageFragments,
        int legacyTypeFallbacks) {

    /**
     * Compatibility field retained for status/health consumers that still expect the
     * pre-Step-6 diagnostic. IMAGE is now a supported projection type, so this value
     * is deliberately always zero. Use the type-specific Image diagnostics instead.
     */
    @Deprecated(forRemoval = false)
    public int unsupportedImageFragments() {
        return unsupportedImageFragments;
    }
}
