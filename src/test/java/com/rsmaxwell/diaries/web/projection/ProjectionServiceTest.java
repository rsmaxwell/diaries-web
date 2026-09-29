package com.rsmaxwell.diaries.web.projection;

import static com.rsmaxwell.diaries.web.TestData.diary;
import static com.rsmaxwell.diaries.web.TestData.fragment;
import static com.rsmaxwell.diaries.web.TestData.marquee;
import static com.rsmaxwell.diaries.web.TestData.page;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.rsmaxwell.diaries.web.model.FragmentItem;
import com.rsmaxwell.diaries.web.model.FragmentType;
import com.rsmaxwell.diaries.web.model.ImageItem;
import com.rsmaxwell.diaries.web.model.MarqueeItem;
import com.rsmaxwell.diaries.web.mqtt.EntityType;

class ProjectionServiceTest {
    private static final Duration WAIT = Duration.ofSeconds(3);

    @Test
    void resolvesCompleteRelationshipInEveryArrivalOrder() {
        List<ProjectionEvent> events = List.of(
                new ProjectionEvent.UpsertDiary(diary()),
                new ProjectionEvent.UpsertPage(page()),
                new ProjectionEvent.UpsertFragment(fragment()),
                new ProjectionEvent.UpsertMarquee(marquee()));

        for (List<ProjectionEvent> order : permutations(events)) {
            try (ProjectionService service = service()) {
                startReplay(service, order);

                assertThat(service.snapshot().resolveFragment(33)).isPresent();
                assertThat(service.snapshot().fragmentsForDay(11, fragment().date()))
                        .extracting(FragmentItem::id)
                        .containsExactly(33L);
            }
        }
    }

    @Test
    void sortsNumericallyThenByIdAndPublishesImmutableSnapshots() {
        FragmentItem firstById = new FragmentItem(31, 0, 2026, 9, 1,
                new BigDecimal("2.00"), "one", 22L, FragmentType.MARQUEE, null, 41L);
        FragmentItem lowerSequence = new FragmentItem(32, 0, 2026, 9, 1,
                new BigDecimal("1.9"), "two", 22L, FragmentType.MARQUEE, null, 42L);
        var marquee31 = new com.rsmaxwell.diaries.web.model.MarqueeItem(
                41, 0, 22, 31, marquee().rectangle());
        var marquee32 = new com.rsmaxwell.diaries.web.model.MarqueeItem(
                42, 0, 22, 32, marquee().rectangle());

        try (ProjectionService service = service()) {
            startReplay(service, List.of(
                    new ProjectionEvent.UpsertDiary(diary()),
                    new ProjectionEvent.UpsertPage(page()),
                    new ProjectionEvent.UpsertFragment(firstById),
                    new ProjectionEvent.UpsertFragment(lowerSequence),
                    new ProjectionEvent.UpsertMarquee(marquee31),
                    new ProjectionEvent.UpsertMarquee(marquee32)));
            ProjectionSnapshot captured = service.snapshot();

            assertThat(captured.fragmentsForDay(11, fragment().date()))
                    .extracting(FragmentItem::id)
                    .containsExactly(32L, 31L);
            assertThat(captured.monthsForDiary(11)).containsExactly(YearMonth.of(2026, 9));
            assertThat(captured.fragmentsForMonth(11, YearMonth.of(2026, 9)))
                    .extracting(resolved -> resolved.fragment().id())
                    .containsExactly(32L, 31L);
            assertThatThrownBy(() -> captured.diariesById().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> captured.fragmentsForDay(11, fragment().date()).clear())
                    .isInstanceOf(UnsupportedOperationException.class);

            service.accept(new ProjectionEvent.Tombstone(EntityType.FRAGMENT, 31)).join();
            assertThat(captured.fragmentsById()).containsKey(31L);
            assertThat(service.snapshot().fragmentsById()).doesNotContainKey(31L);
        }
    }

    @Test
    void storesImagesIdempotentlyUpdatesMetadataAndKeepsSnapshotsImmutable() {
        ImageItem first = image(60, 0, "First caption");
        ImageItem updated = image(60, 1, "Updated caption");

        try (ProjectionService service = service()) {
            startReplay(service, List.of(new ProjectionEvent.UpsertImage(first)));
            ProjectionSnapshot captured = service.snapshot();
            long generation = captured.generation();

            assertThat(captured.imageCount()).isEqualTo(1);
            assertThat(captured.imageById(60)).contains(first);

            service.accept(new ProjectionEvent.UpsertImage(first)).join();
            assertThat(service.snapshot().generation()).isEqualTo(generation);

            service.accept(new ProjectionEvent.UpsertImage(updated)).join();
            assertThat(service.snapshot().generation()).isGreaterThan(generation);
            assertThat(service.snapshot().imageById(60)).contains(updated);
            assertThat(captured.imageById(60)).contains(first);

            service.accept(new ProjectionEvent.Tombstone(EntityType.IMAGE, 60)).join();
            assertThat(service.snapshot().imageCount()).isZero();
            assertThat(service.snapshot().imageById(60)).isEmpty();
            assertThat(captured.imageById(60)).contains(first);
            assertThat(service.status().tombstoneCount()).isEqualTo(1);
        }
    }

