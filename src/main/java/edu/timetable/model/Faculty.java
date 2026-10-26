package edu.timetable.model;

import java.util.Set;
import java.util.TreeSet;

/**
 * A teacher. {@code unavailable} lists the slots in which the faculty member cannot teach
 * (a hard constraint).
 */
public record Faculty(int id, String name, Set<TimeSlot> unavailable) {
    public Faculty {
        unavailable = Set.copyOf(unavailable == null ? Set.of() : unavailable);
    }

    public Faculty(int id, String name) {
        this(id, name, Set.of());
    }

    public Faculty withId(int newId) {
        return new Faculty(newId, name, unavailable);
    }

    public Set<TimeSlot> sortedUnavailable() {
        return new TreeSet<>(unavailable);
    }

    @Override
    public String toString() {
        return name;
    }
}
