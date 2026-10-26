package edu.timetable.solver;

import edu.timetable.model.Batch;
import edu.timetable.model.Course;
import edu.timetable.model.Dataset;
import edu.timetable.model.Faculty;
import edu.timetable.model.Room;
import edu.timetable.model.RoomType;
import edu.timetable.model.SchedulingConfig;
import edu.timetable.model.TimeSlot;
import edu.timetable.service.SampleData;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeasibilityCheckerTest {
    private static final SchedulingConfig WEEK = new SchedulingConfig(1, 4);

    private static FeasibilityReport check(Dataset d) {
        return FeasibilityChecker.check(new Problem(d), 1_000);
    }

    private static boolean mentions(FeasibilityReport r, String text) {
        return r.reasons().stream().anyMatch(s -> s.contains(text));
    }

    @Test
    void sampleDepartmentPassesWithProvenMaximumClique() {
        FeasibilityReport r = check(SampleData.department());
        assertFalse(r.provenInfeasible(), r.reasons().toString());
        assertEquals(25, r.lowerBound()); // CSE-3A: 22 own meetings + 3 of the combined ML elective
        assertTrue(r.clique().optimal());
    }

    @Test
    void cliqueBoundProvesOverloadedBatchInfeasible() {
        FeasibilityReport r = check(SampleData.overloaded());
        assertTrue(r.provenInfeasible());
        assertEquals(36, r.lowerBound());
        assertTrue(mentions(r, "Clique bound violated"), r.reasons().toString());
    }

    @Test
    void detectsLabRoomThatIsTooSmall() {
        Dataset d = new Dataset(WEEK,
                List.of(new Room(1, "LAB", "X", 30, RoomType.LAB), new Room(2, "LH", "X", 90, RoomType.LECTURE)),
                List.of(new Faculty(1, "F")), List.of(new Batch(1, "B", 60)),
                List.of(new Course(1, "C1L", "Lab", 1, 1, 2, RoomType.LAB)));
        FeasibilityReport r = check(d);
        assertTrue(mentions(r, "largest LAB room seats 30"), r.reasons().toString());
    }

    @Test
    void detectsFacultyWhoIsNeverAvailable() {
        Set<TimeSlot> always = Set.of(new TimeSlot(0, 0), new TimeSlot(0, 1), new TimeSlot(0, 2), new TimeSlot(0, 3));
        Dataset d = new Dataset(WEEK, List.of(new Room(1, "LH", "X", 90, RoomType.LECTURE)),
                List.of(new Faculty(1, "Busy", always)), List.of(new Batch(1, "B", 60)),
                List.of(new Course(1, "C1", "Course", 1, 1, 1, RoomType.LECTURE)));
        assertTrue(mentions(check(d), "unavailable in every slot"));
    }

    /** Three meetings fit in a 4-slot week, but the teacher is available for only two of them. */
    @Test
    void hallConditionCatchesTooFewAvailableSlots() {
        Faculty f = new Faculty(1, "Part-timer", Set.of(new TimeSlot(0, 2), new TimeSlot(0, 3)));
        Dataset d = new Dataset(WEEK, List.of(new Room(1, "LH", "X", 90, RoomType.LECTURE), new Room(2, "LH2", "X", 90, RoomType.LECTURE)),
                List.of(f), List.of(new Batch(1, "B1", 60), new Batch(2, "B2", 60)),
                List.of(new Course(1, "C1", "One", 1, 1, 2, RoomType.LECTURE), new Course(2, "C2", "Two", 1, 2, 1, RoomType.LECTURE)));
        FeasibilityReport r = check(d);
        assertTrue(r.provenInfeasible());
        assertTrue(mentions(r, "Part-timer: 3 meetings that must be in different slots can only use 2 slot(s)"), r.reasons().toString());
    }

    /** Five independent meetings, one room, four slots: no slot conflict, but not enough room-time. */
    @Test
    void hallConditionCatchesRoomShortage() {
        List<Faculty> faculty = List.of(new Faculty(1, "F1"), new Faculty(2, "F2"), new Faculty(3, "F3"), new Faculty(4, "F4"), new Faculty(5, "F5"));
        List<Batch> batches = List.of(new Batch(1, "B1", 40), new Batch(2, "B2", 40), new Batch(3, "B3", 40), new Batch(4, "B4", 40), new Batch(5, "B5", 40));
        List<Course> courses = List.of(
                new Course(1, "C1", "c", 1, 1, 1, RoomType.LECTURE), new Course(2, "C2", "c", 2, 2, 1, RoomType.LECTURE),
                new Course(3, "C3", "c", 3, 3, 1, RoomType.LECTURE), new Course(4, "C4", "c", 4, 4, 1, RoomType.LECTURE),
                new Course(5, "C5", "c", 5, 5, 1, RoomType.LECTURE));
        Dataset d = new Dataset(WEEK, List.of(new Room(1, "LH", "X", 90, RoomType.LECTURE)), faculty, batches, courses);
        FeasibilityReport r = check(d);
        assertEquals(1, r.lowerBound());
        assertTrue(mentions(r, "Not enough rooms: 5 meetings"), r.reasons().toString());
    }

    /**
     * Three combined electives pairwise share a batch: E1 (A+B, 2 meetings), E2 (B+C, 2) and
     * E3 (A+C, 1). No batch or teacher has more than 4 meetings, so every per-group count fits
     * the 4-slot week, yet all 5 meetings pairwise clash. Only the clique bound sees it.
     */
    @Test
    void cliqueBoundCatchesCombinedClassesThatGroupCountsMiss() {
        List<Room> rooms = List.of(new Room(1, "H1", "X", 200, RoomType.LECTURE), new Room(2, "H2", "X", 200, RoomType.LECTURE),
                new Room(3, "H3", "X", 200, RoomType.LECTURE));
        List<Faculty> faculty = List.of(new Faculty(1, "F1"), new Faculty(2, "F2"), new Faculty(3, "F3"));
        List<Batch> batches = List.of(new Batch(1, "A", 40), new Batch(2, "B", 40), new Batch(3, "C", 40));
        List<Course> courses = List.of(
                new Course(1, "E1", "AB", 1, List.of(1, 2), 2, RoomType.LECTURE),
                new Course(2, "E2", "BC", 2, List.of(2, 3), 2, RoomType.LECTURE),
                new Course(3, "E3", "AC", 3, List.of(1, 3), 1, RoomType.LECTURE));
        Problem p = new Problem(new Dataset(WEEK, rooms, faculty, batches, courses));
        for (int b = 0; b < 3; b++) assertTrue(p.meetingsOfBatch(b).length <= 4);
        for (int f = 0; f < 3; f++) assertTrue(p.meetingsOfFaculty(f).length <= 4);

        FeasibilityReport r = FeasibilityChecker.check(p, 1_000);
        assertEquals(5, r.lowerBound());
        assertTrue(r.clique().optimal());
        assertEquals(1, r.reasons().size(), r.reasons().toString());
        assertTrue(mentions(r, "Clique bound violated: 5 meetings pairwise clash (no single batch or teacher"), r.reasons().toString());
        assertFalse(TinyInstances.bruteForceFeasible(p));
    }

    /** Every infeasibility proof must be correct: whenever the checker says "infeasible", no timetable exists. */
    @Test
    void neverClaimsInfeasibilityForAFeasibleInstance() {
        for (long seed = 0; seed < 600; seed++) {
            Problem p = new Problem(TinyInstances.random(seed));
            if (FeasibilityChecker.check(p, 1_000).provenInfeasible()) {
                assertFalse(TinyInstances.bruteForceFeasible(p), "seed " + seed);
            }
        }
    }
}