    @Test
    void imageStorageIsIndependentOfImageFragmentArrivalOrder() {
        ImageItem image = image(60, 0, "Shared image");
        FragmentItem imageFragment = new FragmentItem(53, 0, 2026, 9, 11, BigDecimal.ONE,
                "image fragment", 22L, FragmentType.IMAGE, 60L, null);

        for (List<ProjectionEvent> order : List.of(
                List.<ProjectionEvent>of(new ProjectionEvent.UpsertImage(image),
                        new ProjectionEvent.UpsertFragment(imageFragment)),
                List.<ProjectionEvent>of(new ProjectionEvent.UpsertFragment(imageFragment),
                        new ProjectionEvent.UpsertImage(image)))) {
            try (ProjectionService service = service()) {
                List<ProjectionEvent> events = new ArrayList<>();
                events.add(new ProjectionEvent.UpsertDiary(diary()));
                events.add(new ProjectionEvent.UpsertPage(page()));
                events.addAll(order);
                startReplay(service, events);

                assertThat(service.snapshot().imageCount()).isEqualTo(1);
                assertThat(service.snapshot().imageById(60)).contains(image);
                assertThat(service.snapshot().fragmentsById()).containsEntry(53L, imageFragment);
                assertThat(service.snapshot().resolveFragment(53)).isPresent();
            }
        }
    }

    @Test
    void imageTombstoneBeforeLaterFragmentReferenceDoesNotResurrectMetadata() {
        ImageItem image = image(60, 0, "Removed before reference");
        FragmentItem imageFragment = new FragmentItem(54, 0, 2026, 9, 12, BigDecimal.ONE,
                "later reference", 22L, FragmentType.IMAGE, 60L, null);

        try (ProjectionService service = service()) {
            startReplay(service, List.of(
                    new ProjectionEvent.UpsertDiary(diary()),
                    new ProjectionEvent.UpsertPage(page()),
                    new ProjectionEvent.UpsertImage(image),
                    new ProjectionEvent.Tombstone(EntityType.IMAGE, 60),
                    new ProjectionEvent.UpsertFragment(imageFragment)));

            assertThat(service.snapshot().imageCount()).isZero();
            assertThat(service.snapshot().imageById(60)).isEmpty();
            assertThat(service.snapshot().fragmentsById()).containsEntry(54L, imageFragment);
            assertThat(service.snapshot().resolveFragment(54)).isPresent();
        }
    }

    @Test
    void sortsSourcePageFragmentsByDateThenSequenceThenId() {
        FragmentItem februaryFirstSecond = datedFragment(219, 1830, 2, 1, "2.0", 82);
        FragmentItem februarySecond = datedFragment(226, 1830, 2, 2, "1.0", 89);
        FragmentItem februaryFirstSameSequenceHigherId = datedFragment(227, 1830, 2, 1, "1.0", 90);
        FragmentItem februaryFirstSameSequenceLowerId = datedFragment(218, 1830, 2, 1, "1.0", 81);

        try (ProjectionService service = service()) {
            startReplay(service, List.of(
                    new ProjectionEvent.UpsertDiary(diary()),
                    new ProjectionEvent.UpsertPage(page()),
                    new ProjectionEvent.UpsertFragment(februaryFirstSecond),
                    new ProjectionEvent.UpsertFragment(februarySecond),
                    new ProjectionEvent.UpsertFragment(februaryFirstSameSequenceHigherId),
                    new ProjectionEvent.UpsertFragment(februaryFirstSameSequenceLowerId),
                    new ProjectionEvent.UpsertMarquee(linkedMarquee(82, 219)),
                    new ProjectionEvent.UpsertMarquee(linkedMarquee(89, 226)),
                    new ProjectionEvent.UpsertMarquee(linkedMarquee(90, 227)),
                    new ProjectionEvent.UpsertMarquee(linkedMarquee(81, 218))));

            assertThat(service.snapshot().fragmentsForPage(page().id()))
                    .extracting(resolved -> resolved.fragment().id())
                    .containsExactly(218L, 227L, 219L, 226L);
        }
    }

