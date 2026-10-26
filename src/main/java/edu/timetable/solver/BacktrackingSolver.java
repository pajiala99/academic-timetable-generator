package edu.timetable.solver;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Random;
import java.util.function.BooleanSupplier;

/**
 * Complete backtracking search for a timetable that satisfies every hard constraint.
 *
 * <p>The search colours meetings with slots. Rooms are not branched on: for every slot the
 * solver maintains a bipartite matching between the meetings placed in that slot and the rooms
 * that suit them, so a set of meetings can share a slot exactly when the matching covers them
 * all. This removes the huge, mostly symmetric branching over room choices.
 *
 * <ul>
 *   <li><b>Variable ordering:</b> minimum remaining values (fewest slots left in the domain).
 *       Ties go to the meeting with the highest saturation degree (distinct slots already used by
 *       its assigned neighbours, as in DSatur), then to the one with most unassigned neighbours,
 *       then to the one with fewest suitable rooms, then randomly.</li>
 *   <li><b>Value ordering:</b> least-constraining slot first: the slot that the fewest unassigned
 *       neighbours could still use, then the one with the most free rooms.</li>
 *   <li><b>Forward checking:</b> placing a meeting in slot s removes s from the domain of every
 *       conflicting meeting, and from every other meeting that the room matching of s can no
 *       longer absorb. A domain wipe-out triggers an immediate backtrack.</li>
 *   <li><b>Restarts:</b> runs are cut off after a node budget that grows geometrically, with
 *       fresh random tie-breaking each time, which tames heavy-tailed run times. A run that
 *       exhausts its search tree within budget proves the instance infeasible.</li>
 * </ul>
 */
public final class BacktrackingSolver {

    public enum Status { SOLVED, INFEASIBLE, TIMEOUT, CANCELLED }

    /**
     * @param initialNodeLimit node budget of the first run; {@code <= 0} disables restarts
     * @param restartGrowth    factor by which the budget grows after each restart
     */
    public record Options(long timeLimitMs, long initialNodeLimit, double restartGrowth, long seed) {
        public static Options defaults() {
            return new Options(30_000, 1_000, 1.3, 42);
        }
    }

    public record Result(Status status, Schedule schedule, long nodes, long backtracks, int restarts, long millis) {
    }

    private static final int SOLVED = 1;
    private static final int EXHAUSTED = 0;
    private static final int ABORTED = -1;

    private final Problem p;
    private final Options options;
    private final BooleanSupplier cancelled;
    private final int meetingCount;
    private final int slotCount;
    private final int roomCount;
    private final int[][] suitableRooms;

    // Search state.
    private BitSet[] domain;
    private int[] slotOf;
    private int[] roomOf;
    private int[][] holder;          // holder[slot][room] = meeting using that room, or -1
    private int[] freeRooms;         // per slot
    private int[][] neighbourSlotUse;
    private int[] saturation;
    private int[] freeDegree;
    private int unassigned;

    // Trail of removed (meeting, slot) domain values, undone on backtrack.
    private int[] trailMeeting = new int[1024];
    private int[] trailSlot = new int[1024];
    private int trailSize;

    // Scratch space for augmenting-path searches.
    private final int[] visitStamp;
    private int stamp;

    private Random random;
    private long nodes;
    private long backtracks;
    private long nodeLimit;
    private long deadline;
    private Status abortStatus;

    public BacktrackingSolver(Problem problem, Options options, BooleanSupplier cancelled) {
        this.p = problem;
        this.options = options;
        this.cancelled = cancelled == null ? () -> false : cancelled;
        this.meetingCount = problem.meetingCount();
        this.slotCount = problem.slotCount();
        this.roomCount = problem.roomCount();
        this.suitableRooms = new int[meetingCount][];
        for (int m = 0; m < meetingCount; m++) suitableRooms[m] = problem.suitableRooms(m);
        this.visitStamp = new int[roomCount];
    }

