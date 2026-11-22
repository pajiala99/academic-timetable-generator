package edu.timetable.web;

import edu.timetable.dao.DataAccessException;
import edu.timetable.dao.DataStore;
import edu.timetable.model.Batch;
import edu.timetable.model.Course;
import edu.timetable.model.Dataset;
import edu.timetable.model.Faculty;
import edu.timetable.model.Room;
import edu.timetable.model.SchedulingConfig;
import edu.timetable.model.TimeSlot;
import edu.timetable.model.TimetableRun;
import edu.timetable.service.CsvExporter;
import edu.timetable.service.TimetableGrid;
import edu.timetable.service.TimetableService;
import edu.timetable.solver.BacktrackingSolver;
import edu.timetable.solver.SimulatedAnnealer;
import edu.timetable.solver.SoftConstraintEvaluator;
import edu.timetable.solver.TimetableEngine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The JSON API behind the web version. Every route validates its input like the desktop forms do. */
final class Api {
    private static final String[] PALETTE = {
            "#dbeafe", "#dcfce7", "#fef3c7", "#fce7f3", "#e0e7ff", "#ccfbf1",
            "#fee2e2", "#ede9fe", "#ecfccb", "#ffedd5", "#cffafe", "#f5f5f4"};

    private final TimetableService service;
    private final DataStore store;
    private final GenerationJob job = new GenerationJob();

    Api(TimetableService service) {
        this.service = service;
        this.store = service.store();
    }

    Http.Router router() {
        return new Http.Router(this::constraintMessage)
                .on("GET", "/api/summary", r -> summary())
                .on("POST", "/api/sample", r -> {
                    service.replaceWithSampleData();
                    return summary();
                })
                .on("PUT", "/api/settings/week", r -> saveWeek(r.body(Week.class)))

                .on("GET", "/api/rooms", r -> store.rooms().findAll())
                .on("POST", "/api/rooms", r -> store.rooms().insert(validRoom(r.body(Room.class), 0)))
                .on("PUT", "/api/rooms/(\\d+)", r -> update(validRoom(r.body(Room.class), r.id())))
                .on("DELETE", "/api/rooms/(\\d+)", r -> delete("room", r.id()))

                .on("GET", "/api/batches", r -> store.batches().findAll())
                .on("POST", "/api/batches", r -> store.batches().insert(validBatch(r.body(Batch.class), 0)))
                .on("PUT", "/api/batches/(\\d+)", r -> update(validBatch(r.body(Batch.class), r.id())))
                .on("DELETE", "/api/batches/(\\d+)", r -> delete("batch", r.id()))

                .on("GET", "/api/faculty", r -> store.faculty().findAll())
                .on("POST", "/api/faculty", r -> store.faculty().insert(validFaculty(r.body(Faculty.class), 0)))
                .on("PUT", "/api/faculty/(\\d+)", r -> update(validFaculty(r.body(Faculty.class), r.id())))
                .on("DELETE", "/api/faculty/(\\d+)", r -> delete("faculty", r.id()))

                .on("GET", "/api/courses", r -> store.courses().findAll())
                .on("POST", "/api/courses", r -> store.courses().insert(validCourse(r.body(Course.class), 0)))
                .on("PUT", "/api/courses/(\\d+)", r -> update(validCourse(r.body(Course.class), r.id())))
                .on("DELETE", "/api/courses/(\\d+)", r -> delete("course", r.id()))

                .on("POST", "/api/generate", r -> generate(r.body(GenerateRequest.class)))
                .on("GET", "/api/generate", r -> job.status())
                .on("POST", "/api/generate/cancel", r -> {
                    job.cancel();
                    return job.status();
                })

                .on("GET", "/api/runs", r -> store.timetables().findAllSummaries().stream().map(Api::runSummary).toList())
                .on("DELETE", "/api/runs/(\\d+)", r -> {
                    store.timetables().delete(r.id());
                    return null;
                })
                .on("GET", "/api/runs/(\\d+)/grid", r -> grid(r.id(), r.query("view", "BATCH"), r.query("subject", "")))
                .on("GET", "/api/runs/(\\d+)/csv", r -> csv(r.id()));
    }

    // ---- overview and settings ------------------------------------------------------------------