    @Test
    void tombstonesRemoveDerivedRelationshipsAndReaddingResolvesThem() {
        try (ProjectionService service = service()) {
            startReplay(service, standardEvents());
            service.accept(new ProjectionEvent.Tombstone(EntityType.PAGE, 22)).join();

            assertThat(service.snapshot().fragmentsById()).containsKey(33L);
            assertThat(service.snapshot().resolveFragment(33)).isEmpty();

            service.accept(new ProjectionEvent.UpsertPage(page())).join();
            assertThat(service.snapshot().resolveFragment(33)).isPresent();
        }
    }

    @Test
    void keepsPageOwnedMarqueeFragmentInChronologyWhenMarqueeIsMissing() {
        FragmentItem withoutMarquee = new FragmentItem(
                35, 0, 2026, 9, 3, new BigDecimal("1.0"), "no marquee",
                22L, FragmentType.MARQUEE, null, null);

        try (ProjectionService service = service()) {
            startReplay(service, List.of(
                    new ProjectionEvent.UpsertDiary(diary()),
                    new ProjectionEvent.UpsertPage(page()),
                    new ProjectionEvent.UpsertFragment(withoutMarquee)));

            assertThat(service.snapshot().resolveFragment(35)).isPresent();
            assertThat(service.snapshot().resolveFragment(35).orElseThrow().marquee()).isEmpty();
            assertThat(service.snapshot().fragmentsForDay(11, withoutMarquee.date()))
                    .extracting(FragmentItem::id)
                    .containsExactly(35L);
            assertThat(service.snapshot().relationshipDiagnostics().marqueeFragmentsWithoutMarquee())
                    .isEqualTo(1);
        }
    }

    @Test
    void fragmentPageOwnershipWinsOverMismatchedMarqueePage() {
        FragmentItem fragment = new FragmentItem(
                36, 0, 2026, 9, 4, new BigDecimal("1.0"), "mismatch",
                22L, FragmentType.MARQUEE, null, 46L);
        MarqueeItem wrongPage = new MarqueeItem(46, 0, 23, 36, marquee().rectangle());

        try (ProjectionService service = service()) {
            startReplay(service, List.of(
                    new ProjectionEvent.UpsertDiary(diary()),
                    new ProjectionEvent.UpsertPage(page()),
                    new ProjectionEvent.UpsertPage(
                            new com.rsmaxwell.diaries.web.model.PageItem(
                                    23, 0, 11, "other", BigDecimal.TEN, "jpg", 100, 100)),
                    new ProjectionEvent.UpsertFragment(fragment),
                    new ProjectionEvent.UpsertMarquee(wrongPage)));

            var resolved = service.snapshot().resolveFragment(36).orElseThrow();
            assertThat(resolved.page().id()).isEqualTo(22);
            assertThat(resolved.marquee()).isEmpty();
            assertThat(service.snapshot().relationshipDiagnostics().inconsistentFragmentMarqueePages())
                    .isEqualTo(1);
        }
    }

    @Test
    void discoversMarqueeByFragmentLinkInsteadOfCompatibilityMarqueeId() {
        FragmentItem fragment = new FragmentItem(
                37, 0, 2026, 9, 5, new BigDecimal("1.0"), "stale compatibility id",
                22L, FragmentType.MARQUEE, null, 999L);
        MarqueeItem linked = new MarqueeItem(47, 0, 22, 37, marquee().rectangle());

        try (ProjectionService service = service()) {
            startReplay(service, List.of(
                    new ProjectionEvent.UpsertDiary(diary()),
                    new ProjectionEvent.UpsertPage(page()),
                    new ProjectionEvent.UpsertFragment(fragment),
                    new ProjectionEvent.UpsertMarquee(linked)));

            var resolved = service.snapshot().resolveFragment(37).orElseThrow();
            assertThat(resolved.marquee()).contains(linked);
            assertThat(service.snapshot().fragmentByMarqueeId()).containsEntry(47L, fragment);
        }
    }

