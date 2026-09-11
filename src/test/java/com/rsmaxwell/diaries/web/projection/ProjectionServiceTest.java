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
            assertThat(diagnostics.unsupportedImageFragments()).isEqualTo(1);
            assertThat(diagnostics.legacyTypeFallbacks()).isEqualTo(1);
            assertThat(service.snapshot().resolveFragment(38)).isEmpty();
            assertThat(service.snapshot().resolveFragment(41)).isPresent();
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
            startReplay(service, standardEvents());
            long oldGeneration = service.snapshot().generation();

            service.beginReplay(true).join();
            assertThat(service.status().ready()).isFalse();
            assertThat(service.snapshot().sourceConnectionState()).isEqualTo(SourceConnectionState.REPLAYING);
            service.accept(new ProjectionEvent.UpsertDiary(diary())).join();
            service.subscriptionsAcknowledged().join();
            await().atMost(WAIT).until(() -> service.status().ready());

            assertThat(service.snapshot().generation()).isGreaterThan(oldGeneration);
            assertThat(service.snapshot().pagesById()).isEmpty();
            assertThat(service.snapshot().fragmentsById()).isEmpty();
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
