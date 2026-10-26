package edu.timetable.service;

import edu.timetable.model.Batch;
import edu.timetable.model.Course;
import edu.timetable.model.Dataset;
import edu.timetable.model.Faculty;
import edu.timetable.model.Room;
import edu.timetable.model.SchedulingConfig;
import edu.timetable.model.TimetableEntry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * A weekly grid of one batch, faculty member or room, built from timetable entries. Shared by
 * the JavaFX view, the CSV export and the command-line printer.
 */
public final class TimetableGrid {

    public enum Perspective {
        BATCH("Batch"), FACULTY("Faculty"), ROOM("Room");

        private final String label;

        Perspective(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** Something a grid can be drawn for. */
    public record Subject(int id, String name) {
        @Override
        public String toString() {
            return name;
        }
    }

    public record Cell(Course course, int meetingIndex, List<Batch> batches, Faculty faculty, Room room) {
        public String batchNames() {
            return batches.stream().map(Batch::name).collect(Collectors.joining("+"));
        }

        /** What the grid's own subject does not already say. */
        public String details(Perspective perspective) {
            return switch (perspective) {
                case BATCH -> room.code() + " | " + faculty.name() + (course.isCombined() ? " | with " + batchNames() : "");
                case FACULTY -> batchNames() + " | " + room.code();
                case ROOM -> batchNames() + " | " + faculty.name();
            };
        }
    }

    private final SchedulingConfig config;
    private final Perspective perspective;
    private final Subject subject;
    private final List<List<Cell>> cells;

    private TimetableGrid(SchedulingConfig config, Perspective perspective, Subject subject) {
        this.config = config;
        this.perspective = perspective;
        this.subject = subject;
        this.cells = new ArrayList<>();
        for (int i = 0; i < config.slotCount(); i++) cells.add(new ArrayList<>());
    }

    public static List<Subject> subjects(Dataset data, Perspective perspective) {
        List<Subject> out = switch (perspective) {
            case BATCH -> data.batches().stream().map(b -> new Subject(b.id(), b.name())).toList();
            case FACULTY -> data.faculty().stream().map(f -> new Subject(f.id(), f.name())).toList();
            case ROOM -> data.rooms().stream().map(r -> new Subject(r.id(), r.code() + " (" + r.building() + ")")).toList();
        };
        return out.stream().sorted(Comparator.comparing(Subject::name)).toList();
    }

    /** Entries that refer to courses or rooms that no longer exist, or fall outside the week, are skipped. */
    public static TimetableGrid build(Dataset data, List<TimetableEntry> entries, Perspective perspective, Subject subject) {
        TimetableGrid grid = new TimetableGrid(data.config(), perspective, subject);
        Map<Integer, Course> courses = data.courseById();
        Map<Integer, Batch> batches = data.batchById();
        Map<Integer, Faculty> faculty = data.facultyById();
        Map<Integer, Room> rooms = data.roomById();
        for (TimetableEntry e : entries) {
            Course c = courses.get(e.courseId());
            Room r = rooms.get(e.roomId());
            if (c == null || r == null) continue;
            if (e.day() >= data.config().days() || e.period() >= data.config().periodsPerDay()) continue;
            List<Batch> attending = c.batchIds().stream().map(batches::get).filter(Objects::nonNull).toList();
            Faculty f = faculty.get(c.facultyId());
            if (attending.isEmpty() || f == null) continue;
            boolean shown = switch (perspective) {
                case BATCH -> c.batchIds().contains(subject.id());
                case FACULTY -> f.id() == subject.id();
                case ROOM -> r.id() == subject.id();
            };
            if (!shown) continue;
            grid.cells.get(e.day() * data.config().periodsPerDay() + e.period()).add(new Cell(c, e.meetingIndex(), attending, f, r));
        }
        return grid;
    }

    public List<Cell> at(int day, int period) {
        return cells.get(day * config.periodsPerDay() + period);
    }

    public SchedulingConfig config() {
        return config;
    }

    public Perspective perspective() {
        return perspective;
    }

    public Subject subject() {
        return subject;
    }

    public int occupiedCells() {
        return (int) cells.stream().filter(c -> !c.isEmpty()).count();
    }

    /** Fixed-width text rendering for terminals. */
    public String render() {
        int width = 16;
        StringBuilder sb = new StringBuilder();
        sb.append(perspective).append(": ").append(subject.name()).append('\n');
        sb.append(pad("", 5));
        for (int p = 0; p < config.periodsPerDay(); p++) sb.append(pad("P" + (p + 1), width));
        sb.append('\n');
        for (int d = 0; d < config.days(); d++) {
            String[] line1 = new String[config.periodsPerDay()];
            String[] line2 = new String[config.periodsPerDay()];
            for (int p = 0; p < config.periodsPerDay(); p++) {
                List<Cell> here = at(d, p);
                if (here.isEmpty()) {
                    line1[p] = "-";
                    line2[p] = "";
                } else {
                    Cell c = here.get(0);
                    line1[p] = c.course().code() + (here.size() > 1 ? " (+" + (here.size() - 1) + ")" : "");
                    line2[p] = switch (perspective) {
                        case BATCH -> c.room().code();
                        case FACULTY -> c.batchNames() + " " + c.room().code();
                        case ROOM -> c.batchNames();
                    };
                }
            }
            sb.append(pad(SchedulingConfig.dayName(d), 5));
            for (String s : line1) sb.append(pad(s, width));
            sb.append('\n').append(pad("", 5));
            for (String s : line2) sb.append(pad(s, width));
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String pad(String s, int width) {
        if (s.length() >= width) return s.substring(0, width - 1) + " ";
        return s + " ".repeat(width - s.length());
    }
}
