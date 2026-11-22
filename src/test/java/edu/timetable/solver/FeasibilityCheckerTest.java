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

import java.util.ArrayList;
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
        assertEquals(25, r.lowerBound()); // CSE-2A and CSE-3A each have 25 teaching periods a week
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
        assertTrue(mentions(r, "Part-timer: 3 teaching periods that must be in different slots can only use 2 slot(s)"), r.reasons().toString());
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
        assertTrue(mentions(r, "Not enough rooms: 5 teaching periods"), r.reasons().toString());
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
        for (int b = 0; b < 3; b++) assertTrue(p.meetingsOfGroup(b).length <= 4);
        for (int f = 0; f < 3; f++) assertTrue(p.meetingsOfFaculty(f).length <= 4);

        FeasibilityReport r = FeasibilityChecker.check(p, 1_000);
        assertEquals(5, r.lowerBound());
        assertTrue(r.clique().optimal());
        assertEquals(1, r.reasons().size(), r.reasons().toString());
        assertTrue(mentions(r, "Clique bound violated: 5 teaching periods pairwise clash (no single batch or teacher"), r.reasons().toString());
        assertFalse(TinyInstances.bruteForceFeasible(p));
    }

    /** Two double-period labs and a lecture for one batch: 5 periods, which a 4-period day cannot hold. */
    @Test
    void cliqueBoundCountsEveryPeriodOfALongSession() {
        Dataset d = new Dataset(WEEK, List.of(new Room(1, "LAB", "X", 60, RoomType.LAB), new Room(2, "LAB2", "X", 60, RoomType.LAB),
                new Room(3, "LH", "X", 60, RoomType.LECTURE)),
                List.of(new Faculty(1, "F1"), new Faculty(2, "F2"), new Faculty(3, "F3")), List.of(new Batch(1, "B", 40)),
                List.of(new Course(1, "L1", "Lab", 1, List.of(1), 1, 2, RoomType.LAB),
                        new Course(2, "L2", "Lab", 2, List.of(1), 1, 2, RoomType.LAB),
                        new Course(3, "C", "Lecture", 3, 1, 1, RoomType.LECTURE)));
        FeasibilityReport r = check(d);
        assertEquals(5, r.lowerBound());
        assertTrue(mentions(r, "Clique bound violated: 5 teaching periods"), r.reasons().toString());
    }

    @Test
    void explainsSessionsThatCannotFit() {
        Faculty part = new Faculty(1, "Gappy", Set.of(new TimeSlot(0, 1), new TimeSlot(0, 3)));
        Faculty capped = new Faculty(2, "Capped", Set.of(), 1);
        Dataset d = new Dataset(WEEK, List.of(new Room(1, "LAB", "X", 60, RoomType.LAB)), List.of(part, capped),
                List.of(new Batch(1, "B", 40), new Batch(2, "B2", 40)),
                List.of(new Course(1, "L1", "Lab", 1, List.of(1), 1, 2, RoomType.LAB),
                        new Course(2, "L2", "Lab", 2, List.of(2), 1, 2, RoomType.LAB),
                        new Course(3, "L5", "Marathon", 2, List.of(2), 1, 5, RoomType.LAB)));
        FeasibilityReport r = check(d);
        assertTrue(mentions(r, "L1 has 2-period sessions, but Gappy never has 2 consecutive free periods"), r.reasons().toString());
        assertTrue(mentions(r, "L2 has 2-period sessions, but Capped may teach at most 1 periods a day"), r.reasons().toString());
        assertTrue(mentions(r, "L5 has 5-period sessions, but a day has only 4 periods"), r.reasons().toString());
    }

    /**
     * Five double-period labs, one lab, two 5-period days: 10 periods for 10 free lab periods, so
     * every per-period count passes, but a 5-period day holds only two double sessions per room.
     */
    @Test
    void longSessionsNeedWholeRoomDays() {
        SchedulingConfig week = new SchedulingConfig(2, 5);
        List<Batch> batches = new ArrayList<>();
        List<Faculty> faculty = new ArrayList<>();
        List<Course> courses = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            batches.add(new Batch(i, "B" + i, 30));
            faculty.add(new Faculty(i, "F" + i));
            courses.add(new Course(i, "L" + i, "Lab", i, List.of(i), 1, 2, RoomType.LAB));
        }
        Problem p = new Problem(new Dataset(week, List.of(new Room(1, "LAB", "X", 60, RoomType.LAB)), faculty, batches, courses));
        FeasibilityReport r = FeasibilityChecker.check(p, 1_000);
        assertEquals(1, r.reasons().size(), r.reasons().toString());
        assertTrue(mentions(r, "Not enough room for long sessions: 5 sessions of two or more periods can only use rooms [LAB], "
                + "which fit at most 4 such sessions a week"), r.reasons().toString());
        assertFalse(TinyInstances.bruteForceFeasible(p));
    }

    /** Six single periods for a teacher limited to 1 a day in a 4-day week: at most 4 fit. */
    @Test
    void dailyLimitCapacityIsChecked() {
        SchedulingConfig week = new SchedulingConfig(4, 3);
        List<Batch> batches = new ArrayList<>();
        List<Course> courses = new ArrayList<>();
        for (int b = 1; b <= 6; b++) {
            batches.add(new Batch(b, "B" + b, 30));
            courses.add(new Course(b, "C" + b, "Course", 1, b, 1, RoomType.LECTURE));
        }
        Dataset d = new Dataset(week, List.of(new Room(1, "R", "X", 60, RoomType.LECTURE), new Room(2, "R2", "X", 60, RoomType.LECTURE)),
                List.of(new Faculty(1, "Limited", Set.of(), 1)), batches, courses);
        FeasibilityReport r = check(d);
        assertTrue(mentions(r, "Limited teaches 6 periods a week, but with a limit of 1 per day and their availability can teach at most 4"),
                r.reasons().toString());
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
