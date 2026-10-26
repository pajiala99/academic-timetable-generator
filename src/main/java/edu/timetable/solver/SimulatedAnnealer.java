package edu.timetable.solver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.BooleanSupplier;

/**
 * Improves a feasible timetable against the soft-constraint objective by simulated annealing.
 *
 * <p>Two move types are used: <i>relocate</i> (move one meeting to another allowed slot and
 * room) and <i>swap</i> (exchange the slots and rooms of two meetings, usually of the same
 * batch). Before a move is applied it is checked against occupancy tables for batches, faculty
 * and rooms, and against the meetings' base domains; any move that would break a hard
 * constraint is rejected outright, so the schedule stays feasible at every step. Feasible moves
 * are accepted by the Metropolis rule under a geometric cooling schedule, and the best schedule
 * ever seen is returned.
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
    private final int[][] batchAt;
    private final int[][] facultyAt;
    private final int[][] roomAt;
    private Schedule current;
    private Random random;

    public SimulatedAnnealer(Problem problem, SoftConstraintEvaluator evaluator, Options options) {
        this.p = problem;
        this.evaluator = evaluator;
        this.options = options;
        int slots = problem.slotCount();
        this.batchAt = new int[problem.batches().length][slots];
        this.facultyAt = new int[problem.faculty().length][slots];
        this.roomAt = new int[problem.roomCount()][slots];
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

    /** Moves one meeting to a random allowed slot, into a free suitable room there. */
    private Move proposeRelocation() {
        int m = random.nextInt(p.meetingCount());
        int[] slots = p.allowedSlots(m);
        int s = slots[random.nextInt(slots.length)];
        if (!teachable(m, s, m, -1)) return null;
        int oldS = current.slotOf(m), oldR = current.roomOf(m);
        int[] rooms = p.suitableRooms(m);
        int offset = random.nextInt(rooms.length);
        for (int k = 0; k < rooms.length; k++) {
            int r = rooms[(offset + k) % rooms.length];
            if (s == oldS && r == oldR) continue;
            if (vacant(roomAt[r][s], m, -1)) return new Move(m, s, r, oldS, oldR, -1, -1, -1, -1, -1);
        }
        return s == oldS ? Move.NONE : null;
    }

    private Move proposeSwap() {
        int a = random.nextInt(p.meetingCount());
        int b;
        if (random.nextDouble() < 0.7) {
            int[] attending = p.batchesOf(a);
            int[] sameBatch = p.meetingsOfBatch(attending[random.nextInt(attending.length)]);
            b = sameBatch[random.nextInt(sameBatch.length)];
        } else {
            b = random.nextInt(p.meetingCount());
        }
        // Meetings of one course are interchangeable, so swapping two of them changes nothing.
        if (a == b || p.courseOf(a) == p.courseOf(b)) return Move.NONE;
        int sa = current.slotOf(a), ra = current.roomOf(a);
        int sb = current.slotOf(b), rb = current.roomOf(b);
        if (!p.baseDomain(a).get(p.pair(sb, rb)) || !p.baseDomain(b).get(p.pair(sa, ra))) return null;
        if (!free(a, sb, rb, a, b) || !free(b, sa, ra, a, b)) return null;
        return new Move(a, sb, rb, sa, ra, b, sa, ra, sb, rb);
    }

    /** Would meeting m fit at (s, r) once meetings x and y are lifted out of the timetable? */
    private boolean free(int m, int s, int r, int x, int y) {
        return teachable(m, s, x, y) && vacant(roomAt[r][s], x, y);
    }

    /** Are m's batches and teacher free in slot s, ignoring meetings x and y? */
    private boolean teachable(int m, int s, int x, int y) {
        for (int b : p.batchesOf(m)) if (!vacant(batchAt[b][s], x, y)) return false;
        return vacant(facultyAt[p.facultyOf(m)][s], x, y);
    }

    private static boolean vacant(int occupant, int x, int y) {
        return occupant < 0 || occupant == x || occupant == y;
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
        for (int[] row : batchAt) Arrays.fill(row, -1);
        for (int[] row : facultyAt) Arrays.fill(row, -1);
        for (int[] row : roomAt) Arrays.fill(row, -1);
        for (int m = 0; m < p.meetingCount(); m++) put(m, current.slotOf(m), current.roomOf(m));
    }

    private void lift(int m) {
        int s = current.slotOf(m), r = current.roomOf(m);
        for (int b : p.batchesOf(m)) batchAt[b][s] = -1;
        facultyAt[p.facultyOf(m)][s] = -1;
        roomAt[r][s] = -1;
        current.assign(m, -1, -1);
    }

    private void put(int m, int s, int r) {
        for (int b : p.batchesOf(m)) batchAt[b][s] = m;
        facultyAt[p.facultyOf(m)][s] = m;
        roomAt[r][s] = m;
        current.assign(m, s, r);
    }
}