    private Map<String, Object> summary() {
        Dataset data = service.loadDataset();
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("rooms", data.rooms().size());
        counts.put("batches", data.batches().stream().filter(b -> !b.isGroup()).count());
        counts.put("labGroups", data.batches().stream().filter(Batch::isGroup).count());
        counts.put("faculty", data.faculty().size());
        counts.put("courses", data.courses().size());
        counts.put("meetings", data.totalMeetings());
        counts.put("periods", data.courses().stream().mapToInt(Course::weeklyPeriods).sum());
        List<TimetableRun> runs = store.timetables().findAllSummaries();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("app", "SlotWise");
        out.put("database", store.database().describe());
        out.put("week", week(data.config()));
        out.put("counts", counts);
        out.put("latestRun", runs.isEmpty() ? null : runSummary(runs.get(0)));
        out.put("generating", job.status().get("running"));
        return out;
    }

    /** {@code lunchAfter} may be left out: the current break is kept if it still fits. */
    record Week(int days, int periodsPerDay, Integer lunchAfter) {
    }

    private static Map<String, Object> week(SchedulingConfig c) {
        List<String> names = new ArrayList<>();
        for (int d = 0; d < c.days(); d++) names.add(SchedulingConfig.dayName(d));
        return Map.of("days", c.days(), "periodsPerDay", c.periodsPerDay(), "lunchAfter", c.lunchAfter(), "dayNames", names);
    }

    private Map<String, Object> saveWeek(Week w) {
        if (w.days() < 1 || w.days() > 7) throw new Http.ApiError(400, "Days per week must be from 1 to 7.");
        if (w.periodsPerDay() < 1 || w.periodsPerDay() > 12) throw new Http.ApiError(400, "Periods per day must be from 1 to 12.");
        int current = store.settings().loadConfig().lunchAfter();
        int lunch = w.lunchAfter() != null ? w.lunchAfter() : current < w.periodsPerDay() ? current : 0;
        if (lunch < 0 || lunch >= w.periodsPerDay())
            throw new Http.ApiError(400, "Lunch must come after a period before the last one, or be 0 for no break.");
        store.settings().saveConfig(new SchedulingConfig(w.days(), w.periodsPerDay(), lunch));
        return week(store.settings().loadConfig());
    }

    // ---- validation -----------------------------------------------------------------------------

    private static String required(String value, String message) {
        if (value == null || value.isBlank()) throw new Http.ApiError(400, message);
        return value.trim();
    }

    private static void range(int value, int min, int max, String message) {
        if (value < min || value > max) throw new Http.ApiError(400, message);
    }

    private Room validRoom(Room r, int id) {
        String code = required(r.code(), "Enter a room code.");
        String building = required(r.building(), "Enter a building.");
        range(r.capacity(), 1, 10_000, "Capacity must be a whole number from 1 to 10000.");
        if (r.type() == null) throw new Http.ApiError(400, "Choose the room type.");
        return new Room(id, code, building, r.capacity(), r.type());
    }

    private Batch validBatch(Batch b, int id) {
        String name = required(b.name(), "Enter a batch name, e.g. CSE-3A.");
        range(b.studentCount(), 1, 10_000, "Students must be a whole number from 1 to 10000.");
        if (b.parentId() != null) {
            Batch parent = store.batches().findById(b.parentId()).orElseThrow(() -> new Http.ApiError(400, "That batch no longer exists."));
            if (parent.id() == id) throw new Http.ApiError(400, "A batch cannot be a lab group of itself.");
            if (parent.isGroup()) throw new Http.ApiError(400, parent.name() + " is itself a lab group; choose a whole batch.");
            boolean hasGroups = store.batches().findAll().stream().anyMatch(x -> x.isGroup() && x.parentId() == id);
            if (id != 0 && hasGroups) throw new Http.ApiError(400, "This batch has lab groups, so it cannot itself be a lab group.");
        }
        return new Batch(id, name, b.studentCount(), b.parentId());
    }

    private Faculty validFaculty(Faculty f, int id) {
        String name = required(f.name(), "Enter a name.");
        range(f.maxPerDay(), 0, 16, "The daily limit must be from 0 (no limit) to 16 periods.");
        for (TimeSlot t : f.unavailable()) {
            if (t.day() < 0 || t.period() < 0) throw new Http.ApiError(400, "Unavailable slots must be inside the week.");
        }
        return new Faculty(id, name, f.unavailable(), f.maxPerDay());
    }

