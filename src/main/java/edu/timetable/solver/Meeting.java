package edu.timetable.solver;

import edu.timetable.model.Batch;
import edu.timetable.model.Course;
import edu.timetable.model.Faculty;

import java.util.List;
import java.util.stream.Collectors;

/**
 * One weekly meeting of a course: a vertex of the conflict graph.
 *
 * @param index   dense vertex index inside the {@link Problem}
 * @param ordinal which meeting of the course this is (0-based)
 * @param batches every batch that attends (more than one for a combined class)
 */
public record Meeting(int index, Course course, int ordinal, List<Batch> batches, Faculty faculty) {
    public Meeting {
        batches = List.copyOf(batches);
    }

    public int studentCount() {
        return batches.stream().mapToInt(Batch::studentCount).sum();
    }

    public String batchNames() {
        return batches.stream().map(Batch::name).collect(Collectors.joining("+"));
    }

    public String label() {
        return course.code() + "#" + (ordinal + 1) + " [" + batchNames() + ", " + faculty.name() + "]";
    }
}
