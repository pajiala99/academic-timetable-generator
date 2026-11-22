package edu.timetable.model;

import java.util.List;

/**
 * Shape of the weekly grid. {@code lunchAfter} is the number of periods before the lunch break
 * (0 = no break); no session may run through the break, so a double period cannot start in the
 * last period before lunch.
 */
public record SchedulingConfig(int days, int periodsPerDay, int lunchAfter) {
    private static final List<String> DAY_NAMES =
            List.of("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun");

    public SchedulingConfig {
        if (days < 1 || days > 7) throw new IllegalArgumentException("days must be 1..7");
        if (periodsPerDay < 1 || periodsPerDay > 16) throw new IllegalArgumentException("periodsPerDay must be 1..16");
        if (lunchAfter < 0 || lunchAfter >= periodsPerDay)
            throw new IllegalArgumentException("lunchAfter must be 0 (no break) or a period before the last one");
    }

    /** A week with no lunch break. */
    public SchedulingConfig(int days, int periodsPerDay) {
        this(days, periodsPerDay, 0);
    }

    public boolean hasLunchBreak() {
        return lunchAfter > 0;
    }

    /** Would a session of {@code length} periods starting at {@code period} (0-based) run through lunch? */
    public boolean crossesLunch(int period, int length) {
        return lunchAfter > 0 && period < lunchAfter && period + length > lunchAfter;
    }

    /** The most sessions of two or more periods one room can host in a day: they cannot overlap or cross lunch. */
    public int longSessionsPerDay() {
        return hasLunchBreak() ? lunchAfter / 2 + (periodsPerDay - lunchAfter) / 2 : periodsPerDay / 2;
    }

    public int slotCount() {
        return days * periodsPerDay;
    }

    public static String dayName(int day) {
        return DAY_NAMES.get(day);
    }

    public String slotLabel(int slotIndex) {
        TimeSlot t = TimeSlot.fromIndex(slotIndex, periodsPerDay);
        return dayName(t.day()) + " P" + (t.period() + 1);
    }
}