    @Test
    void reportsTypedPageOwnershipDiagnosticsWithoutGuessingRelationships() {
        FragmentItem noPageLegacy = new FragmentItem(
                38, 0, 2026, 9, 6, BigDecimal.ONE, "legacy no page",
                null, null, null, null);
        FragmentItem missingPage = new FragmentItem(
                39, 0, 2026, 9, 7, BigDecimal.ONE, "missing page",
                999L, FragmentType.MARQUEE, null, null);
        FragmentItem missingDiary = new FragmentItem(
                40, 0, 2026, 9, 8, BigDecimal.ONE, "missing diary",
                23L, FragmentType.MARQUEE, null, null);
        FragmentItem image = new FragmentItem(
                41, 0, 2026, 9, 9, BigDecimal.ONE, "future image",
                22L, FragmentType.IMAGE, 51L, null);

        try (ProjectionService service = service()) {
            startReplay(service, List.of(
                    new ProjectionEvent.UpsertDiary(diary()),
                    new ProjectionEvent.UpsertPage(page()),
                    new ProjectionEvent.UpsertPage(
                            new com.rsmaxwell.diaries.web.model.PageItem(
                                    23, 0, 99, "orphan", BigDecimal.TEN, "jpg", 100, 100)),
                    new ProjectionEvent.UpsertFragment(noPageLegacy),
                    new ProjectionEvent.UpsertFragment(missingPage),
                    new ProjectionEvent.UpsertFragment(missingDiary),
                    new ProjectionEvent.UpsertFragment(image)));

            var diagnostics = service.snapshot().relationshipDiagnostics();
            assertThat(diagnostics.fragmentsWithoutPageId()).isEqualTo(1);
            assertThat(diagnostics.fragmentsWithMissingPage()).isEqualTo(1);
            assertThat(diagnostics.fragmentPagesWithoutDiary()).isEqualTo(1);
            assertThat(diagnostics.fragmentsWithoutPage()).isEqualTo(2);
            assertThat(diagnostics.imageFragmentsWithoutImage()).isZero();
            assertThat(diagnostics.imagesReferencedButMissing()).isEqualTo(1);
            assertThat(diagnostics.unsupportedImageFragments()).isZero();
            assertThat(diagnostics.legacyTypeFallbacks()).isEqualTo(1);
            assertThat(service.snapshot().resolveFragment(38)).isEmpty();
            var resolvedImage = service.snapshot().resolveFragment(41).orElseThrow();
            assertThat(resolvedImage.image()).isEmpty();
            assertThat(resolvedImage.mediaState()).isEqualTo(ResolvedFragment.MediaState.MISSING_METADATA);
        }
    }

    @Test
    void unknownExplicitFragmentTypeKeepsOtherwiseValidPageOwnedChronology() {
        FragmentItem unknown = new FragmentItem(
                52, 0, 2026, 9, 10, BigDecimal.ONE, "future typed content",
                22L, FragmentType.UNKNOWN, "VIDEO", null, null);

        try (ProjectionService service = service()) {
            startReplay(service, List.of(
                    new ProjectionEvent.UpsertDiary(diary()),
                    new ProjectionEvent.UpsertPage(page()),
                    new ProjectionEvent.UpsertFragment(unknown)));

            assertThat(service.snapshot().resolveFragment(52)).isPresent();
            assertThat(service.snapshot().fragmentsForDay(11, unknown.date()))
                    .extracting(FragmentItem::id)
                    .containsExactly(52L);
            assertThat(service.snapshot().fragmentsForMonth(11, YearMonth.of(2026, 9)))
                    .extracting(resolved -> resolved.fragment().id())
                    .containsExactly(52L);
            assertThat(service.snapshot().fragmentsById().get(52L).rawType()).isEqualTo("VIDEO");
        }
    }

