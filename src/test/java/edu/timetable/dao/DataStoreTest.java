package edu.timetable.dao;

import edu.timetable.model.Batch;
import edu.timetable.model.Course;
import edu.timetable.model.Dataset;
import edu.timetable.model.Faculty;
import edu.timetable.model.Room;
import edu.timetable.model.RoomType;
import edu.timetable.model.SchedulingConfig;
import edu.timetable.model.TimeSlot;
import edu.timetable.model.TimetableRun;
import edu.timetable.service.SampleData;
import edu.timetable.service.TimetableService;
import edu.timetable.solver.SoftConstraintEvaluator;
import edu.timetable.solver.TimetableEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the JDBC DAOs against an in-memory H2 database in MySQL compatibility mode, or against a
 * real MySQL server when {@code -Dtest.mysql.url=jdbc:mysql://...} is given (the database is
 * wiped before every test, so point it at a scratch schema):
 * <pre>
 *   mvn test -Dtest=DataStoreTest -Dtest.mysql.url="jdbc:mysql://localhost:3306/timetable_test?createDatabaseIfNotExist=true" \
 *            -Dtest.mysql.user=timetable -Dtest.mysql.password=timetable
 * </pre>
 */
class DataStoreTest {
    private DataStore store;

    @BeforeEach
    void open() {
        String mysql = System.getProperty("test.mysql.url");
        if (mysql == null || mysql.isBlank()) {
            store = new DataStore(Database.inMemory("test-" + UUID.randomUUID()));
        } else {
            store = new DataStore(new Database(mysql, System.getProperty("test.mysql.user", "root"),
                    System.getProperty("test.mysql.password", "")));
            store.clearAll();
        }
    }

    @Test
    void schemaInitialisationIsIdempotent() {
        store.database().initialize();
        store.database().initialize();
        assertTrue(store.isEmpty());
    }

    @Test
    void importedSampleLoadsBackIdentically() {
        Dataset original = SampleData.department();
        store.importDataset(original);
        Dataset loaded = store.loadDataset();
        assertEquals(original.config(), loaded.config());
        assertEquals(original.rooms().size(), loaded.rooms().size());
        assertEquals(original.batches().size(), loaded.batches().size());
        assertEquals(original.faculty().size(), loaded.faculty().size());
        assertEquals(original.courses().size(), loaded.courses().size());
        assertEquals(original.totalMeetings(), loaded.totalMeetings());
        Faculty rao = loaded.faculty().stream().filter(f -> f.name().equals("Dr. Anita Rao")).findFirst().orElseThrow();
        assertEquals(7, rao.unavailable().size());
        // Every course points at an existing batch and faculty member after id remapping.
        for (Course c : loaded.courses()) {
            assertTrue(loaded.batchById().keySet().containsAll(c.batchIds()));
            assertTrue(loaded.facultyById().containsKey(c.facultyId()));
        }
    }

    @Test
    void roomCrud() {
        Room r = store.rooms().insert(new Room(0, "LH-1", "Main", 60, RoomType.LECTURE));
        assertTrue(r.id() > 0);
        assertEquals(r, store.rooms().findById(r.id()).orElseThrow());
        store.rooms().update(new Room(r.id(), "LH-1", "Annex", 80, RoomType.LAB));
        Room updated = store.rooms().findById(r.id()).orElseThrow();
        assertEquals("Annex", updated.building());
        assertEquals(80, updated.capacity());
        assertEquals(RoomType.LAB, updated.type());
        store.rooms().delete(r.id());
        assertTrue(store.rooms().findAll().isEmpty());
    }

    @Test
    void facultyUnavailabilityIsReplacedOnUpdate() {
        Faculty f = store.faculty().insert(new Faculty(0, "Dr. X", Set.of(new TimeSlot(0, 0), new TimeSlot(1, 2))));
        assertEquals(Set.of(new TimeSlot(0, 0), new TimeSlot(1, 2)), store.faculty().findById(f.id()).orElseThrow().unavailable());
        store.faculty().update(new Faculty(f.id(), "Dr. X", Set.of(new TimeSlot(4, 6))));
        assertEquals(Set.of(new TimeSlot(4, 6)), new HashSet<>(store.faculty().findAll().get(0).unavailable()));
        store.faculty().delete(f.id());
        assertTrue(store.faculty().findAll().isEmpty());
    }

