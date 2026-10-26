package edu.timetable.solver;

import edu.timetable.model.Dataset;
import edu.timetable.model.TimetableEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * The full pipeline: compile the dataset, run the infeasibility pre-checks, find a clash-free
 * timetable by backtracking, improve it by simulated annealing, and audit the result with the
 * independent {@link HardConstraintValidator}.
 */
public final class TimetableEngine {

    public record Options(long cliqueTimeLimitMs, BacktrackingSolver.Options search,
                          SimulatedAnnealer.Options anneal, SoftConstraintEvaluator.Weights weights) {
        public static Options defaults() {
            return new Options(2_000, BacktrackingSolver.Options.defaults(),
                    SimulatedAnnealer.Options.defaults(), SoftConstraintEvaluator.Weights.defaults());
        }
    }

    public interface Listener {
        void log(String line);

        default void stage(String name) {
        }

        default void annealProgress(long iteration, long total, double current, double best) {
        }
    }

    public enum Outcome {
        SUCCESS("Timetable generated"),
        INFEASIBLE_PRECHECK("Proven infeasible before search"),
        INFEASIBLE_SEARCH("Proven infeasible by exhaustive search"),
        TIMEOUT("Search time limit reached"),
        CANCELLED("Cancelled");

        private final String text;

        Outcome(String text) {
            this.text = text;
        }

        public String text() {
            return text;
        }
    }

    public record Result(Outcome outcome, Problem problem, FeasibilityReport precheck,
                         BacktrackingSolver.Result search, SimulatedAnnealer.Result anneal,
                         Schedule schedule, SoftConstraintEvaluator.Breakdown breakdown, List<String> log) {
        public boolean success() {
            return outcome == Outcome.SUCCESS;
        }

        public List<TimetableEntry> entries() {
            return schedule == null ? List.of() : problem.toEntries(schedule);
        }
    }

    private TimetableEngine() {
    }

    public static Result run(Dataset data, Options options, Listener listener, BooleanSupplier cancelled) {
        List<String> log = new ArrayList<>();
        Listener out = new Listener() {
            @Override
            public void log(String line) {
                log.add(line);
                if (listener != null) listener.log(line);
            }

            @Override
            public void stage(String name) {
                if (listener != null) listener.stage(name);
            }

            @Override
            public void annealProgress(long iteration, long total, double current, double best) {
                if (listener != null) listener.annealProgress(iteration, total, current, best);
            }
        };

        out.stage("Building conflict graph");
        Problem problem = new Problem(data);
        out.log(String.format("Dataset: %d courses -> %d weekly meetings, %d batches, %d faculty, %d rooms, %d x %d week.",
                data.courses().size(), problem.meetingCount(), data.batches().size(), data.faculty().size(),
                data.rooms().size(), data.config().days(), data.config().periodsPerDay()));

        out.stage("Checking feasibility");
        FeasibilityReport report = FeasibilityChecker.check(problem, options.cliqueTimeLimitMs());
        report.notes().forEach(out::log);
        if (report.provenInfeasible()) {
            out.log("INFEASIBLE - no clash-free timetable can exist:");
            report.reasons().forEach(r -> out.log("  * " + r));
            return new Result(Outcome.INFEASIBLE_PRECHECK, problem, report, null, null, null, null, log);
        }

        out.stage("Searching for a clash-free timetable");
        BacktrackingSolver.Result search = new BacktrackingSolver(problem, options.search(), cancelled).solve();
        out.log(String.format("Backtracking: %s after %d nodes, %d backtracks, %d restarts, %d ms.",
                search.status(), search.nodes(), search.backtracks(), search.restarts(), search.millis()));
        switch (search.status()) {
            case INFEASIBLE -> {
                out.log("INFEASIBLE - the search space was exhausted: the hard constraints cannot all be met. "
                        + "Relax faculty availability, add rooms or slots, or reduce meetings.");
                return new Result(Outcome.INFEASIBLE_SEARCH, problem, report, search, null, null, null, log);
            }
            case TIMEOUT -> {
                out.log("UNDECIDED - the time limit was reached before a timetable was found or ruled out. "
                        + "Try a longer limit or relax the constraints.");
                return new Result(Outcome.TIMEOUT, problem, report, search, null, null, null, log);
            }
            case CANCELLED -> {
                out.log("Cancelled during search.");
                return new Result(Outcome.CANCELLED, problem, report, search, null, null, null, log);
            }
            case SOLVED -> {
            }
        }
        audit(problem, search.schedule(), "backtracking");

        SoftConstraintEvaluator evaluator = new SoftConstraintEvaluator(problem, options.weights());
        Schedule schedule = search.schedule();
        SimulatedAnnealer.Result anneal = null;
        if (options.anneal().iterations() > 0) {
            out.stage("Improving soft constraints (simulated annealing)");
            out.log("Initial soft cost: " + evaluator.breakdown(schedule).describe());
            anneal = new SimulatedAnnealer(problem, evaluator, options.anneal())
                    .improve(schedule, (it, total, cur, best, temperature) -> out.annealProgress(it, total, cur, best), cancelled);
            schedule = anneal.best();
            audit(problem, schedule, "annealing");
            out.log(String.format("Annealing: %d iterations, %d accepted, %d improving, %d rejected as infeasible, T0 = %.3f, %d ms%s.",
                    anneal.iterations(), anneal.accepted(), anneal.improvements(), anneal.rejectedInfeasible(),
                    anneal.initialTemperature(), anneal.millis(), anneal.cancelled() ? " (stopped early)" : ""));
        }
        SoftConstraintEvaluator.Breakdown breakdown = evaluator.breakdown(schedule);
        out.log("Final soft cost:   " + breakdown.describe());
        out.log("Hard constraints verified independently: no clashes.");
        out.stage("Done");
        return new Result(Outcome.SUCCESS, problem, report, search, anneal, schedule, breakdown, log);
    }

    private static void audit(Problem problem, Schedule schedule, String phase) {
        List<String> violations = HardConstraintValidator.violations(problem, schedule);
        if (!violations.isEmpty()) {
            throw new IllegalStateException("Internal error: " + phase + " produced an invalid timetable: " + violations);
        }
    }
}
