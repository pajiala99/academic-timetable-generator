package edu.timetable.model;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Everything the engine needs to build a timetable, independent of where it was loaded from. */
public record Dataset(SchedulingConfig config, List<Room> rooms, List<Faculty> faculty,
                      List<Batch> batches, List<Course> courses) {
    public Dataset {
        rooms = List.copyOf(rooms);
        faculty = List.copyOf(faculty);
        batches = List.copyOf(batches);
        courses = List.copyOf(courses);
    }

    public Map<Integer, Faculty> facultyById() {
        return faculty.stream().collect(Collectors.toMap(Faculty::id, Function.identity()));
    }

    public Map<Integer, Batch> batchById() {
        return batches.stream().collect(Collectors.toMap(Batch::id, Function.identity()));
    }

    public Map<Integer, Room> roomById() {
        return rooms.stream().collect(Collectors.toMap(Room::id, Function.identity()));
    }

    public Map<Integer, Course> courseById() {
        return courses.stream().collect(Collectors.toMap(Course::id, Function.identity()));
    }

    public int totalMeetings() {
        return courses.stream().mapToInt(Course::meetingsPerWeek).sum();
    }
}