    @Test
    void foreignKeysProtectReferencedRows() {
        Batch b = store.batches().insert(new Batch(0, "CSE-1", 60));
        Faculty f = store.faculty().insert(new Faculty(0, "Dr. Y"));
        store.courses().insert(new Course(0, "CS1", "Intro", f.id(), b.id(), 3, RoomType.LECTURE));
        DataAccessException e = assertThrows(DataAccessException.class, () -> store.batches().delete(b.id()));
        assertTrue(e.isConstraintViolation());
        assertThrows(DataAccessException.class, () -> store.faculty().delete(f.id()));
        assertEquals(1, store.courses().findByBatch(b.id()).size());
    }

    @Test
    void combinedClassesKeepAllTheirBatches() {
        Batch a = store.batches().insert(new Batch(0, "A", 40));
        Batch b = store.batches().insert(new Batch(0, "B", 45));
        Batch c = store.batches().insert(new Batch(0, "C", 50));
        Faculty f = store.faculty().insert(new Faculty(0, "Dr. Z"));
        Course elective = store.courses().insert(new Course(0, "EL1", "Elective", f.id(), List.of(b.id(), a.id()), 2, RoomType.LECTURE));
        assertEquals(List.of(a.id(), b.id()), store.courses().findById(elective.id()).orElseThrow().batchIds());
        assertEquals(1, store.courses().findByBatch(b.id()).size());
        assertTrue(store.courses().findByBatch(c.id()).isEmpty());

        store.courses().update(new Course(elective.id(), "EL1", "Elective", f.id(), List.of(c.id()), 2, RoomType.LECTURE));
        assertEquals(List.of(c.id()), store.courses().findById(elective.id()).orElseThrow().batchIds());
        assertTrue(store.courses().findByBatch(a.id()).isEmpty());
        store.batches().delete(a.id()); // no longer referenced
        assertThrows(DataAccessException.class, () -> store.batches().delete(c.id()));
    }

    @Test
    void labGroupsDailyLimitsAndSessionLengthsRoundTrip() {
        Batch whole = store.batches().insert(new Batch(0, "CSE-1A", 60));
        Batch g1 = store.batches().insert(new Batch(0, "CSE-1A G1", 30, whole.id()));
        Faculty f = store.faculty().insert(new Faculty(0, "Dr. L", Set.of(new TimeSlot(0, 0)), 4));
        Course lab = store.courses().insert(new Course(0, "CS101L", "Lab", f.id(), List.of(g1.id()), 1, 2, RoomType.LAB));

        assertEquals(whole.id(), store.batches().findById(g1.id()).orElseThrow().parentId());
        assertEquals(null, store.batches().findById(whole.id()).orElseThrow().parentId());
        assertEquals(4, store.faculty().findById(f.id()).orElseThrow().maxPerDay());
        assertEquals(2, store.courses().findById(lab.id()).orElseThrow().periodsPerMeeting());

        store.faculty().update(new Faculty(f.id(), "Dr. L", Set.of(), 0));
        assertEquals(0, store.faculty().findAll().get(0).maxPerDay());
        store.batches().update(new Batch(g1.id(), "CSE-1A G1", 30, null));
        assertEquals(null, store.batches().findById(g1.id()).orElseThrow().parentId());
        store.batches().update(new Batch(g1.id(), "CSE-1A G1", 30, whole.id()));

        // A batch with lab groups cannot be deleted from under them.
        store.courses().delete(lab.id());
        DataAccessException e = assertThrows(DataAccessException.class, () -> store.batches().delete(whole.id()));
        assertTrue(e.isConstraintViolation());
        store.clearAll();
        assertTrue(store.batches().findAll().isEmpty());
    }

