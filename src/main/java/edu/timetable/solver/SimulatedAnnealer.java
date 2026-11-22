package edu.timetable.solver;

import edu.timetable.model.Faculty;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.BooleanSupplier;

/**
 * Improves a feasible timetable against the soft-constraint objective by simulated annealing.
 *
 * <p>Two move types are used: <i>relocate</i> (move one meeting to another allowed start and a
 * free suitable room) and <i>swap</i> (exchange the starts and rooms of two meetings of the
 * same length, usually sharing a student group). Every move is checked against occupancy
 * tables for student groups, teachers and rooms over all periods of the meetings involved,
 * against the meetings' allowed starts and rooms, and against teachers' daily limits. A move
 * that would break any hard constraint is rejected outright, so the schedule stays feasible at
 * every step. Feasible moves are accepted by the Metropolis rule under a geometric cooling
 * schedule, and the best schedule ever seen is returned.
 */
public final class SimulatedAnnealer {

    /**
     * @param initialTemperature {@code <= 0} calibrates it from sampled moves
     * @param finalTemperature   {@code <= 0} uses a thousandth of the initial temperature
     * @param swapProbability    chance of proposing a swap rather than a relocation
     */
    public record Options(long iterations, double initialTemperature, double finalTemperature,
                          double swapProbability, long seed) {
        public static Options defaults() {
            return new Options(1_000_000, 0, 0, 0.5, 7);
        }
    }

    public interface Listener {
        void onProgress(long iteration, long totalIterations, double current, double best, double temperature);
    }

    /** {@code trace} rows are {iteration, current cost, best cost}. */
    public record Result(Schedule best, SoftConstraintEvaluator.Breakdown initial,
                         SoftConstraintEvaluator.Breakdown improved, long iterations, long accepted,
                         long improvements, long rejectedInfeasible, double initialTemperature,
                         List<double[]> trace, long millis, boolean cancelled) {
    }

    private static final int TRACE_POINTS = 300;
    private static final double EPS = 1e-9;

    private final Problem p;
    private final SoftConstraintEvaluator evaluator;
    private final Options options;
    private final int periods;
    private final boolean[][] allowedStart;
    private final boolean[][] suitableRoom;
    private final int[] dailyLimit;
    private final int[][] groupAt;
    private final int[][] facultyAt;
    private final int[][] roomAt;
    private final int[][] facultyLoad;
    private Schedule current;
    private Random random;

    public SimulatedAnnealer(Problem problem, SoftConstraintEvaluator evaluator, Options options) {
        this.p = problem;
        this.evaluator = evaluator;
        this.options = options;
        int slots = problem.slotCount();
        this.periods = problem.config().periodsPerDay();
        int m = problem.meetingCount();
        this.allowedStart = new boolean[m][slots];
        this.suitableRoom = new boolean[m][problem.roomCount()];
        for (int i = 0; i < m; i++) {
            for (int s : problem.allowedStarts(i)) allowedStart[i][s] = true;
            for (int r : problem.suitableRooms(i)) suitableRoom[i][r] = true;
        }
        this.dailyLimit = new int[problem.faculty().length];
        for (int f = 0; f < dailyLimit.length; f++) {
            Faculty fac = problem.faculty()[f];
            dailyLimit[f] = fac.hasDailyLimit() ? fac.maxPerDay() : Integer.MAX_VALUE;
        }
        this.groupAt = new int[problem.groups().length][slots];
        this.facultyAt = new int[problem.faculty().length][slots];
        this.roomAt = new int[problem.roomCount()][slots];
        this.facultyLoad = new int[problem.faculty().length][problem.config().days()];
    }

