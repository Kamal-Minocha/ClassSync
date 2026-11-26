package com.classsync.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;

/**
 * Learning-streak arithmetic on calendar days in the ACADEMIC time zone (no database, unit-tested).
 *
 * A day "counts" when the student did at least one meaningful thing that day (see ProgressService).
 * Current streak: consecutive counted days ending today — or ending yesterday, so the streak is not shown
 * as broken in the morning before the student has done anything today.
 */
public final class Streaks {

    private Streaks() {
    }

    public static int current(Collection<LocalDate> days, LocalDate today) {
        NavigableSet<LocalDate> set = new TreeSet<>(days);
        LocalDate d = set.contains(today) ? today : today.minusDays(1);
        int n = 0;
        while (set.contains(d)) {
            n++;
            d = d.minusDays(1);
        }
        return n;
    }

    public static int longest(Collection<LocalDate> days) {
        int best = 0;
        int run = 0;
        LocalDate previous = null;
        for (LocalDate d : new TreeSet<>(days)) {
            run = previous != null && previous.plusDays(1).equals(d) ? run + 1 : 1;
            best = Math.max(best, run);
            previous = d;
        }
        return best;
    }

    /** Activity count per week (Monday start) for the `weeks` most recent weeks, oldest first, zeros included. */
    public static Map<LocalDate, Integer> weekly(Collection<LocalDate> eventDays, LocalDate today, int weeks) {
        LocalDate thisMonday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        Map<LocalDate, Integer> map = new LinkedHashMap<>();
        for (int i = weeks - 1; i >= 0; i--) {
            map.put(thisMonday.minusWeeks(i), 0);
        }
        for (LocalDate d : eventDays) {
            LocalDate monday = d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            map.computeIfPresent(monday, (k, v) -> v + 1);
        }
        return map;
    }
}
