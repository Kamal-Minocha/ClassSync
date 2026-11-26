package com.classsync.service;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Streak and weekly-activity arithmetic (no database). */
class StreaksTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 25);   // a Wednesday

    private static LocalDate d(int daysAgo) {
        return TODAY.minusDays(daysAgo);
    }

    @Test
    void currentStreakEndsTodayOrYesterday() {
        assertEquals(0, Streaks.current(List.of(), TODAY));
        assertEquals(3, Streaks.current(List.of(d(0), d(1), d(2)), TODAY));
        assertEquals(2, Streaks.current(List.of(d(1), d(2)), TODAY), "not broken yet this morning");
        assertEquals(0, Streaks.current(List.of(d(2), d(3)), TODAY), "a missed day breaks it");
        assertEquals(1, Streaks.current(List.of(d(0), d(2), d(3)), TODAY));
    }

    @Test
    void duplicatesDoNotInflateStreaks() {
        assertEquals(2, Streaks.current(List.of(d(0), d(0), d(0), d(1)), TODAY), "many events on one day = one day");
        assertEquals(2, Streaks.longest(List.of(d(0), d(0), d(1))));
    }

    @Test
    void longestStreakFindsTheBestRun() {
        assertEquals(0, Streaks.longest(List.of()));
        assertEquals(5, Streaks.longest(List.of(d(20), d(19), d(18), d(17), d(16), d(3), d(2))));
        assertEquals(1, Streaks.longest(List.of(d(9), d(5), d(1))));
    }

    @Test
    void weeklyBucketsStartOnMondayAndIncludeEmptyWeeks() {
        Map<LocalDate, Integer> w = Streaks.weekly(List.of(d(0), d(1), d(2), d(9), d(60)), TODAY, 4);
        assertEquals(4, w.size());
        assertEquals(List.of(0, 0, 1, 3), List.copyOf(w.values()), "Mon 23 Nov week has 3 events; 60 days ago is outside");
        assertEquals(LocalDate.of(2026, 11, 23), w.keySet().stream().reduce((a, b) -> b).orElseThrow());
    }
}