    public Result solve() {
        long start = System.currentTimeMillis();
        deadline = start + Math.max(1, options.timeLimitMs());
        random = new Random(options.seed());
        boolean restarts = options.initialNodeLimit() > 0;
        double budget = restarts ? options.initialNodeLimit() : Double.MAX_VALUE;
        int restartCount = 0;
        while (true) {
            reset();
            nodeLimit = restarts ? nodes + (long) budget : Long.MAX_VALUE;
            abortStatus = null;
            int outcome = search();
            long millis = System.currentTimeMillis() - start;
            if (outcome == SOLVED) {
                Schedule s = new Schedule(meetingCount);
                for (int m = 0; m < meetingCount; m++) s.assign(m, slotOf[m], roomOf[m]);
                return new Result(Status.SOLVED, s, nodes, backtracks, restartCount, millis);
            }
            if (outcome == EXHAUSTED) {
                return new Result(Status.INFEASIBLE, null, nodes, backtracks, restartCount, millis);
            }
            if (abortStatus != null) {
                return new Result(abortStatus, null, nodes, backtracks, restartCount, millis);
            }
            restartCount++;
            budget *= Math.max(1.01, options.restartGrowth());
        }
    }

    private void reset() {
        domain = new BitSet[meetingCount];
        for (int m = 0; m < meetingCount; m++) {
            domain[m] = new BitSet(slotCount);
            for (int s : p.allowedSlots(m)) if (suitableRooms[m].length > 0) domain[m].set(s);
        }
        slotOf = new int[meetingCount];
        roomOf = new int[meetingCount];
        Arrays.fill(slotOf, -1);
        Arrays.fill(roomOf, -1);
        holder = new int[slotCount][roomCount];
        for (int[] row : holder) Arrays.fill(row, -1);
        freeRooms = new int[slotCount];
        Arrays.fill(freeRooms, roomCount);
        neighbourSlotUse = new int[meetingCount][slotCount];
        saturation = new int[meetingCount];
        freeDegree = new int[meetingCount];
        for (int m = 0; m < meetingCount; m++) freeDegree[m] = p.neighbours(m).length;
        unassigned = meetingCount;
        trailSize = 0;
    }

    private int search() {
        if (unassigned == 0) return SOLVED;
        if (++nodes > nodeLimit) return ABORTED;
        if ((nodes & 255) == 0) {
            if (cancelled.getAsBoolean()) {
                abortStatus = Status.CANCELLED;
                return ABORTED;
            }
            if (System.currentTimeMillis() > deadline) {
                abortStatus = Status.TIMEOUT;
                return ABORTED;
            }
        }

        int m = selectMeeting();
        for (int s : orderSlots(m)) {
            int mark = trailSize;
            if (place(m, s)) {
                if (forwardCheck(m, s)) {
                    int outcome = search();
                    if (outcome != EXHAUSTED) return outcome;
                }
                undo(mark);
                unplace(m);
            }
            backtracks++;
        }
        return EXHAUSTED;
    }

    private int selectMeeting() {
        int best = -1;
        int ties = 0;
        for (int m = 0; m < meetingCount; m++) {
            if (slotOf[m] >= 0) continue;
            int cmp = best < 0 ? 1 : compareUrgency(m, best);
            if (cmp == 0) {
                ties++;
                if (random.nextInt(ties + 1) == 0) best = m;
            } else if (cmp > 0) {
                ties = 0;
                best = m;
            }
        }
        return best;
    }

    /** Positive when meeting a should be placed before meeting b. */
    private int compareUrgency(int a, int b) {
        int cmp = Integer.compare(domain[b].cardinality(), domain[a].cardinality());
        if (cmp == 0) cmp = Integer.compare(saturation[a], saturation[b]);
        if (cmp == 0) cmp = Integer.compare(freeDegree[a], freeDegree[b]);
        if (cmp == 0) cmp = Integer.compare(suitableRooms[b].length, suitableRooms[a].length);
        return cmp;
    }

