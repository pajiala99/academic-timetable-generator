package edu.timetable.solver;

import edu.timetable.model.Batch;
import edu.timetable.model.Course;
import edu.timetable.model.Dataset;
import edu.timetable.model.Faculty;
import edu.timetable.model.Room;
import edu.timetable.model.RoomType;
import edu.timetable.model.SchedulingConfig;
import edu.timetable.service.SampleData;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SoftConstraintEvaluatorTest {
    private static final SoftConstraintEvaluator.Weights UNIT = new SoftConstraintEvaluator.Weights(1, 1, 1, 1);

    /**
     * Batch B1 on day 0 has C1 in P1 (building A), C1 in P3 (building B) and C1 in P4 (building A);
     * on day 1 it has C2 in P2.
     */
    @Test
    void scoresEachTermAsDocumented() {
        Dataset d = new Dataset(new SchedulingConfig(2, 4),
                List.of(new Room(1, "R1", "A", 50, RoomType.LECTURE), new Room(2, "R2", "B", 60, RoomType.LECTURE)),
                List.of(new Faculty(1, "F1"), new Faculty(2, "F2")),
                List.of(new Batch(1, "B1", 40)),
                List.of(new Course(1, "C1", "One", 1, 1, 3, RoomType.LECTURE), new Course(2, "C2", "Two", 2, 1, 1, RoomType.LECTURE)));
        Problem p = new Problem(d);
        Schedule s = new Schedule(p.meetingCount());
        s.assign(0, 0, 0); // C1#1  day 0, P1, R1 (A)
        s.assign(1, 2, 1); // C1#2  day 0, P3, R2 (B)
        s.assign(2, 3, 0); // C1#3  day 0, P4, R1 (A)
        s.assign(3, 5, 0); // C2#1  day 1, P2, R1 (A)

        SoftConstraintEvaluator.Breakdown b = new SoftConstraintEvaluator(p, UNIT).breakdown(s);
        assertEquals(1, b.idleGaps(), "free P2 between P1 and P3 on day 0");
        assertEquals(2, b.buildingChanges(), "A -> B -> A on day 0");
        assertEquals(4, b.loadSpread(), "F1 teaches 3 + 0; evenest split 2 + 1: 9 - 5");
        assertEquals(3, b.sessionSpacing(), 1e-9, "three C1 pairs on the same day");
        assertEquals(10, b.total(), 1e-9);
    }

    @Test
    void adjacentDaysCostAQuarterPerPair() {
        Dataset d = new Dataset(new SchedulingConfig(3, 2),
                List.of(new Room(1, "R1", "A", 50, RoomType.LECTURE)), List.of(new Faculty(1, "F1")),
                List.of(new Batch(1, "B1", 40)), List.of(new Course(1, "C1", "One", 1, 1, 2, RoomType.LECTURE)));
        Problem p = new Problem(d);
        Schedule s = new Schedule(p.meetingCount());
        s.assign(0, 0, 0); // day 0
        s.assign(1, 2, 0); // day 1
        assertEquals(SoftConstraintEvaluator.ADJACENT_DAY_PENALTY, new SoftConstraintEvaluator(p, UNIT).breakdown(s).sessionSpacing(), 1e-9);
    }

    /** The annealer relies on local re-scoring; it must equal the change in the full objective. */
    @Test
    void localCostDeltaMatchesFullRecomputation() {
        Problem p = new Problem(SampleData.department());
        Schedule s = new BacktrackingSolver(p, BacktrackingSolver.Options.defaults(), null).solve().schedule();
        SoftConstraintEvaluator e = new SoftConstraintEvaluator(p, SoftConstraintEvaluator.Weights.defaults());
        Random rnd = new Random(5);
        for (int i = 0; i < 2_000; i++) {
            int a = rnd.nextInt(p.meetingCount());
            int b = rnd.nextBoolean() ? -1 : rnd.nextInt(p.meetingCount());
            if (a == b) continue;
            double totalBefore = e.total(s), localBefore = e.localCost(s, a, b);
            int sa = s.slotOf(a), ra = s.roomOf(a);
            if (b < 0) {
                s.assign(a, rnd.nextInt(p.slotCount()), rnd.nextInt(p.roomCount()));
            } else {
                s.assign(a, s.slotOf(b), s.roomOf(b));
                s.assign(b, sa, ra);
            }
            double totalAfter = e.total(s), localAfter = e.localCost(s, a, b);
            assertEquals(totalAfter - totalBefore, localAfter - localBefore, 1e-9, "move " + i);
        }
    }
}