    public Result improve(Schedule start, Listener listener, BooleanSupplier cancelled) {
        long startTime = System.currentTimeMillis();
        if (!start.isComplete()) throw new IllegalArgumentException("Annealing needs a complete schedule");
        random = new Random(options.seed());
        current = start.copy();
        buildOccupancy();

        SoftConstraintEvaluator.Breakdown initial = evaluator.breakdown(current);
        double cost = initial.total();
        double bestCost = cost;
        Schedule best = current.copy();
        List<double[]> trace = new ArrayList<>();
        long iterations = Math.max(0, options.iterations());
        if (p.meetingCount() == 0 || iterations == 0) {
            return new Result(best, initial, initial, 0, 0, 0, 0, 0, trace, 0, false);
        }

        double t0 = options.initialTemperature() > 0 ? options.initialTemperature() : calibrateTemperature();
        double tEnd = options.finalTemperature() > 0 ? Math.min(options.finalTemperature(), t0) : t0 * 1e-3;
        double alpha = Math.pow(tEnd / t0, 1.0 / iterations);
        long traceEvery = Math.max(1, iterations / TRACE_POINTS);

        long accepted = 0, improvements = 0, infeasible = 0, done = 0;
        boolean wasCancelled = false;
        double temperature = t0;
        for (long it = 0; it < iterations; it++, temperature *= alpha) {
            if ((it & 1023) == 0 && cancelled != null && cancelled.getAsBoolean()) {
                wasCancelled = true;
                break;
            }
            done++;
            Move move = random.nextDouble() < options.swapProbability() ? proposeSwap() : proposeRelocation();
            if (move == null) {
                infeasible++;
            } else if (move != Move.NONE) {
                double delta = apply(move);
                if (delta <= 0 || random.nextDouble() < Math.exp(-delta / temperature)) {
                    accepted++;
                    cost += delta;
                    if (delta < -EPS) improvements++;
                    if (cost < bestCost - EPS) {
                        bestCost = cost;
                        best = current.copy();
                    }
                } else {
                    revert(move);
                }
            }
            if (it % 20_000 == 0) cost = evaluator.total(current); // clear floating-point drift
            if (it % traceEvery == 0 || it == iterations - 1) {
                trace.add(new double[]{it, cost, bestCost});
                if (listener != null) listener.onProgress(it, iterations, cost, bestCost, temperature);
            }
        }
        SoftConstraintEvaluator.Breakdown improved = evaluator.breakdown(best);
        return new Result(best, initial, improved, done, accepted, improvements, infeasible, t0, trace,
                System.currentTimeMillis() - startTime, wasCancelled);
    }

    /** Sets the start temperature so that an average uphill move is accepted half of the time. */
    private double calibrateTemperature() {
        double sum = 0;
        int uphill = 0;
        for (int i = 0; i < 400; i++) {
            Move move = random.nextBoolean() ? proposeSwap() : proposeRelocation();
            if (move == null || move == Move.NONE) continue;
            double delta = apply(move);
            revert(move);
            if (delta > EPS) {
                sum += delta;
                uphill++;
            }
        }
        return uphill == 0 ? 1.0 : (sum / uphill) / Math.log(2);
    }

    // ---- moves ----------------------------------------------------------------------------

    /** a moves to (sa, ra); b (if present) moves to (sb, rb). The old positions are kept for revert. */
    private record Move(int a, int sa, int ra, int oldSa, int oldRa, int b, int sb, int rb, int oldSb, int oldRb) {
        static final Move NONE = new Move(-1, -1, -1, -1, -1, -1, -1, -1, -1, -1);
    }

    /** Moves one meeting to a random allowed start, into a free suitable room there. */
    private Move proposeRelocation() {
        int m = random.nextInt(p.meetingCount());
        int[] starts = p.allowedStarts(m);
        int s = starts[random.nextInt(starts.length)];
        int oldS = current.slotOf(m), oldR = current.roomOf(m);
        int[] rooms = p.suitableRooms(m);
        int offset = random.nextInt(rooms.length);
        lift(m);
        try {
            if (!teachable(m, s)) return null;
            for (int k = 0; k < rooms.length; k++) {
                int r = rooms[(offset + k) % rooms.length];
                if (s == oldS && r == oldR) continue;
                if (roomFree(m, s, r)) return new Move(m, s, r, oldS, oldR, -1, -1, -1, -1, -1);
            }
            return s == oldS ? Move.NONE : null;
        } finally {
            put(m, oldS, oldR);
        }
    }

