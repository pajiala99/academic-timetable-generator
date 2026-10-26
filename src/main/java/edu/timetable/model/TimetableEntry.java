package edu.timetable.model;

/** One placed meeting: the {@code meetingIndex}-th weekly meeting of a course, in a slot and room. */
public record TimetableEntry(int courseId, int meetingIndex, int day, int period, int roomId) {
}