    /** A database created by the first release gains the new columns, keeping its data. */
    @Test
    void upgradesADatabaseFromTheFirstRelease() throws Exception {
        Database old = Database.inMemory("old-" + UUID.randomUUID());
        try (Connection c = old.connect(); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE faculty (id INT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(100) NOT NULL UNIQUE)");
            st.execute("CREATE TABLE batch (id INT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(64) NOT NULL UNIQUE, "
                    + "student_count INT NOT NULL)");
            st.execute("CREATE TABLE course (id INT AUTO_INCREMENT PRIMARY KEY, code VARCHAR(32) NOT NULL, title VARCHAR(128) NOT NULL, "
                    + "faculty_id INT NOT NULL, meetings_per_week INT NOT NULL, room_type VARCHAR(16) NOT NULL)");
            st.execute("INSERT INTO faculty (name) VALUES ('Dr. Old')");
            st.execute("INSERT INTO batch (name, student_count) VALUES ('OLD-1', 40)");
        }
        DataStore upgraded = new DataStore(old);
        Faculty f = upgraded.faculty().findAll().get(0);
        assertEquals("Dr. Old", f.name());
        assertEquals(0, f.maxPerDay());
        assertEquals(null, upgraded.batches().findAll().get(0).parentId());
        upgraded.importDataset(SampleData.department());
        assertEquals(SampleData.department().totalMeetings(), upgraded.loadDataset().totalMeetings());
    }

    @Test
    void uniqueCodesAreEnforced() {
        store.rooms().insert(new Room(0, "LH-1", "Main", 60, RoomType.LECTURE));
        DataAccessException e = assertThrows(DataAccessException.class,
                () -> store.rooms().insert(new Room(0, "LH-1", "Other", 30, RoomType.LECTURE)));
        assertTrue(e.isConstraintViolation());
    }

    @Test
    void settingsRoundTrip() {
        store.settings().saveConfig(new SchedulingConfig(6, 8));
        assertEquals(new SchedulingConfig(6, 8), store.settings().loadConfig());
        store.settings().put("x", "1");
        store.settings().put("x", "2");
        assertEquals("2", store.settings().get("x").orElseThrow());
    }

    @Test
    void generatedTimetablesArePersistedAndReEvaluated() {
        store.importDataset(SampleData.department());
        TimetableService service = new TimetableService(store);
        TimetableEngine.Result result = service.generate(TimetableEngine.Options.defaults(), null, null);
        assertTrue(result.success());
        TimetableRun saved = service.save(result, "test run");

        List<TimetableRun> summaries = store.timetables().findAllSummaries();
        assertEquals(1, summaries.size());
        assertTrue(summaries.get(0).entries().isEmpty(), "summaries do not load entries");
        TimetableRun loaded = store.timetables().findById(saved.id()).orElseThrow();
        assertEquals("test run", loaded.label());
        assertEquals(new HashSet<>(result.entries()), new HashSet<>(loaded.entries()));
        assertTrue(loaded.notes().contains("Backtracking: SOLVED"));

        TimetableService.Evaluation ok = service.evaluate(store.loadDataset(), loaded, SoftConstraintEvaluator.Weights.defaults());
        assertTrue(ok.valid(), ok.hardViolations().toString());
        assertEquals(126, ok.placedMeetings());

        // Deleting a room cascades to the entries that used it: the saved run is now incomplete,
        // and re-evaluation says so instead of pretending it is still valid.
        int roomId = loaded.entries().get(0).roomId();
        store.rooms().delete(roomId);
        TimetableRun stale = store.timetables().findById(saved.id()).orElseThrow();
        TimetableService.Evaluation bad = service.evaluate(store.loadDataset(), stale, SoftConstraintEvaluator.Weights.defaults());
        assertFalse(bad.valid());
        assertTrue(bad.placedMeetings() < 126);

        store.timetables().delete(saved.id());
        assertTrue(store.timetables().findAllSummaries().isEmpty());
    }

    @Test
    void clearAllRemovesEverything() {
        store.importDataset(SampleData.department());
        assertFalse(store.isEmpty());
        store.clearAll();
        assertTrue(store.isEmpty());
        assertTrue(store.courses().findAll().isEmpty());
    }
}
