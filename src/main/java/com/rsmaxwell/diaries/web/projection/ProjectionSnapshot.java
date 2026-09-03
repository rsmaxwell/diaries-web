package com.rsmaxwell.diaries.web.projection;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

import com.rsmaxwell.diaries.web.model.DiaryItem;
import com.rsmaxwell.diaries.web.model.FragmentItem;
import com.rsmaxwell.diaries.web.model.MarqueeItem;
import com.rsmaxwell.diaries.web.model.PageItem;

public final class ProjectionSnapshot {
    public static final Comparator<DiaryItem> DIARY_ORDER = Comparator
            .comparing(DiaryItem::sequence, BigDecimal::compareTo)
            .thenComparingLong(DiaryItem::id);
    public static final Comparator<PageItem> PAGE_ORDER = Comparator
            .comparing(PageItem::sequence, BigDecimal::compareTo)
            .thenComparingLong(PageItem::id);
    public static final Comparator<FragmentItem> FRAGMENT_ORDER = Comparator
            .comparing(FragmentItem::sequence, BigDecimal::compareTo)
            .thenComparingLong(FragmentItem::id);
    public static final Comparator<FragmentItem> SOURCE_PAGE_FRAGMENT_ORDER = Comparator
            .comparing(FragmentItem::date)
            .thenComparing(FRAGMENT_ORDER);

    private final long generation;
    private final Instant createdAt;
    private final Map<Long, DiaryItem> diariesById;
    private final Map<Long, PageItem> pagesById;
    private final Map<Long, FragmentItem> fragmentsById;
    private final Map<Long, MarqueeItem> marqueesById;
    private final Map<Long, List<PageItem>> pagesByDiaryId;
    private final Map<Long, List<MarqueeItem>> marqueesByPageId;
    private final Map<Long, FragmentItem> fragmentByMarqueeId;
    private final Map<DiaryDayKey, List<FragmentItem>> fragmentsByDiaryAndDate;
    private final Map<Long, List<LocalDate>> datesByDiaryId;
    private final Map<DiaryMonthKey, List<ResolvedFragment>> fragmentsByDiaryAndMonth;
    private final Map<Long, List<YearMonth>> monthsByDiaryId;
    private final Map<Long, ResolvedFragment> resolvedFragmentsById;
    private final RelationshipDiagnostics relationshipDiagnostics;
    private final SourceConnectionState sourceConnectionState;

    private ProjectionSnapshot(
            long generation,
            Instant createdAt,
            Map<Long, DiaryItem> diariesById,
            Map<Long, PageItem> pagesById,
            Map<Long, FragmentItem> fragmentsById,
            Map<Long, MarqueeItem> marqueesById,
            Map<Long, List<PageItem>> pagesByDiaryId,
            Map<Long, List<MarqueeItem>> marqueesByPageId,
            Map<Long, FragmentItem> fragmentByMarqueeId,
            Map<DiaryDayKey, List<FragmentItem>> fragmentsByDiaryAndDate,
            Map<Long, List<LocalDate>> datesByDiaryId,
            Map<DiaryMonthKey, List<ResolvedFragment>> fragmentsByDiaryAndMonth,
            Map<Long, List<YearMonth>> monthsByDiaryId,
            Map<Long, ResolvedFragment> resolvedFragmentsById,
            RelationshipDiagnostics relationshipDiagnostics,
            SourceConnectionState sourceConnectionState) {
        this.generation = generation;
        this.createdAt = createdAt;
        this.diariesById = Map.copyOf(diariesById);
        this.pagesById = Map.copyOf(pagesById);
        this.fragmentsById = Map.copyOf(fragmentsById);
        this.marqueesById = Map.copyOf(marqueesById);
        this.pagesByDiaryId = copyLists(pagesByDiaryId);
        this.marqueesByPageId = copyLists(marqueesByPageId);
        this.fragmentByMarqueeId = Map.copyOf(fragmentByMarqueeId);
        this.fragmentsByDiaryAndDate = copyLists(fragmentsByDiaryAndDate);
        this.datesByDiaryId = copyLists(datesByDiaryId);
        this.fragmentsByDiaryAndMonth = copyLists(fragmentsByDiaryAndMonth);
        this.monthsByDiaryId = copyLists(monthsByDiaryId);
        this.resolvedFragmentsById = Map.copyOf(resolvedFragmentsById);
        this.relationshipDiagnostics = relationshipDiagnostics;
        this.sourceConnectionState = sourceConnectionState;
    }