    @Test
    void resolvesMixedFragmentTypesAndReportsTypeSpecificDiagnostics() {
        ImageItem availableImage = image(60, 0, "Available");
        FragmentItem marqueeWithImage = new FragmentItem(
                61, 0, 2026, 9, 13, new BigDecimal("1.0"), "marquee with illegal image",
                22L, FragmentType.MARQUEE, 60L, 71L);
        FragmentItem imageAvailable = new FragmentItem(
                62, 0, 2026, 9, 13, new BigDecimal("2.0"), "image available",
                22L, FragmentType.IMAGE, 60L, 72L);
        FragmentItem imageNoSelection = new FragmentItem(
                63, 0, 2026, 9, 13, new BigDecimal("3.0"), "image no selection",
                22L, FragmentType.IMAGE, null, null);
        FragmentItem imageMissing = new FragmentItem(
                64, 0, 2026, 9, 13, new BigDecimal("4.0"), "image missing",
                22L, FragmentType.IMAGE, 404L, null);
        FragmentItem unknown = new FragmentItem(
                65, 0, 2026, 9, 13, new BigDecimal("5.0"), "unknown",
                22L, FragmentType.UNKNOWN, "VIDEO", 60L, 72L);
        FragmentItem imagePointerOnly = new FragmentItem(
                66, 0, 2026, 9, 13, new BigDecimal("6.0"), "image stale pointer only",
                22L, FragmentType.IMAGE, 60L, 999L);
        MarqueeItem marqueeLink = new MarqueeItem(71, 0, 22, 61, marquee().rectangle());
        MarqueeItem invalidImageLink = new MarqueeItem(72, 0, 22, 62, marquee().rectangle());

        try (ProjectionService service = service()) {
            startReplay(service, List.of(
                    new ProjectionEvent.UpsertDiary(diary()),
                    new ProjectionEvent.UpsertPage(page()),
                    new ProjectionEvent.UpsertImage(availableImage),
                    new ProjectionEvent.UpsertFragment(marqueeWithImage),
                    new ProjectionEvent.UpsertFragment(imageAvailable),
                    new ProjectionEvent.UpsertFragment(imageNoSelection),
                    new ProjectionEvent.UpsertFragment(imageMissing),
                    new ProjectionEvent.UpsertFragment(unknown),
                    new ProjectionEvent.UpsertFragment(imagePointerOnly),
                    new ProjectionEvent.UpsertMarquee(marqueeLink),
                    new ProjectionEvent.UpsertMarquee(invalidImageLink)));

            var marqueeResolved = service.snapshot().resolveFragment(61).orElseThrow();
            assertThat(marqueeResolved.marquee()).contains(marqueeLink);
            assertThat(marqueeResolved.image()).isEmpty();
            assertThat(marqueeResolved.mediaState()).isEqualTo(ResolvedFragment.MediaState.NOT_APPLICABLE);

            var availableResolved = service.snapshot().resolveFragment(62).orElseThrow();
            assertThat(availableResolved.marquee()).isEmpty();
            assertThat(availableResolved.image()).contains(availableImage);
            assertThat(availableResolved.mediaState()).isEqualTo(ResolvedFragment.MediaState.AVAILABLE);

            var noSelectionResolved = service.snapshot().resolveFragment(63).orElseThrow();
            assertThat(noSelectionResolved.image()).isEmpty();
            assertThat(noSelectionResolved.mediaState()).isEqualTo(ResolvedFragment.MediaState.NO_SELECTION);

            var missingResolved = service.snapshot().resolveFragment(64).orElseThrow();
            assertThat(missingResolved.image()).isEmpty();
            assertThat(missingResolved.mediaState()).isEqualTo(ResolvedFragment.MediaState.MISSING_METADATA);

            var unknownResolved = service.snapshot().resolveFragment(65).orElseThrow();
            assertThat(unknownResolved.marquee()).isEmpty();
            assertThat(unknownResolved.image()).isEmpty();
            assertThat(unknownResolved.mediaState()).isEqualTo(ResolvedFragment.MediaState.UNSUPPORTED_TYPE);

            var diagnostics = service.snapshot().relationshipDiagnostics();
            assertThat(diagnostics.fragmentsWithUnknownType()).isEqualTo(1);
            assertThat(diagnostics.marqueeFragmentsWithImage()).isEqualTo(1);
            assertThat(diagnostics.imageFragmentsWithMarquee()).isEqualTo(1);
            assertThat(diagnostics.imageFragmentsWithoutImage()).isEqualTo(1);
            assertThat(diagnostics.imagesReferencedButMissing()).isEqualTo(1);
            assertThat(diagnostics.inconsistentFragmentMarqueeLinks()).isEqualTo(1);
            assertThat(diagnostics.unsupportedImageFragments()).isZero();

            assertThat(service.snapshot().fragmentsForDay(11, marqueeWithImage.date()))
                    .extracting(FragmentItem::id)
                    .containsExactly(61L, 62L, 63L, 64L, 65L, 66L);
        }
    }

