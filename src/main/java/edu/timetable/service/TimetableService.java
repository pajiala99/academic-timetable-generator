package edu.timetable.service;

import edu.timetable.dao.DataStore;
import edu.timetable.model.Dataset;
import edu.timetable.model.TimetableRun;
import edu.timetable.solver.HardConstraintValidator;
import edu.timetable.solver.Problem;
import edu.timetable.solver.Schedule;
import edu.timetable.solver.SoftConstraintEvaluator;
import edu.timetable.solver.TimetableEngine;

import java.util.List;
import java.util.function.BooleanSupplier;

/** Application use cases, tying the persistence layer to the timetable engine. */
public final class TimetableService {

    /**
     * A saved timetable re-checked against the current data, which may have changed since it
     * was generated.
     */
    public record Evaluation(List<String> hardViolations, SoftConstraintEvaluator.Breakdown breakdown,
                             int placedMeetings, int totalMeetings) {
        public boolean valid() {
            return hardViolations.isEmpty();
        }
    }

    private final DataStore store;

    public TimetableService(DataStore store) {
        this.store = store;
    }

    public DataStore store() {
        return store;
    }

    public Dataset loadDataset() {
        return store.loadDataset();
    }

    public TimetableEngine.Result generate(TimetableEngine.Options options, TimetableEngine.Listener listener,
                                           BooleanSupplier cancelled) {
        return TimetableEngine.run(loadDataset(), options, listener, cancelled);
    }

    /** Persists a successful run together with its log. */
    public TimetableRun save(TimetableEngine.Result result, String label) {
        if (!result.success()) throw new IllegalArgumentException("Only successful runs can be saved");
        return store.timetables().save(label, result.breakdown().total(), String.join("\n", result.log()), result.entries());
    }

    public Evaluation evaluate(Dataset data, TimetableRun run, SoftConstraintEvaluator.Weights weights) {
        Problem problem = new Problem(data);
        Schedule schedule = problem.fromEntries(run.entries());
        int placed = 0;
        for (int m = 0; m < problem.meetingCount(); m++) if (schedule.slotOf(m) >= 0) placed++;
        List<String> violations = HardConstraintValidator.violations(problem, schedule);
        SoftConstraintEvaluator.Breakdown breakdown = new SoftConstraintEvaluator(problem, weights).breakdown(schedule);
        return new Evaluation(violations, breakdown, placed, problem.meetingCount());
    }

    /** Replaces everything in the database with the sample department. */
    public void replaceWithSampleData() {
        store.clearAll();
        store.importDataset(SampleData.department());
    }
}
