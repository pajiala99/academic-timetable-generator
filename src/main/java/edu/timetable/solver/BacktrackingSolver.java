package edu.timetable.solver;

import edu.timetable.model.Faculty;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Random;
import java.util.function.BooleanSupplier;

/**
 * Complete backtracking search for a timetable that satisfies every hard constraint.
 *
 * <p>The search assigns each meeting a start slot (its colour). Rooms are handled per slot:
 * <ul>
 *   <li>single-period meetings are not branched on rooms; every slot keeps a bipartite matching
 *       between its meetings and suitable rooms, so a set of meetings can share a slot exactly
 *       when the matching covers them all;</li>
 *   <li>a multi-period meeting (a double-period lab) must keep one room for all its periods, so
 *       the search branches on that room and pins it; matchings may move single-period
 *       meetings around a pinned room, never the pinned meeting itself.</li>
 * </ul>
 *
 * <ul>
 *   <li><b>Variable ordering:</b> minimum remaining values (fewest start slots left). Ties go
 *       to the highest saturation degree (distinct slots already occupied by its assigned
 *       neighbours, as in DSatur), then to the most unassigned neighbours, then to the fewest
 *       suitable rooms, then randomly.</li>
 *   <li><b>Value ordering:</b> least-constraining start first (the one that the fewest
 *       unassigned neighbours could still use), then the one with the most free rooms.</li>
 *   <li><b>Forward checking:</b> placing a meeting removes, from every other meeting, the
 *       starts that would overlap it while sharing a student group or teacher, the starts whose
 *       rooms can no longer be found, and the starts that would push the teacher past their
 *       daily limit. A domain wipe-out triggers an immediate backtrack.</li>
 *   <li><b>Restarts:</b> runs are cut off after a node budget that grows geometrically, with
 *       fresh random tie-breaking each time. A run that exhausts its search tree within budget
 *       proves the instance infeasible.</li>
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
    private final int periods;
    private final int[] duration;
    private final int[] faculty;
    private final int[] dailyLimit;

    // Search state.
    private BitSet[] domain;
    private int[] startOf;
    private int[] roomOf;
    private int[][] holder;          // holder[slot][room] = meeting using that room, or -1
    private int[] freeRooms;         // per slot
    private int[][] facultyLoad;     // [faculty][day] periods taught
    private int[][] neighbourSlotUse;
    private int[] saturation;
    private int[] freeDegree;
    private int unassigned;

    // Trail of removed (meeting, start) domain values, undone on backtrack.
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
        this.periods = problem.config().periodsPerDay();
        this.duration = new int[meetingCount];
        this.faculty = new int[meetingCount];
        for (int m = 0; m < meetingCount; m++) {
            duration[m] = problem.durationOf(m);
            faculty[m] = problem.facultyOf(m);
        }
        this.dailyLimit = new int[problem.faculty().length];
        for (int f = 0; f < dailyLimit.length; f++) {
            Faculty fac = problem.faculty()[f];
            dailyLimit[f] = fac.hasDailyLimit() ? fac.maxPerDay() : Integer.MAX_VALUE;
        }
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
                for (int m = 0; m < meetingCount; m++) s.assign(m, startOf[m], roomOf[m]);
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
            if (p.suitableRooms(m).length == 0) continue;
            for (int s : p.allowedStarts(m)) if (duration[m] <= dailyLimit[faculty[m]]) domain[m].set(s);
        }
        startOf = new int[meetingCount];
        roomOf = new int[meetingCount];
        Arrays.fill(startOf, -1);
        Arrays.fill(roomOf, -1);
        holder = new int[slotCount][roomCount];
        for (int[] row : holder) Arrays.fill(row, -1);
        freeRooms = new int[slotCount];
        Arrays.fill(freeRooms, roomCount);
        facultyLoad = new int[p.faculty().length][p.config().days()];
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
        boolean pinned = duration[m] > 1;
        for (int s : orderStarts(m)) {
            // Single-period meetings get their room from the slot's matching; longer ones try each room.
            int[] roomChoices = pinned ? p.suitableRooms(m) : new int[]{-1};
            for (int r : roomChoices) {
                if (pinned && !claimable(m, s, r)) continue;
                int mark = trailSize;
                if (place(m, s, r)) {
                    if (forwardCheck(m, s)) {
                        int outcome = search();
                        if (outcome != EXHAUSTED) return outcome;
                    }
                    undo(mark);
                    unplace(m);
                }
                backtracks++;
            }
        }
        return EXHAUSTED;
    }

    private int selectMeeting() {
        int best = -1;
        int ties = 0;
        for (int m = 0; m < meetingCount; m++) {
            if (startOf[m] >= 0) continue;
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
        if (cmp == 0) cmp = Integer.compare(p.suitableRooms(b).length, p.suitableRooms(a).length);
        return cmp;
    }

    private int[] orderStarts(int m) {
        int[] starts = domain[m].stream().toArray();
        long[] keys = new long[starts.length];
        for (int i = 0; i < starts.length; i++) {
            int s = starts[i];
            int impact = 0;
            for (int n : p.neighbours(m)) if (startOf[n] < 0 && anyOverlappingStart(n, m, s)) impact++;
            int busyRooms = 0;
            for (int t = s; t < s + duration[m]; t++) busyRooms += roomCount - freeRooms[t];
            // Lower key first: fewer neighbours affected, then more free rooms, then random.
            keys[i] = ((long) impact << 40) | ((long) Math.min(busyRooms, 0xFFFFF) << 20) | random.nextInt(1 << 20);
        }
        Integer[] idx = new Integer[starts.length];
        for (int i = 0; i < idx.length; i++) idx[i] = i;
        Arrays.sort(idx, (x, y) -> Long.compare(keys[x], keys[y]));
        int[] out = new int[starts.length];
        for (int i = 0; i < out.length; i++) out[i] = starts[idx[i]];
        return out;
    }

    /** Does meeting n still have a start that overlaps meeting m placed at s? */
    private boolean anyOverlappingStart(int n, int m, int s) {
        int first = domain[n].nextSetBit(Math.max(0, s - duration[n] + 1));
        return first >= 0 && first < s + duration[m];
    }

    // ---- placing meetings and the per-slot room matching ------------------------------------

    /** Puts m into its periods from s; r is the room for a multi-period meeting, -1 to match one. */
    private boolean place(int m, int s, int r) {
        if (r < 0) {
            stamp++;
            if (!augment(m, s, true)) return false;
        } else {
            for (int t = s; t < s + duration[m]; t++) {
                int occupant = holder[t][r];
                if (occupant >= 0) {
                    // Re-seat the single-period occupant elsewhere in this slot; claimable() said it can be.
                    stamp++;
                    visitStamp[r] = stamp;
                    holder[t][r] = -1;
                    if (!augment(occupant, t, true)) throw new IllegalStateException("room claim failed after check");
                }
                holder[t][r] = m;
            }
            roomOf[m] = r;
        }
        startOf[m] = s;
        unassigned--;
        facultyLoad[faculty[m]][s / periods] += duration[m];
        for (int t = s; t < s + duration[m]; t++) {
            freeRooms[t]--;
            for (int n : p.neighbours(m)) if (neighbourSlotUse[n][t]++ == 0) saturation[n]++;
        }
        for (int n : p.neighbours(m)) freeDegree[n]--;
        return true;
    }

    private void unplace(int m) {
        int s = startOf[m];
        for (int t = s; t < s + duration[m]; t++) {
            freeRooms[t]++;
            for (int n : p.neighbours(m)) if (--neighbourSlotUse[n][t] == 0) saturation[n]--;
            holder[t][roomOf[m]] = -1;
        }
        for (int n : p.neighbours(m)) freeDegree[n]++;
        facultyLoad[faculty[m]][s / periods] -= duration[m];
        roomOf[m] = -1;
        startOf[m] = -1;
        unassigned++;
    }

    /** Could a multi-period meeting take room r for all its periods from s? Changes nothing. */
    private boolean claimable(int m, int s, int r) {
        for (int t = s; t < s + duration[m]; t++) {
            int occupant = holder[t][r];
            if (occupant < 0) continue;
            if (duration[occupant] > 1) return false;
            stamp++;
            visitStamp[r] = stamp;
            if (!augment(occupant, t, false)) return false;
        }
        return true;
    }

    /** Could meeting n start at s, as far as rooms go? Changes nothing. */
    private boolean fits(int n, int s) {
        if (duration[n] == 1) {
            if (freeRooms[s] == 0) return false;
            for (int r : p.suitableRooms(n)) if (holder[s][r] < 0) return true;
            stamp++;
            return augment(n, s, false);
        }
        for (int t = s; t < s + duration[n]; t++) if (freeRooms[t] == 0) return false;
        for (int r : p.suitableRooms(n)) if (claimable(n, s, r)) return true;
        return false;
    }

    /**
     * Augmenting-path search in slot s: find a room for single-period meeting m, moving other
     * single-period meetings of the slot to other suitable rooms if needed. Pinned (multi-period)
     * meetings never move. With {@code commit} the matching is updated.
     */
    private boolean augment(int m, int s, boolean commit) {
        for (int r : p.suitableRooms(m)) {
            if (visitStamp[r] == stamp) continue;
            visitStamp[r] = stamp;
            int occupant = holder[s][r];
            if (occupant < 0 || (duration[occupant] == 1 && augment(occupant, s, commit))) {
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

    /** Prunes the other domains after placing m at s; returns false on a wipe-out. */
    private boolean forwardCheck(int m, int s) {
        BitSet conflicting = p.neighbourSet(m);
        int day = s / periods;
        int f = faculty[m];
        for (int n = 0; n < meetingCount; n++) {
            if (startOf[n] >= 0) continue;
            BitSet d = domain[n];
            boolean conflict = conflicting.get(n);
            // Starts of n whose periods overlap [s, s + duration m).
            int from = Math.max(0, s - duration[n] + 1), to = s + duration[m];
            for (int t = d.nextSetBit(from); t >= 0 && t < to; t = d.nextSetBit(t + 1)) {
                if (conflict || !fits(n, t)) remove(n, t);
            }
            // The teacher's daily limit, on the rest of the day.
            if (faculty[n] == f && dailyLimit[f] != Integer.MAX_VALUE && facultyLoad[f][day] + duration[n] > dailyLimit[f]) {
                for (int t = d.nextSetBit(day * periods); t >= 0 && t < (day + 1) * periods; t = d.nextSetBit(t + 1)) remove(n, t);
            }
            if (d.isEmpty()) return false;
        }
        return true;
    }

    private void remove(int meeting, int slot) {
        domain[meeting].clear(slot);
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