    private int[] orderSlots(int m) {
        int[] slots = domain[m].stream().toArray();
        long[] keys = new long[slots.length];
        for (int i = 0; i < slots.length; i++) {
            int s = slots[i];
            int impact = 0;
            for (int n : p.neighbours(m)) if (slotOf[n] < 0 && domain[n].get(s)) impact++;
            // Lower key first: fewer neighbours affected, then more free rooms, then random.
            keys[i] = ((long) impact << 40) | ((long) (roomCount - freeRooms[s]) << 20) | random.nextInt(1 << 20);
        }
        Integer[] idx = new Integer[slots.length];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        Arrays.sort(idx, (x, y) -> Long.compare(keys[x], keys[y]));
        int[] out = new int[slots.length];
        for (int i = 0; i < out.length; i++) out[i] = slots[idx[i]];
        return out;
    }

    // ---- placing meetings and the per-slot room matching ------------------------------------

    /** Puts m into slot s, re-matching the slot's rooms if necessary. False if no room can be found. */
    private boolean place(int m, int s) {
        stamp++;
        if (!augment(m, s, true)) return false;
        slotOf[m] = s;
        freeRooms[s]--;
        unassigned--;
        for (int n : p.neighbours(m)) {
            freeDegree[n]--;
            if (neighbourSlotUse[n][s]++ == 0) saturation[n]++;
        }
        return true;
    }

    private void unplace(int m) {
        int s = slotOf[m];
        for (int n : p.neighbours(m)) {
            freeDegree[n]++;
            if (--neighbourSlotUse[n][s] == 0) saturation[n]--;
        }
        holder[s][roomOf[m]] = -1;
        roomOf[m] = -1;
        slotOf[m] = -1;
        freeRooms[s]++;
        unassigned++;
    }

    /** Could meeting n join slot s without un-seating anybody for good? Does not modify the matching. */
    private boolean fits(int n, int s) {
        if (freeRooms[s] == 0) return false;
        for (int r : suitableRooms[n]) if (holder[s][r] < 0) return true;
        stamp++;
        return augment(n, s, false);
    }

    /**
     * Augmenting-path search in slot s: find a room for meeting m, moving already-seated meetings
     * of the slot to other suitable rooms if needed. With {@code commit} the matching is updated.
     */
    private boolean augment(int m, int s, boolean commit) {
        for (int r : suitableRooms[m]) {
            if (visitStamp[r] == stamp) continue;
            visitStamp[r] = stamp;
            int occupant = holder[s][r];
            if (occupant < 0 || augment(occupant, s, commit)) {
                if (commit) {
                    holder[s][r] = m;
                    roomOf[m] = r;
                }
                return true;
            }
        }
        return false;
    }

    // ---- forward checking ---------------------------------------------------------------------

    /** Prunes the other domains after placing m in slot s; returns false on a wipe-out. */
    private boolean forwardCheck(int m, int s) {
        BitSet conflicting = p.neighbourSet(m);
        for (int n = 0; n < meetingCount; n++) {
            if (slotOf[n] >= 0 || !domain[n].get(s)) continue;
            if (conflicting.get(n) || !fits(n, s)) {
                domain[n].clear(s);
                record(n, s);
                if (domain[n].isEmpty()) return false;
            }
        }
        return true;
    }

    private void record(int meeting, int slot) {
        if (trailSize == trailMeeting.length) {
            trailMeeting = Arrays.copyOf(trailMeeting, trailSize * 2);
            trailSlot = Arrays.copyOf(trailSlot, trailSize * 2);
        }
        trailMeeting[trailSize] = meeting;
        trailSlot[trailSize] = slot;
        trailSize++;
    }

    private void undo(int mark) {
        while (trailSize > mark) {
            trailSize--;
            domain[trailMeeting[trailSize]].set(trailSlot[trailSize]);
        }
    }
}