    @Test
    void sharedImageUpdatesAndTombstonesRepairEveryImageReferenceWithoutChangingChronology() {
        ImageItem shared = image(60, 0, "Shared");
        ImageItem updated = image(60, 1, "Updated shared");
        FragmentItem first = new FragmentItem(
                76, 0, 2026, 9, 14, new BigDecimal("1.0"), "first",
                22L, FragmentType.IMAGE, 60L, null);
        FragmentItem second = new FragmentItem(
                77, 0, 2026, 9, 14, new BigDecimal("2.0"), "second",
                22L, FragmentType.IMAGE, 60L, null);

        try (ProjectionService service = service()) {
            startReplay(service, List.of(
                    new ProjectionEvent.UpsertDiary(diary()),
                    new ProjectionEvent.UpsertPage(page()),
                    new ProjectionEvent.UpsertFragment(first),
                    new ProjectionEvent.UpsertFragment(second)));

            assertThat(service.snapshot().resolveFragment(76).orElseThrow().mediaState())
                    .isEqualTo(ResolvedFragment.MediaState.MISSING_METADATA);
            assertThat(service.snapshot().resolveFragment(77).orElseThrow().mediaState())
                    .isEqualTo(ResolvedFragment.MediaState.MISSING_METADATA);
            assertThat(service.snapshot().relationshipDiagnostics().imagesReferencedButMissing()).isEqualTo(2);

            service.accept(new ProjectionEvent.UpsertImage(shared)).join();
            var firstResolved = service.snapshot().resolveFragment(76).orElseThrow();
            var secondResolved = service.snapshot().resolveFragment(77).orElseThrow();
            assertThat(firstResolved.image()).contains(shared);
            assertThat(secondResolved.image()).contains(shared);
            assertThat(firstResolved.image().orElseThrow()).isSameAs(secondResolved.image().orElseThrow());
            assertThat(service.snapshot().relationshipDiagnostics().imagesReferencedButMissing()).isZero();

            service.accept(new ProjectionEvent.UpsertImage(updated)).join();
            assertThat(service.snapshot().resolveFragment(76).orElseThrow().image()).contains(updated);
            assertThat(service.snapshot().resolveFragment(77).orElseThrow().image()).contains(updated);

            service.accept(new ProjectionEvent.Tombstone(EntityType.IMAGE, 60)).join();
            assertThat(service.snapshot().resolveFragment(76).orElseThrow().mediaState())
                    .isEqualTo(ResolvedFragment.MediaState.MISSING_METADATA);
            assertThat(service.snapshot().resolveFragment(77).orElseThrow().mediaState())
                    .isEqualTo(ResolvedFragment.MediaState.MISSING_METADATA);
            assertThat(service.snapshot().relationshipDiagnostics().imagesReferencedButMissing()).isEqualTo(2);
            assertThat(service.snapshot().fragmentsForDay(11, first.date()))
                    .extracting(FragmentItem::id)
                    .containsExactly(76L, 77L);
        }
    }

    @Test
    void rejectedImageMetadataIsDistinguishedFromMissingMetadataPerGeneration() {
        FragmentItem referenced = new FragmentItem(
                78, 0, 2026, 9, 14, BigDecimal.ONE, "invalid image metadata",
                22L, FragmentType.IMAGE, 60L, null);
        ImageItem valid = image(60, 0, "Valid before malformed replacement");

        try (ProjectionService service = service()) {
            service.beginReplay(false).join();
            service.accept(new ProjectionEvent.UpsertDiary(diary())).join();
            service.accept(new ProjectionEvent.UpsertPage(page())).join();
            service.accept(new ProjectionEvent.UpsertFragment(referenced)).join();
            service.recordInvalidImage(60).join();
            service.subscriptionsAcknowledged().join();
            await().atMost(WAIT).until(() -> service.status().ready());

            assertThat(service.snapshot().resolveFragment(78).orElseThrow().mediaState())
                    .isEqualTo(ResolvedFragment.MediaState.INVALID_METADATA);
            assertThat(service.snapshot().relationshipDiagnostics().imagesReferencedButInvalid()).isEqualTo(1);
            assertThat(service.snapshot().relationshipDiagnostics().imagesReferencedButMissing()).isZero();
            assertThat(service.status().invalidMessageCount()).isEqualTo(1);

            service.accept(new ProjectionEvent.UpsertImage(valid)).join();
            assertThat(service.snapshot().resolveFragment(78).orElseThrow().mediaState())
                    .isEqualTo(ResolvedFragment.MediaState.AVAILABLE);
            assertThat(service.snapshot().relationshipDiagnostics().imagesReferencedButInvalid()).isZero();

            service.recordInvalidImage(60).join();
            assertThat(service.snapshot().resolveFragment(78).orElseThrow().image()).contains(valid);
            assertThat(service.snapshot().resolveFragment(78).orElseThrow().mediaState())
                    .isEqualTo(ResolvedFragment.MediaState.AVAILABLE);
            assertThat(service.status().invalidMessageCount()).isEqualTo(2);
        }
    }

