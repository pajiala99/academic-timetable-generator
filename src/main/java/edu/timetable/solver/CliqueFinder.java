package edu.timetable.solver;

import java.util.Arrays;
import java.util.BitSet;

/**
 * Maximum-clique search on the conflict graph: branch and bound with a greedy-colouring upper
 * bound (Tomita and Seki's MCQ). A clique is a set of meetings that pairwise must use different
 * slots, so its size is a lower bound on the number of slots any clash-free timetable needs.
 *
 * <p>The search is exponential in the worst case, so it runs under a time limit. If the limit
 * is hit the best clique found so far is still a valid lower bound, it just might not be the
 * maximum; {@link Result#optimal()} records which case occurred.
 */
public final class CliqueFinder {

    public record Result(int[] members, boolean optimal, long nodes, long millis) {
        public int size() {
            return members.length;
        }
    }

    private final Problem p;
    private final long deadline;
    private final int[] current;
    private int currentSize;
    private int[] best;
    private long nodes;
    private boolean timedOut;

    private CliqueFinder(Problem p, long timeLimitMs) {
        this.p = p;
        this.deadline = System.currentTimeMillis() + Math.max(1, timeLimitMs);
        this.current = new int[p.meetingCount()];
        this.best = initialClique();
    }

    public static Result find(Problem p, long timeLimitMs) {
        long start = System.currentTimeMillis();
        CliqueFinder f = new CliqueFinder(p, timeLimitMs);
        if (p.meetingCount() > 0) {
            BitSet all = new BitSet(p.meetingCount());
            all.set(0, p.meetingCount());
            f.expand(all);
        }
        int[] members = f.best.clone();
        Arrays.sort(members);
        return new Result(members, !f.timedOut, f.nodes, System.currentTimeMillis() - start);
    }

    /** Every batch's and every faculty member's meetings form a clique; start from the biggest. */
    private int[] initialClique() {
        int[] start = new int[0];
        for (int b = 0; b < p.batches().length; b++)
            if (p.meetingsOfBatch(b).length > start.length) start = p.meetingsOfBatch(b);
        for (int f = 0; f < p.faculty().length; f++)
            if (p.meetingsOfFaculty(f).length > start.length) start = p.meetingsOfFaculty(f);
        return start.clone();
    }

    private void expand(BitSet candidates) {
        nodes++;
        if ((nodes & 1023) == 0 && System.currentTimeMillis() > deadline) timedOut = true;
        if (timedOut) return;

        int n = candidates.cardinality();
        int[] order = new int[n];
        int[] colour = new int[n];
        colourSort(candidates, order, colour);

        for (int i = n - 1; i >= 0; i--) {
            // colour[i] colours suffice for every candidate up to i, so no clique through them
            // can contain more than colour[i] further vertices.
            if (currentSize + colour[i] <= best.length) return;
            int v = order[i];
            current[currentSize++] = v;
            BitSet next = (BitSet) candidates.clone();
            next.and(p.neighbourSet(v));
            if (next.isEmpty()) {
                if (currentSize > best.length) best = Arrays.copyOf(current, currentSize);
            } else {
                expand(next);
            }
            currentSize--;
            candidates.clear(v);
            if (timedOut) return;
        }
    }

    /** Greedy sequential colouring; fills {@code order} by non-decreasing colour number. */
    private void colourSort(BitSet candidates, int[] order, int[] colour) {
        BitSet uncoloured = (BitSet) candidates.clone();
        int k = 0;
        int idx = 0;
        while (!uncoloured.isEmpty()) {
            k++;
            BitSet available = (BitSet) uncoloured.clone();
            for (int v = available.nextSetBit(0); v >= 0; v = available.nextSetBit(v + 1)) {
                available.andNot(p.neighbourSet(v));
                uncoloured.clear(v);
                order[idx] = v;
                colour[idx] = k;
                idx++;
            }
        }
    }
}
