package edu.timetable.solver;

import edu.timetable.model.Batch;
import edu.timetable.model.Dataset;
import edu.timetable.service.SampleData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BacktrackingSolverTest {

    private static BacktrackingSolver.Result solve(Problem p, long seed) {
        return new BacktrackingSolver(p, new BacktrackingSolver.Options(10_000, 50, 1.3, seed), null).solve();
    }

    @Test
    void solvesTheSampleDepartmentWithoutClashes() {
        Problem p = new Problem(SampleData.department());
        BacktrackingSolver.Result r = solve(p, 1);
        assertEquals(BacktrackingSolver.Status.SOLVED, r.status());
        assertNotNull(r.schedule());
        assertTrue(r.schedule().isComplete());
        assertEquals(List.of(), HardConstraintValidator.violations(p, r.schedule()));
    }

    @Test
    void solvesTightSyntheticInstances() {
        for (long seed = 1; seed <= 3; seed++) {
            Problem p = new Problem(SampleData.synthetic(12, 28, seed));
            BacktrackingSolver.Result r = solve(p, seed);
            assertEquals(BacktrackingSolver.Status.SOLVED, r.status(), "seed " + seed);
            assertTrue(HardConstraintValidator.isFeasible(p, r.schedule()), "seed " + seed);
        }
    }

    /**
     * On hundreds of random tiny instances the solver must agree with exhaustive enumeration:
     * SOLVED exactly when a timetable exists, INFEASIBLE exactly when none does. This checks
     * both soundness (every solution is valid) and completeness (no solution is missed,
     * which would expose an unsound pruning or symmetry-breaking rule).
     */
    @Test
    void agreesWithBruteForceOnRandomTinyInstances() {
        int feasible = 0, infeasible = 0, infeasibleOnlyBySearch = 0;
        for (long seed = 0; seed < 3_000; seed++) {
            Dataset data = TinyInstances.random(seed);
            Problem p = new Problem(data);
            boolean truth = TinyInstances.bruteForceFeasible(p);
            BacktrackingSolver.Result r = solve(p, seed);
            if (truth) {
                feasible++;
                assertEquals(BacktrackingSolver.Status.SOLVED, r.status(), "seed " + seed);
                assertTrue(HardConstraintValidator.isFeasible(p, r.schedule()), "seed " + seed);
            } else {
                infeasible++;
                assertEquals(BacktrackingSolver.Status.INFEASIBLE, r.status(), "seed " + seed);
                if (!FeasibilityChecker.check(p, 1_000).provenInfeasible()) infeasibleOnlyBySearch++;
            }
        }
        System.out.printf("brute-force agreement: %d feasible, %d infeasible (%d of them only provable by search)%n",
                feasible, infeasible, infeasibleOnlyBySearch);
        // The generator must exercise both outcomes, and some infeasible cases must slip past
        // the pre-checks (they are necessary conditions only) so that search has to prove them.
        assertTrue(feasible > 50, "feasible cases: " + feasible);
        assertTrue(infeasible > 50, "infeasible cases: " + infeasible);
        assertTrue(infeasibleOnlyBySearch > 0, "no infeasible case needed the search");
    }

    @Test
    void reportsInfeasibleWhenAMeetingHasNowhereToGo() {
        // CS101L needs a lab for 70 students but the largest lab seats 64.
        Dataset base = SampleData.department();
        List<Batch> batches = base.batches().stream()
                .map(b -> b.name().equals("CSE-1A") ? new Batch(b.id(), b.name(), 70) : b).toList();
        Problem p = new Problem(new Dataset(base.config(), base.rooms(), base.faculty(), batches, base.courses()));
        BacktrackingSolver.Result r = solve(p, 3);
        assertEquals(BacktrackingSolver.Status.INFEASIBLE, r.status());
        assertEquals(1, r.nodes(), "the empty domain is selected first by MRV and fails at once");
    }

    /**
     * Pigeonhole-style infeasibility (36 meetings of one batch, 35 slots) is exponentially hard
     * for backtracking, which is why the engine runs the maximum-clique bound first. Here the
     * search alone cannot finish within a short limit, while the clique bound proves the same
     * fact instantly.
     */
    @Test
    void pigeonholeInfeasibilityNeedsTheCliqueBound() {
        Problem p = new Problem(SampleData.overloaded());
        BacktrackingSolver.Result search = new BacktrackingSolver(p, new BacktrackingSolver.Options(1_000, 1_000, 1.3, 1), null).solve();
        assertEquals(BacktrackingSolver.Status.TIMEOUT, search.status());
        assertTrue(FeasibilityChecker.check(p, 1_000).provenInfeasible());
    }

    @Test
    void cancellationStopsTheSearch() {
        // 520 meetings need at least 520 nodes, well past the first cancellation poll.
        Problem p = new Problem(SampleData.synthetic(20, 26, 1));
        BacktrackingSolver.Result r = new BacktrackingSolver(p, BacktrackingSolver.Options.defaults(), () -> true).solve();
        assertEquals(BacktrackingSolver.Status.CANCELLED, r.status());
        assertEquals(null, r.schedule());
    }
}
