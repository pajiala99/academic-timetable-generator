package edu.timetable.model;

import java.util.List;

/** Shape of the weekly grid. */
public record SchedulingConfig(int days, int periodsPerDay) {
    private static final List<String> DAY_NAMES =
            List.of("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun");

    public SchedulingConfig {
        if (days < 1 || days > 7) throw new IllegalArgumentException("days must be 1..7");
        if (periodsPerDay < 1 || periodsPerDay > 16) throw new IllegalArgumentException("periodsPerDay must be 1..16");
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