    public static ProjectionSnapshot empty() {
        return build(0, new MutableProjectionState(), SourceConnectionState.STARTING);
    }

    static ProjectionSnapshot build(
            long generation,
            MutableProjectionState state,
            SourceConnectionState connectionState) {
        Map<Long, List<PageItem>> pagesByDiary = new HashMap<>();
        int pagesWithoutDiary = 0;
        for (PageItem page : state.pages.values()) {
            if (!state.diaries.containsKey(page.diaryId())) {
                pagesWithoutDiary++;
                continue;
            }
            pagesByDiary.computeIfAbsent(page.diaryId(), ignored -> new ArrayList<>()).add(page);
        }
        pagesByDiary.values().forEach(items -> items.sort(PAGE_ORDER));

        Map<Long, List<MarqueeItem>> marqueesByPage = new HashMap<>();
        int marqueesWithoutPage = 0;
        int marqueesWithoutFragment = 0;
        for (MarqueeItem marquee : state.marquees.values()) {
            if (!state.pages.containsKey(marquee.pageId())) {
                marqueesWithoutPage++;
            }
            if (!state.fragments.containsKey(marquee.fragmentId())) {
                marqueesWithoutFragment++;
            }
            if (state.pages.containsKey(marquee.pageId())) {
                marqueesByPage.computeIfAbsent(marquee.pageId(), ignored -> new ArrayList<>()).add(marquee);
            }
        }
        marqueesByPage.values().forEach(items -> items.sort(Comparator.comparingLong(MarqueeItem::id)));

        Map<Long, FragmentItem> fragmentByMarquee = new HashMap<>();
        Map<Long, ResolvedFragment> resolvedFragments = new HashMap<>();
        Map<DiaryDayKey, List<FragmentItem>> fragmentsByDay = new HashMap<>();
        Map<Long, TreeSet<LocalDate>> mutableDatesByDiary = new HashMap<>();
        Map<DiaryMonthKey, List<ResolvedFragment>> fragmentsByMonth = new HashMap<>();
        Map<Long, TreeSet<YearMonth>> mutableMonthsByDiary = new HashMap<>();
        int fragmentsWithoutMarquee = 0;
        int inconsistentLinks = 0;

        for (FragmentItem fragment : state.fragments.values()) {
            if (fragment.marqueeId() == null) {
                fragmentsWithoutMarquee++;
                continue;
            }
            MarqueeItem marquee = state.marquees.get(fragment.marqueeId());
            if (marquee == null) {
                fragmentsWithoutMarquee++;
                continue;
            }
            if (marquee.fragmentId() != fragment.id()) {
                inconsistentLinks++;
                continue;
            }
            fragmentByMarquee.put(marquee.id(), fragment);
            PageItem page = state.pages.get(marquee.pageId());
            if (page == null) {
                continue;
            }
            DiaryItem diary = state.diaries.get(page.diaryId());
            if (diary == null) {
                continue;
            }
            ResolvedFragment resolved = new ResolvedFragment(fragment, marquee, page, diary);
            resolvedFragments.put(fragment.id(), resolved);
            DiaryDayKey key = new DiaryDayKey(diary.id(), fragment.date());
            fragmentsByDay.computeIfAbsent(key, ignored -> new ArrayList<>()).add(fragment);
            mutableDatesByDiary.computeIfAbsent(diary.id(), ignored -> new TreeSet<>()).add(fragment.date());
            YearMonth month = YearMonth.from(fragment.date());
            fragmentsByMonth.computeIfAbsent(new DiaryMonthKey(diary.id(), month), ignored -> new ArrayList<>())
                    .add(resolved);
            mutableMonthsByDiary.computeIfAbsent(diary.id(), ignored -> new TreeSet<>()).add(month);
        }
        fragmentsByDay.values().forEach(items -> items.sort(FRAGMENT_ORDER));
        fragmentsByMonth.values().forEach(items -> items.sort(
                Comparator.comparing((ResolvedFragment value) -> value.fragment().date())
                        .thenComparing(ResolvedFragment::fragment, FRAGMENT_ORDER)));

        Map<Long, List<LocalDate>> datesByDiary = new HashMap<>();
        mutableDatesByDiary.forEach((id, dates) -> datesByDiary.put(id, List.copyOf(dates)));
        Map<Long, List<YearMonth>> monthsByDiary = new HashMap<>();
        mutableMonthsByDiary.forEach((id, months) -> monthsByDiary.put(id, List.copyOf(months)));

        RelationshipDiagnostics diagnostics = new RelationshipDiagnostics(
                pagesWithoutDiary,
                marqueesWithoutPage,
                marqueesWithoutFragment,
                fragmentsWithoutMarquee,
                inconsistentLinks);

        return new ProjectionSnapshot(
                generation,
                Instant.now(),
                state.diaries,
                state.pages,
                state.fragments,
                state.marquees,
                pagesByDiary,
                marqueesByPage,
                fragmentByMarquee,
                fragmentsByDay,
                datesByDiary,
                fragmentsByMonth,
                monthsByDiary,
                resolvedFragments,
                diagnostics,
                connectionState);
    }

