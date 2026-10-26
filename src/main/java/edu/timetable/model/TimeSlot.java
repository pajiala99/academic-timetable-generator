package edu.timetable.model;

/** A (day, period) cell of the weekly grid. Both indices are zero-based. */
public record TimeSlot(int day, int period) implements Comparable<TimeSlot> {
    public int index(int periodsPerDay) {
        return day * periodsPerDay + period;
    }

    public static TimeSlot fromIndex(int index, int periodsPerDay) {
        return new TimeSlot(index / periodsPerDay, index % periodsPerDay);
    }

    @Override
    public int compareTo(TimeSlot o) {
        return day != o.day ? Integer.compare(day, o.day) : Integer.compare(period, o.period);
    }
}
