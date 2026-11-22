package edu.timetable.model;

/**
 * A group of students who attend classes together, e.g. "CSE-3A". A batch may be split into
 * lab groups: a group names its {@code parentId}, takes its own lab sessions separately, and
 * attends every class of its parent batch. A batch with groups is never in two places at once
 * as a whole, but its groups can be in different labs at the same time.
 *
 * @param parentId the batch this is a lab group of, or null for a top-level batch
 */
public record Batch(int id, String name, int studentCount, Integer parentId) {
    public Batch(int id, String name, int studentCount) {
        this(id, name, studentCount, null);
    }

    public boolean isGroup() {
        return parentId != null;
    }

    public Batch withId(int newId) {
        return new Batch(newId, name, studentCount, parentId);
    }

    @Override
    public String toString() {
        return name;
    }
}