    private Move proposeSwap() {
        int a = random.nextInt(p.meetingCount());
        int b;
        if (random.nextDouble() < 0.7) {
            int[] attending = p.groupsOf(a);
            int[] sameGroup = p.meetingsOfGroup(attending[random.nextInt(attending.length)]);
            b = sameGroup[random.nextInt(sameGroup.length)];
        } else {
            b = random.nextInt(p.meetingCount());
        }
        // Meetings of one course are interchangeable, so swapping two of them changes nothing.
        if (a == b || p.courseOf(a) == p.courseOf(b)) return Move.NONE;
        if (p.durationOf(a) != p.durationOf(b)) return null;
        int sa = current.slotOf(a), ra = current.roomOf(a);
        int sb = current.slotOf(b), rb = current.roomOf(b);
        if (!allowedStart[a][sb] || !allowedStart[b][sa] || !suitableRoom[a][rb] || !suitableRoom[b][ra]) return null;
        lift(a);
        lift(b);
        boolean ok = false;
        if (teachable(a, sb) && roomFree(a, sb, rb)) {
            put(a, sb, rb);
            ok = teachable(b, sa) && roomFree(b, sa, ra);
            lift(a);
        }
        put(a, sa, ra);
        put(b, sb, rb);
        return ok ? new Move(a, sb, rb, sa, ra, b, sa, ra, sb, rb) : null;
    }

    /** With m lifted out: are m's student groups and teacher free for all its periods from s, within the daily limit? */
    private boolean teachable(int m, int s) {
        int f = p.facultyOf(m);
        if (facultyLoad[f][s / periods] + p.durationOf(m) > dailyLimit[f]) return false;
        for (int t = s; t < s + p.durationOf(m); t++) {
            if (facultyAt[f][t] >= 0) return false;
            for (int g : p.groupsOf(m)) if (groupAt[g][t] >= 0) return false;
        }
        return true;
    }

    private boolean roomFree(int m, int s, int r) {
        for (int t = s; t < s + p.durationOf(m); t++) if (roomAt[r][t] >= 0) return false;
        return true;
    }

    private double apply(Move mv) {
        double before = evaluator.localCost(current, mv.a(), mv.b());
        lift(mv.a());
        if (mv.b() >= 0) lift(mv.b());
        put(mv.a(), mv.sa(), mv.ra());
        if (mv.b() >= 0) put(mv.b(), mv.sb(), mv.rb());
        return evaluator.localCost(current, mv.a(), mv.b()) - before;
    }

    private void revert(Move mv) {
        lift(mv.a());
        if (mv.b() >= 0) lift(mv.b());
        put(mv.a(), mv.oldSa(), mv.oldRa());
        if (mv.b() >= 0) put(mv.b(), mv.oldSb(), mv.oldRb());
    }

    // ---- occupancy ------------------------------------------------------------------------

    private void buildOccupancy() {
        for (int[] row : groupAt) Arrays.fill(row, -1);
        for (int[] row : facultyAt) Arrays.fill(row, -1);
        for (int[] row : roomAt) Arrays.fill(row, -1);
        for (int[] row : facultyLoad) Arrays.fill(row, 0);
        for (int m = 0; m < p.meetingCount(); m++) put(m, current.slotOf(m), current.roomOf(m));
    }

    private void lift(int m) {
        mark(m, current.slotOf(m), current.roomOf(m), -1);
        facultyLoad[p.facultyOf(m)][current.slotOf(m) / periods] -= p.durationOf(m);
        current.assign(m, -1, -1);
    }

    private void put(int m, int s, int r) {
        mark(m, s, r, m);
        facultyLoad[p.facultyOf(m)][s / periods] += p.durationOf(m);
        current.assign(m, s, r);
    }

    private void mark(int m, int s, int r, int value) {
        int f = p.facultyOf(m);
        for (int t = s; t < s + p.durationOf(m); t++) {
            for (int g : p.groupsOf(m)) groupAt[g][t] = value;
            facultyAt[f][t] = value;
            roomAt[r][t] = value;
        }
    }
}