    @Test
    void laterRelationshipMessagesRepairDiagnosticsWithoutChangingFragmentOwnership() {
        FragmentItem marqueeFragment = new FragmentItem(
                68, 0, 2026, 9, 15, new BigDecimal("1.0"), "marquee repair",
                22L, FragmentType.MARQUEE, null, null);
        FragmentItem imageFragment = new FragmentItem(
                69, 0, 2026, 9, 15, new BigDecimal("2.0"), "image repair",
                22L, FragmentType.IMAGE, 60L, null);
        MarqueeItem repairedMarquee = new MarqueeItem(73, 0, 22, 68, marquee().rectangle());
        MarqueeItem invalidImageMarquee = new MarqueeItem(74, 0, 22, 69, marquee().rectangle());

        try (ProjectionService service = service()) {
            startReplay(service, List.of(
                    new ProjectionEvent.UpsertDiary(diary()),
                    new ProjectionEvent.UpsertPage(page()),
                    new ProjectionEvent.UpsertFragment(marqueeFragment),
                    new ProjectionEvent.UpsertFragment(imageFragment),
                    new ProjectionEvent.UpsertMarquee(invalidImageMarquee)));

            assertThat(service.snapshot().relationshipDiagnostics().marqueeFragmentsWithoutMarquee()).isEqualTo(1);
            assertThat(service.snapshot().relationshipDiagnostics().imageFragmentsWithMarquee()).isEqualTo(1);
            assertThat(service.snapshot().relationshipDiagnostics().imagesReferencedButMissing()).isEqualTo(1);
            assertThat(service.snapshot().resolveFragment(69).orElseThrow().marquee()).isEmpty();

            service.accept(new ProjectionEvent.UpsertMarquee(repairedMarquee)).join();
            service.accept(new ProjectionEvent.UpsertImage(image(60, 0, "Repaired image"))).join();
            service.accept(new ProjectionEvent.Tombstone(EntityType.MARQUEE, 74)).join();

            assertThat(service.snapshot().relationshipDiagnostics().marqueeFragmentsWithoutMarquee()).isZero();
            assertThat(service.snapshot().relationshipDiagnostics().imageFragmentsWithMarquee()).isZero();
            assertThat(service.snapshot().relationshipDiagnostics().imagesReferencedButMissing()).isZero();
            assertThat(service.snapshot().resolveFragment(68).orElseThrow().marquee()).contains(repairedMarquee);
            assertThat(service.snapshot().resolveFragment(69).orElseThrow().mediaState())
                    .isEqualTo(ResolvedFragment.MediaState.AVAILABLE);
            assertThat(service.snapshot().resolveFragment(69).orElseThrow().page().id()).isEqualTo(22L);
        }
    }

    @Test
    void mixedTypesPreserveDateSequenceAndIdOrdering() {
        FragmentItem imageSecond = new FragmentItem(
                72, 0, 2026, 9, 16, new BigDecimal("2.0"), "image second",
                22L, FragmentType.IMAGE, null, null);
        FragmentItem unknownFirstHigherId = new FragmentItem(
                73, 0, 2026, 9, 16, new BigDecimal("1.0"), "unknown first",
                22L, FragmentType.UNKNOWN, "VIDEO", null, null);
        FragmentItem marqueeFirstLowerId = new FragmentItem(
                71, 0, 2026, 9, 16, new BigDecimal("1.0"), "marquee first",
                22L, FragmentType.MARQUEE, null, 75L);
        MarqueeItem linked = new MarqueeItem(75, 0, 22, 71, marquee().rectangle());

        try (ProjectionService service = service()) {
            startReplay(service, List.of(
                    new ProjectionEvent.UpsertDiary(diary()),
                    new ProjectionEvent.UpsertPage(page()),
                    new ProjectionEvent.UpsertFragment(imageSecond),
                    new ProjectionEvent.UpsertFragment(unknownFirstHigherId),
                    new ProjectionEvent.UpsertFragment(marqueeFirstLowerId),
                    new ProjectionEvent.UpsertMarquee(linked)));

            assertThat(service.snapshot().fragmentsForDay(11, imageSecond.date()))
                    .extracting(FragmentItem::id)
                    .containsExactly(71L, 73L, 72L);
            assertThat(service.snapshot().fragmentsForMonth(11, YearMonth.of(2026, 9)))
                    .filteredOn(resolved -> resolved.fragment().date().equals(imageSecond.date()))
                    .extracting(resolved -> resolved.fragment().id())
                    .containsExactly(71L, 73L, 72L);
            assertThat(service.snapshot().fragmentsForPage(22))
                    .filteredOn(resolved -> resolved.fragment().date().equals(imageSecond.date()))
                    .extracting(resolved -> resolved.fragment().id())
                    .containsExactly(71L, 73L, 72L);
        }
    }