    private Course validCourse(Course c, int id) {
        String code = required(c.code(), "Enter a course code.");
        String title = required(c.title(), "Enter a title.");
        if (store.faculty().findById(c.facultyId()).isEmpty()) throw new Http.ApiError(400, "Choose who teaches this course.");
        List<Batch> chosen = new ArrayList<>();
        for (int b : c.batchIds()) chosen.add(store.batches().findById(b).orElseThrow(() -> new Http.ApiError(400, "That batch no longer exists.")));
        for (Batch b : chosen) {
            if (b.isGroup() && chosen.stream().anyMatch(x -> x.id() == b.parentId()))
                throw new Http.ApiError(400, b.name() + " is a lab group of a batch you also ticked; tick one or the other.");
        }
        range(c.meetingsPerWeek(), 1, 20, "Meetings per week must be from 1 to 20.");
        range(c.periodsPerMeeting(), 1, 3, "Each meeting lasts 1, 2 or 3 periods.");
        if (c.roomType() == null) throw new Http.ApiError(400, "Choose the room type.");
        return new Course(id, code, title, c.facultyId(), c.batchIds(), c.meetingsPerWeek(), c.periodsPerMeeting(), c.roomType());
    }

    private Object update(Object item) {
        if (item instanceof Room r) store.rooms().update(r);
        else if (item instanceof Batch b) store.batches().update(b);
        else if (item instanceof Faculty f) store.faculty().update(f);
        else if (item instanceof Course c) store.courses().update(c);
        else throw new IllegalStateException("unknown entity " + item);
        return item;
    }

    private Object delete(String entity, int id) {
        try {
            switch (entity) {
                case "room" -> store.rooms().delete(id);
                case "batch" -> store.batches().delete(id);
                case "faculty" -> store.faculty().delete(id);
                case "course" -> store.courses().delete(id);
                default -> throw new IllegalStateException(entity);
            }
            return null;
        } catch (DataAccessException e) {
            if (!e.isConstraintViolation()) throw e;
            throw new Http.ApiError(409, switch (entity) {
                case "batch" -> "This batch still has courses or lab groups. Delete or reassign them first.";
                case "faculty" -> "This person still teaches courses. Reassign or delete those courses first.";
                default -> "It is still in use, so it cannot be deleted.";
            });
        }
    }

    /** A save the database refused: the only unique keys are names and codes. */
    private String constraintMessage(DataAccessException e) {
        return "Something with this name or code already exists.";
    }

    // ---- generation ---------------------------------------------------------------------------

    /** Every field is optional; missing ones take the defaults the desktop app shows. */
    record GenerateRequest(Integer days, Integer periodsPerDay, Integer lunchAfter, Long timeLimitSeconds, Long cliqueSeconds,
                           Long iterations, Long seed, Double idleGap, Double loadSpread, Double sessionSpacing,
                           Double buildingChange, Double roomFit, String label) {
    }