    private static <K, V> Map<K, List<V>> copyLists(Map<K, List<V>> source) {
        Map<K, List<V>> copy = new HashMap<>();
        source.forEach((key, value) -> copy.put(key, List.copyOf(value)));
        return Map.copyOf(copy);
    }

    public long generation() {
        return generation;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Map<Long, DiaryItem> diariesById() {
        return diariesById;
    }

    public Map<Long, PageItem> pagesById() {
        return pagesById;
    }

    public Map<Long, FragmentItem> fragmentsById() {
        return fragmentsById;
    }

    public Map<Long, MarqueeItem> marqueesById() {
        return marqueesById;
    }

    public Map<Long, List<PageItem>> pagesByDiaryId() {
        return pagesByDiaryId;
    }

    public Map<Long, List<MarqueeItem>> marqueesByPageId() {
        return marqueesByPageId;
    }

    public Map<Long, FragmentItem> fragmentByMarqueeId() {
        return fragmentByMarqueeId;
    }

    public Map<DiaryDayKey, List<FragmentItem>> fragmentsByDiaryAndDate() {
        return fragmentsByDiaryAndDate;
    }

    public Map<Long, List<LocalDate>> datesByDiaryId() {
        return datesByDiaryId;
    }

    public RelationshipDiagnostics relationshipDiagnostics() {
        return relationshipDiagnostics;
    }

    public SourceConnectionState sourceConnectionState() {
        return sourceConnectionState;
    }

    public List<DiaryItem> orderedDiaries() {
        return diariesById.values().stream().sorted(DIARY_ORDER).toList();
    }

    public List<PageItem> pagesForDiary(long diaryId) {
        return pagesByDiaryId.getOrDefault(diaryId, List.of());
    }

    public List<LocalDate> datesForDiary(long diaryId) {
        return datesByDiaryId.getOrDefault(diaryId, List.of());
    }

    public List<YearMonth> monthsForDiary(long diaryId) {
        return monthsByDiaryId.getOrDefault(diaryId, List.of());
    }

    public List<ResolvedFragment> fragmentsForMonth(long diaryId, YearMonth month) {
        return fragmentsByDiaryAndMonth.getOrDefault(new DiaryMonthKey(diaryId, month), List.of());
    }

    public List<FragmentItem> fragmentsForDay(long diaryId, LocalDate date) {
        return fragmentsByDiaryAndDate.getOrDefault(new DiaryDayKey(diaryId, date), List.of());
    }

    public List<ResolvedFragment> fragmentsForPage(long pageId) {
        return resolvedFragmentsById.values().stream()
                .filter(value -> value.page().id() == pageId)
                .sorted(Comparator.comparing(ResolvedFragment::fragment, SOURCE_PAGE_FRAGMENT_ORDER))
                .toList();
    }

    public Optional<ResolvedFragment> resolveFragment(long fragmentId) {
        return Optional.ofNullable(resolvedFragmentsById.get(fragmentId));
    }
}
