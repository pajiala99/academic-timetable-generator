package edu.timetable.model;

/** A group of students who attend all their classes together, e.g. "CSE-3A". */
public record Batch(int id, String name, int studentCount) {
    public Batch withId(int newId) {
        return new Batch(newId, name, studentCount);
    }

    @Override
    public String toString() {
        return name;
    }
}
