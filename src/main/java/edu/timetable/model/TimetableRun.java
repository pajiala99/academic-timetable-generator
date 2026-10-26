package edu.timetable.model;

import java.time.LocalDateTime;
import java.util.List;

/** A generated timetable as persisted: summary metadata plus its entries. */
public record TimetableRun(int id, LocalDateTime createdAt, String label, double softCost,
                           String notes, List<TimetableEntry> entries) {
    public TimetableRun {
        entries = List.copyOf(entries);
    }

    @Override
    public String toString() {
        return "#" + id + "  " + label + "  (cost " + String.format("%.1f", softCost) + ")";
    }
}