    private Map<String, Object> generate(GenerateRequest g) {
        if (g.days() != null || g.periodsPerDay() != null || g.lunchAfter() != null) {
            SchedulingConfig current = store.settings().loadConfig();
            saveWeek(new Week(g.days() == null ? current.days() : g.days(),
                    g.periodsPerDay() == null ? current.periodsPerDay() : g.periodsPerDay(), g.lunchAfter()));
        }
        long time = or(g.timeLimitSeconds(), 30L), clique = or(g.cliqueSeconds(), 2L), iterations = or(g.iterations(), 1_000_000L);
        long seed = or(g.seed(), 42L);
        if (time < 1 || time > 3600) throw new Http.ApiError(400, "The search time limit must be from 1 to 3600 seconds.");
        if (clique < 0 || clique > 600) throw new Http.ApiError(400, "The clique time limit must be from 0 to 600 seconds.");
        if (iterations < 0 || iterations > 100_000_000) throw new Http.ApiError(400, "Iterations must be from 0 to 100,000,000.");
        double[] w = {or(g.idleGap(), 3.0), or(g.loadSpread(), 1.0), or(g.sessionSpacing(), 4.0), or(g.buildingChange(), 2.0),
                or(g.roomFit(), 1.0)};
        for (double x : w) if (x < 0 || Double.isNaN(x)) throw new Http.ApiError(400, "Weights must be numbers of at least 0.");
        TimetableEngine.Options options = new TimetableEngine.Options(clique * 1000,
                new BacktrackingSolver.Options(time * 1000, 1_000, 1.3, seed),
                new SimulatedAnnealer.Options(iterations, 0, 0, 0.5, seed),
                new SoftConstraintEvaluator.Weights(w[0], w[1], w[2], w[3], w[4]));
        String label = g.label() == null || g.label().isBlank()
                ? "Run " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) : g.label().trim();
        if (!job.start(service, options, label)) throw new Http.ApiError(409, "A timetable is already being generated.");
        return job.status();
    }

    private static <T> T or(T value, T fallback) {
        return value == null ? fallback : value;
    }

    // ---- saved timetables ---------------------------------------------------------------------

    private static Map<String, Object> runSummary(TimetableRun r) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", r.id());
        out.put("label", r.label());
        out.put("createdAt", r.createdAt() == null ? null : r.createdAt().toString().replace('T', ' '));
        out.put("softCost", r.softCost());
        return out;
    }

    private TimetableRun run(int id) {
        return store.timetables().findById(id).orElseThrow(() -> new Http.ApiError(404, "That timetable no longer exists."));
    }

    private Map<String, Object> grid(int runId, String viewName, String subjectId) {
        TimetableRun run = run(runId);
        Dataset data = service.loadDataset();
        TimetableGrid.Perspective view;
        try {
            view = TimetableGrid.Perspective.valueOf(viewName.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new Http.ApiError(400, "View must be batch, faculty or room.");
        }
        List<TimetableGrid.Subject> subjects = TimetableGrid.subjects(data, view);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("run", runSummary(run));
        out.put("view", view.name());
        out.put("subjects", subjects);
        out.put("week", week(data.config()));
        TimetableGrid.Subject subject = subjects.stream().filter(s -> String.valueOf(s.id()).equals(subjectId)).findFirst()
                .orElse(subjects.isEmpty() ? null : subjects.get(0));
        out.put("subject", subject);
        List<List<Map<String, Object>>> rows = new ArrayList<>();
        if (subject != null) {
            TimetableGrid g = TimetableGrid.build(data, run.entries(), view, subject);
            for (int d = 0; d < data.config().days(); d++) {
                List<Map<String, Object>> row = new ArrayList<>();
                for (int p = 0; p < data.config().periodsPerDay(); p++) {
                    List<Map<String, Object>> items = new ArrayList<>();
                    for (TimetableGrid.Cell c : g.at(d, p)) {
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("heading", g.heading(c));
                        item.put("title", c.course().title());
                        item.put("details", g.detailsLine(c));
                        item.put("joint", g.jointWith(c));
                        item.put("tooltip", TimetableGrid.tooltip(c));
                        item.put("colour", PALETTE[Math.floorMod(TimetableGrid.colourKey(c).hashCode(), PALETTE.length)]);
                        item.put("lab", c.course().roomType().name().equals("LAB"));
                        items.add(item);
                    }
                    row.add(Map.of("clash", g.clashAt(d, p), "items", items));
                }
                rows.add(row);
            }
        }
        out.put("rows", rows);
        TimetableService.Evaluation ev = service.evaluate(data, run, SoftConstraintEvaluator.Weights.defaults());
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("valid", ev.valid());
        status.put("placed", ev.placedMeetings());
        status.put("total", ev.totalMeetings());
        status.put("cost", ev.breakdown().describe());
        status.put("problems", ev.hardViolations().subList(0, Math.min(40, ev.hardViolations().size())));
        status.put("problemCount", ev.hardViolations().size());
        out.put("status", status);
        return out;
    }

    private Http.Download csv(int runId) throws IOException {
        TimetableRun run = run(runId);
        Path tmp = Files.createTempFile("slotwise", ".csv");
        try {
            CsvExporter.writeEntries(tmp, service.loadDataset(), run.entries());
            return new Http.Download(Files.readAllBytes(tmp), "text/csv; charset=utf-8", "slotwise-run-" + runId + ".csv");
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
