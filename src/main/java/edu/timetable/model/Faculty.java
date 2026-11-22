package edu.timetable.model;

import java.util.Set;
import java.util.TreeSet;

/**
 * A teacher. {@code unavailable} lists the slots in which the faculty member cannot teach, and
 * {@code maxPerDay} caps the periods they teach on any one day (0 means no limit). Both are hard
 * constraints.
 */
public record Faculty(int id, String name, Set<TimeSlot> unavailable, int maxPerDay) {
    public Faculty {
        unavailable = Set.copyOf(unavailable == null ? Set.of() : unavailable);
        if (maxPerDay < 0) throw new IllegalArgumentException("maxPerDay must be 0 (no limit) or positive");
    }

    public Faculty(int id, String name, Set<TimeSlot> unavailable) {
        this(id, name, unavailable, 0);
    }

    public Faculty(int id, String name) {
        this(id, name, Set.of(), 0);
    }

    public boolean hasDailyLimit() {
        return maxPerDay > 0;
    }

    public Faculty withId(int newId) {
        return new Faculty(newId, name, unavailable, maxPerDay);
    }

    public Set<TimeSlot> sortedUnavailable() {
        return new TreeSet<>(unavailable);
    }

    @Override
    public String toString() {
        return name;
    }
}
