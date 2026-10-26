package edu.timetable.solver;

import edu.timetable.service.SampleData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimulatedAnnealerTest {

    private static Schedule feasibleStart(Problem p) {
        return new BacktrackingSolver(p, BacktrackingSolver.Options.defaults(), null).solve().schedule();
    }

    private static SimulatedAnnealer.Result anneal(Problem p, Schedule start, long iterations, long seed) {
        SoftConstraintEvaluator e = new SoftConstraintEvaluator(p, SoftConstraintEvaluator.Weights.defaults());
        return new SimulatedAnnealer(p, e, new SimulatedAnnealer.Options(iterations, 0, 0, 0.5, seed)).improve(start, null, null);
    }

    @Test
    void improvesTheSampleAndStaysFeasible() {
        Problem p = new Problem(SampleData.department());
        SimulatedAnnealer.Result r = anneal(p, feasibleStart(p), 300_000, 1);
        assertEquals(List.of(), HardConstraintValidator.violations(p, r.best()));
        assertTrue(r.improved().total() <= r.initial().total());
        assertTrue(r.improved().total() < 0.5 * r.initial().total(),
                "expected a large improvement: " + r.initial().total() + " -> " + r.improved().total());
    }

    @Test
    void neverBreaksHardConstraintsOnATightInstance() {
        Problem p = new Problem(SampleData.synthetic(12, 28, 2));
        SimulatedAnnealer.Result r = anneal(p, feasibleStart(p), 200_000, 9);
        assertEquals(List.of(), HardConstraintValidator.violations(p, r.best()));
        assertTrue(r.rejectedInfeasible() > 0, "the tight instance should produce rejected moves");
    }

    @Test
    void isReproducibleForAGivenSeed() {
        Problem p = new Problem(SampleData.department());
        Schedule start = feasibleStart(p);
        Schedule a = anneal(p, start, 50_000, 4).best();
        Schedule b = anneal(p, start, 50_000, 4).best();
        for (int m = 0; m < p.meetingCount(); m++) {
            assertArrayEquals(new int[]{a.slotOf(m), a.roomOf(m)}, new int[]{b.slotOf(m), b.roomOf(m)});
        }
    }

    @Test
    void zeroIterationsReturnsTheStartingSchedule() {
        Problem p = new Problem(SampleData.department());
        Schedule start = feasibleStart(p);
        SimulatedAnnealer.Result r = anneal(p, start, 0, 1);
        assertEquals(r.initial().total(), r.improved().total(), 1e-9);
        for (int m = 0; m < p.meetingCount(); m++) assertEquals(start.slotOf(m), r.best().slotOf(m));
    }
}