    @Test
    void replayReadinessRequiresAcknowledgementAndQuietnessAndEmptyReplayIsValid() {
        try (ProjectionService service = service()) {
            service.beginReplay(false).join();
            assertThat(service.status().ready()).isFalse();
            Thread.sleep(60);
            assertThat(service.status().ready()).isFalse();

            service.subscriptionsAcknowledged().join();
            await().atMost(WAIT).until(() -> service.status().ready());
            assertThat(service.snapshot().diariesById()).isEmpty();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    @Test
    void reconnectUsesEmptyStagingAndRemovesObjectsDeletedOffline() {
        try (ProjectionService service = service()) {
            List<ProjectionEvent> initial = new ArrayList<>(standardEvents());
            initial.add(new ProjectionEvent.UpsertImage(image(60, 0, "old image")));
            startReplay(service, initial);
            ProjectionSnapshot captured = service.snapshot();
            long oldGeneration = captured.generation();

            service.beginReplay(true).join();
            assertThat(service.status().ready()).isFalse();
            assertThat(service.snapshot().sourceConnectionState()).isEqualTo(SourceConnectionState.REPLAYING);
            assertThat(service.snapshot().imageById(60)).isPresent();
            service.accept(new ProjectionEvent.UpsertDiary(diary())).join();
            service.subscriptionsAcknowledged().join();
            await().atMost(WAIT).until(() -> service.status().ready());

            assertThat(service.snapshot().generation()).isGreaterThan(oldGeneration);
            assertThat(service.snapshot().pagesById()).isEmpty();
            assertThat(service.snapshot().fragmentsById()).isEmpty();
            assertThat(service.snapshot().imageCount()).isZero();
            assertThat(service.snapshot().imageById(60)).isEmpty();
            assertThat(captured.imageById(60)).isPresent();
        }
    }

    @Test
    void duplicateLiveDeliveryIsIdempotentAndConcurrentUpdatesAreSerialised() {
        try (ProjectionService service = service()) {
            startReplay(service, standardEvents());
            long generation = service.snapshot().generation();
            service.accept(new ProjectionEvent.UpsertDiary(diary())).join();
            assertThat(service.snapshot().generation()).isEqualTo(generation);

            List<CompletableFuture<Void>> updates = IntStream.range(100, 150)
                    .mapToObj(id -> service.accept(new ProjectionEvent.UpsertDiary(
                            new com.rsmaxwell.diaries.web.model.DiaryItem(
                                    id, 0, "Diary " + id, BigDecimal.valueOf(id)))))
                    .toList();
            CompletableFuture.allOf(updates.toArray(CompletableFuture[]::new)).join();
            assertThat(service.snapshot().diariesById()).hasSize(51);
        }
    }

    private static ImageItem image(long id, long version, String caption) {
        return new ImageItem(
                id, version, "maps/image-" + id + ".png", "image/png", "image-" + id + ".png",
                1600, 900, "ab".repeat(32), caption, "Image " + id);
    }

    private static ProjectionService service() {
        return new ProjectionService(Duration.ofMillis(20), Duration.ofSeconds(2));
    }

    private static FragmentItem datedFragment(
            long id,
            int year,
            int month,
            int day,
            String sequence,
            long marqueeId) {
        return new FragmentItem(
                id, 0, year, month, day, new BigDecimal(sequence), "fragment " + id,
                page().id(), FragmentType.MARQUEE, null, marqueeId);
    }

    private static MarqueeItem linkedMarquee(long id, long fragmentId) {
        return new MarqueeItem(id, 0, page().id(), fragmentId, marquee().rectangle());
    }

    private static void startReplay(ProjectionService service, List<ProjectionEvent> events) {
        service.beginReplay(false).join();
        events.forEach(event -> service.accept(event).join());
        service.subscriptionsAcknowledged().join();
        await().atMost(WAIT).until(() -> service.status().ready());
    }

    private static List<ProjectionEvent> standardEvents() {
        return List.of(
                new ProjectionEvent.UpsertDiary(diary()),
                new ProjectionEvent.UpsertPage(page()),
                new ProjectionEvent.UpsertFragment(fragment()),
                new ProjectionEvent.UpsertMarquee(marquee()));
    }

    private static <T> List<List<T>> permutations(List<T> values) {
        List<List<T>> output = new ArrayList<>();
        permute(new ArrayList<>(values), 0, output);
        return output;
    }

    private static <T> void permute(List<T> values, int index, List<List<T>> output) {
        if (index == values.size()) {
            output.add(List.copyOf(values));
            return;
        }
        for (int candidate = index; candidate < values.size(); candidate++) {
            Collections.swap(values, index, candidate);
            permute(values, index + 1, output);
            Collections.swap(values, index, candidate);
        }
    }
}
