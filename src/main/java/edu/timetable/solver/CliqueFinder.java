package edu.timetable.solver;

import java.util.Arrays;
import java.util.BitSet;

/**
 * Maximum-clique search on the conflict graph of meeting <i>pieces</i> (a meeting of length d
 * has d pieces): branch and bound with a greedy-colouring upper bound (Tomita and Seki's MCQ).
 * The pieces of a clique pairwise need distinct slots, so its size is a lower bound on the
 * number of slots any timetable needs. For single-period meetings, pieces are just meetings.
 *
 * <p>The search is exponential in the worst case, so it runs under a time limit. If the limit
 * is hit the best clique found so far is still a valid lower bound, it just might not be the
 * maximum; {@link Result#optimal()} records which case occurred.
 */
public final class CliqueFinder {

    /** {@code members} are piece indices; see {@link Problem#pieceMeeting(int)}. */
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
        this.current = new int[p.pieceCount()];
        this.best = initialClique();
    }

    public static Result find(Problem p, long timeLimitMs) {
        long start = System.currentTimeMillis();
        CliqueFinder f = new CliqueFinder(p, timeLimitMs);
        if (p.pieceCount() > 0) {
            BitSet all = new BitSet(p.pieceCount());
            all.set(0, p.pieceCount());
            f.expand(all);
        }
        int[] members = f.best.clone();
        Arrays.sort(members);
        return new Result(members, !f.timedOut, f.nodes, System.currentTimeMillis() - start);
    }

    /** All pieces of one student group's or one teacher's meetings form a clique; start from the biggest. */
    private int[] initialClique() {
        int[] best = new int[0];
        for (int g = 0; g < p.groups().length; g++) best = larger(best, piecesOf(p.meetingsOfGroup(g)));
        for (int f = 0; f < p.faculty().length; f++) best = larger(best, piecesOf(p.meetingsOfFaculty(f)));
        return best;
    }

    private int[] piecesOf(int[] meetings) {
        BitSet set = new BitSet(p.pieceCount());
        BitSet wanted = new BitSet(p.meetingCount());
        for (int m : meetings) wanted.set(m);
        for (int piece = 0; piece < p.pieceCount(); piece++) if (wanted.get(p.pieceMeeting(piece))) set.set(piece);
        return set.stream().toArray();
    }

    private static int[] larger(int[] a, int[] b) {
        return b.length > a.length ? b : a;
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
            next.and(p.pieceNeighbourSet(v));
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
                available.andNot(p.pieceNeighbourSet(v));
                uncoloured.clear(v);
                order[idx] = v;
                colour[idx] = k;
                idx++;
            }
        }
    }
}
