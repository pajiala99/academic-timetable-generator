package edu.timetable.solver;

import edu.timetable.service.SampleData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CliqueFinderTest {

    @Test
    void returnsAGenuineCliqueOnLargerInstances() {
        for (long seed = 1; seed <= 3; seed++) {
            Problem p = new Problem(SampleData.synthetic(15, 26, seed));
            CliqueFinder.Result r = CliqueFinder.find(p, 2_000);
            int[] c = r.members();
            for (int i = 0; i < c.length; i++)
                for (int j = i + 1; j < c.length; j++)
                    assertTrue(p.pieceNeighbourSet(c[i]).get(c[j]), "not adjacent: " + c[i] + ", " + c[j]);
        }
    }

    /** Exhaustive maximum clique over all subsets of pieces (a double period is two pieces) of small graphs. */
    @Test
    void matchesExhaustiveSearchOnSmallGraphs() {
        for (long seed = 0; seed < 300; seed++) {
            Problem p = new Problem(TinyInstances.random(seed));
            int n = p.pieceCount();
            int best = 0;
            for (int mask = 1; mask < (1 << n); mask++) {
                boolean clique = true;
                for (int a = 0; a < n && clique; a++)
                    for (int b = a + 1; b < n && clique; b++)
                        if ((mask >> a & 1) == 1 && (mask >> b & 1) == 1 && !p.pieceNeighbourSet(a).get(b)) clique = false;
                if (clique) best = Math.max(best, Integer.bitCount(mask));
            }
            CliqueFinder.Result r = CliqueFinder.find(p, 1_000);
            assertTrue(r.optimal());
            assertEquals(best, r.size(), "seed " + seed);
        }
    }
}
