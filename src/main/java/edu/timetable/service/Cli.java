package edu.timetable.service;

import edu.timetable.model.Dataset;
import edu.timetable.solver.BacktrackingSolver;
import edu.timetable.solver.Problem;
import edu.timetable.solver.SimulatedAnnealer;
import edu.timetable.solver.SoftConstraintEvaluator;
import edu.timetable.solver.TimetableEngine;

import java.util.List;

/**
 * Headless demo of the engine, no database required.
 *
 * <pre>
 *   mvn -q compile exec:java                                   # sample department
 *   mvn -q compile exec:java -Dexec.args="--overloaded"        # provably infeasible input
 *   mvn -q compile exec:java -Dexec.args="--overloaded --no-precheck --time-limit 10"   # search alone
 *   mvn -q compile exec:java -Dexec.args="--synthetic 12 --load 30 --seed 3 --print none"
 * </pre>
 */
public final class Cli {
    private static final String USAGE = "Options: --overloaded | --synthetic BATCHES [--load MEETINGS_PER_BATCH] "
            + "[--room-use FRACTION], --seed S, --iterations N, --time-limit SECONDS, "
            + "--print batch|faculty|room|none, --no-precheck";

    private Cli() {
    }

    public static void main(String[] args) {
        boolean overloaded = false, precheck = true;
        int synthetic = 0, load = 28;
        double roomUse = 0.9;
        long seed = 42, iterations = 1_000_000, timeLimitMs = 30_000;
        String print = "batch";
        try {
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--overloaded" -> overloaded = true;
                    case "--synthetic" -> synthetic = Integer.parseInt(args[++i]);
                    case "--load" -> load = Integer.parseInt(args[++i]);
                    case "--room-use" -> roomUse = Double.parseDouble(args[++i]);
                    case "--seed" -> seed = Long.parseLong(args[++i]);
                    case "--iterations" -> iterations = Long.parseLong(args[++i]);
                    case "--time-limit" -> timeLimitMs = Long.parseLong(args[++i]) * 1000;
                    case "--print" -> print = args[++i].toLowerCase();
                    case "--no-precheck" -> precheck = false;
                    default -> throw new IllegalArgumentException("Unknown option " + args[i]);
                }
            }
            if (!List.of("batch", "faculty", "room", "none").contains(print)) {
                throw new IllegalArgumentException("Unknown --print value " + print);
            }
        } catch (RuntimeException e) {
            System.err.println(e instanceof ArrayIndexOutOfBoundsException ? "Missing value after the last option" : e.getMessage());
            System.err.println(USAGE);
            System.exit(2);
            return;
        }

        Dataset data = synthetic > 0 ? SampleData.synthetic(synthetic, load, roomUse, seed)
                : overloaded ? SampleData.overloaded() : SampleData.department();
        TimetableEngine.Options options = new TimetableEngine.Options(2_000,
                new BacktrackingSolver.Options(timeLimitMs, 1_000, 1.3, seed),
                new SimulatedAnnealer.Options(iterations, 0, 0, 0.5, seed),
                SoftConstraintEvaluator.Weights.defaults());

        if (!precheck) {
            // Search only, to show what the clique bound saves on infeasible inputs.
            BacktrackingSolver.Result search = new BacktrackingSolver(new Problem(data), options.search(), null).solve();
            System.out.printf("Backtracking without pre-checks: %s after %d nodes, %d backtracks, %d restarts, %d ms.%n",
                    search.status(), search.nodes(), search.backtracks(), search.restarts(), search.millis());
            return;
        }
        TimetableEngine.Result result = TimetableEngine.run(data, options, System.out::println, null);
        System.out.println();
        System.out.println("Outcome: " + result.outcome().text());
        if (!result.success()) System.exit(1);
        if (print.equals("none")) return;

        TimetableGrid.Perspective perspective = TimetableGrid.Perspective.valueOf(print.toUpperCase());
        for (TimetableGrid.Subject s : TimetableGrid.subjects(data, perspective)) {
            System.out.println();
            System.out.print(TimetableGrid.build(data, result.entries(), perspective, s).render());
        }
    }
}
