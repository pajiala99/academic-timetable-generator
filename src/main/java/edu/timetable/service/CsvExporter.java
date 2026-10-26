package edu.timetable.service;

import edu.timetable.model.Batch;
import edu.timetable.model.Course;
import edu.timetable.model.Dataset;
import edu.timetable.model.Faculty;
import edu.timetable.model.Room;
import edu.timetable.model.SchedulingConfig;
import edu.timetable.model.TimetableEntry;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Writes timetables as CSV files that open directly in Excel or LibreOffice. */
public final class CsvExporter {
    private CsvExporter() {
    }

    /**
     * One row per meeting and attending batch, sorted by batch, day and period, so filtering
     * on the Batch column gives that batch's complete week. Combined classes therefore appear
     * once per batch, with the other batches listed under "Shared with".
     */
    public static void writeEntries(Path file, Dataset data, List<TimetableEntry> entries) throws IOException {
        Map<Integer, Course> courses = data.courseById();
        Map<Integer, Batch> batches = data.batchById();
        Map<Integer, Faculty> faculty = data.facultyById();
        Map<Integer, Room> rooms = data.roomById();
        List<String[]> rows = new ArrayList<>();
        for (TimetableEntry e : entries) {
            Course c = courses.get(e.courseId());
            Room r = rooms.get(e.roomId());
            if (c == null || r == null) continue;
            Faculty f = faculty.get(c.facultyId());
            List<String> names = c.batchIds().stream().map(batches::get).filter(b -> b != null).map(Batch::name).toList();
            for (String batch : names) {
                String others = names.stream().filter(n -> !n.equals(batch)).collect(Collectors.joining("+"));
                rows.add(new String[]{batch, SchedulingConfig.dayName(e.day()), Integer.toString(e.period() + 1),
                        c.code(), c.title(), f == null ? "?" : f.name(), r.code(), r.building(), c.roomType().name(), others});
            }
        }
        rows.sort(Comparator.<String[], String>comparing(r -> r[0])
                .thenComparing(r -> dayIndex(r[1]))
                .thenComparing(r -> Integer.parseInt(r[2])));
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            line(w, "Batch", "Day", "Period", "Course", "Title", "Faculty", "Room", "Building", "Type", "Shared with");
            for (String[] r : rows) line(w, r);
        }
    }

    /** The grid as shown on screen: one row per day, one column per period. */
    public static void writeGrid(Path file, TimetableGrid grid) throws IOException {
        SchedulingConfig config = grid.config();
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            String[] header = new String[config.periodsPerDay() + 1];
            header[0] = grid.perspective() + ": " + grid.subject().name();
            for (int p = 0; p < config.periodsPerDay(); p++) header[p + 1] = "P" + (p + 1);
            line(w, header);
            for (int d = 0; d < config.days(); d++) {
                String[] row = new String[config.periodsPerDay() + 1];
                row[0] = SchedulingConfig.dayName(d);
                for (int p = 0; p < config.periodsPerDay(); p++) {
                    row[p + 1] = grid.at(d, p).stream()
                            .map(c -> c.course().code() + " (" + c.details(grid.perspective()) + ")")
                            .collect(Collectors.joining(" / "));
                }
                line(w, row);
            }
        }
    }

    private static int dayIndex(String name) {
        for (int d = 0; d < 7; d++) if (SchedulingConfig.dayName(d).equals(name)) return d;
        return 7;
    }

    private static void line(Writer w, String... cells) throws IOException {
        w.write(Arrays.stream(cells).map(CsvExporter::escape).collect(Collectors.joining(",")));
        w.write("\r\n");
    }

    static String escape(String s) {
        if (s == null) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
