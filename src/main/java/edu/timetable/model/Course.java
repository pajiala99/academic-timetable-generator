package edu.timetable.model;

import java.util.List;

/**
 * A course taught by one faculty member to one or more batches (or lab groups). A course with
 * several batches is a combined class: all of them attend every meeting. The course is
 * decomposed into {@code meetingsPerWeek} meetings, each lasting {@code periodsPerMeeting}
 * consecutive periods on one day in one room (2 for a double-period lab).
 */
public record Course(int id, String code, String title, int facultyId, List<Integer> batchIds,
                     int meetingsPerWeek, int periodsPerMeeting, RoomType roomType) {
    public Course {
        batchIds = batchIds.stream().distinct().sorted().toList();
        if (batchIds.isEmpty()) throw new IllegalArgumentException("Course " + code + " needs at least one batch");
        if (periodsPerMeeting < 1) throw new IllegalArgumentException("Course " + code + " needs at least one period per meeting");
    }

    /** Single-period meetings. */
    public Course(int id, String code, String title, int facultyId, List<Integer> batchIds,
                  int meetingsPerWeek, RoomType roomType) {
        this(id, code, title, facultyId, batchIds, meetingsPerWeek, 1, roomType);
    }

    /** Single-period meetings for a single batch. */
    public Course(int id, String code, String title, int facultyId, int batchId, int meetingsPerWeek, RoomType roomType) {
        this(id, code, title, facultyId, List.of(batchId), meetingsPerWeek, 1, roomType);
    }

    public boolean isCombined() {
        return batchIds.size() > 1;
    }

    /** Teaching periods per week. */
    public int weeklyPeriods() {
        return meetingsPerWeek * periodsPerMeeting;
    }

    public Course withId(int newId) {
        return new Course(newId, code, title, facultyId, batchIds, meetingsPerWeek, periodsPerMeeting, roomType);
    }

    @Override
    public String toString() {
        return code + " - " + title;
    }
}
