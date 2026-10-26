package edu.timetable.model;

import java.util.List;

/**
 * A course taught by one faculty member to one or more batches. A course with several batches
 * is a combined class (a common lecture or a shared elective): all of them attend every meeting.
 * The course is decomposed into {@code meetingsPerWeek} single-period meetings that the solver
 * places independently.
 */
public record Course(int id, String code, String title, int facultyId, List<Integer> batchIds,
                     int meetingsPerWeek, RoomType roomType) {
    public Course {
        batchIds = batchIds.stream().distinct().sorted().toList();
        if (batchIds.isEmpty()) throw new IllegalArgumentException("Course " + code + " needs at least one batch");
    }

    /** A course for a single batch. */
    public Course(int id, String code, String title, int facultyId, int batchId, int meetingsPerWeek, RoomType roomType) {
        this(id, code, title, facultyId, List.of(batchId), meetingsPerWeek, roomType);
    }

    public boolean isCombined() {
        return batchIds.size() > 1;
    }

    public Course withId(int newId) {
        return new Course(newId, code, title, facultyId, batchIds, meetingsPerWeek, roomType);
    }

    @Override
    public String toString() {
        return code + " - " + title;
    }
}
