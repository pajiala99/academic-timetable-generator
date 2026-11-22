package edu.timetable.web;

import edu.timetable.model.TimetableRun;
import edu.timetable.service.TimetableService;
import edu.timetable.solver.TimetableEngine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs one timetable generation at a time in the background and keeps its progress, so the
 * browser can poll it: stage, progress, log lines and the annealing cost curve.
 */
final class GenerationJob {
    private static final int MAX_TRACE = 400;

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "slotwise-generator");
        t.setDaemon(true);
        return t;
    });

    private boolean running;
    private volatile boolean cancelRequested;
    private String stage = "Ready.";
    private double progress;
    private final List<String> log = new ArrayList<>();
    private final List<double[]> trace = new ArrayList<>();
    private String outcome;
    private String outcomeText;
    private Integer runId;
    private String summary;
    private List<String> reasons = List.of();
    private String error;

    /** Starts a generation unless one is already running. */
    synchronized boolean start(TimetableService service, TimetableEngine.Options options, String label) {
        if (running) return false;
        running = true;
        cancelRequested = false;
        stage = "Starting...";
        progress = -1;
        log.clear();
        trace.clear();
        outcome = outcomeText = summary = error = null;
        runId = null;
        reasons = List.of();
        worker.submit(() -> run(service, options, label));
        return true;
    }

    void cancel() {
        cancelRequested = true;
    }

    private void run(TimetableService service, TimetableEngine.Options options, String label) {
        try {
            TimetableEngine.Result result = service.generate(options, new TimetableEngine.Listener() {
                @Override
                public void log(String line) {
                    synchronized (GenerationJob.this) {
                        log.add(line);
                    }
                }

                @Override
                public void stage(String name) {
                    synchronized (GenerationJob.this) {
                        stage = name + "...";
                        progress = -1;
                    }
                }

                @Override
                public void annealProgress(long iteration, long total, double current, double best) {
                    synchronized (GenerationJob.this) {
                        progress = total == 0 ? 1 : (double) iteration / total;
                        if (trace.size() < MAX_TRACE) trace.add(new double[]{iteration, current, best});
                    }
                }
            }, () -> cancelRequested);
            TimetableRun saved = result.success() && !cancelRequested ? service.save(result, label) : null;
            synchronized (this) {
                outcome = cancelRequested ? "CANCELLED" : result.outcome().name();
                outcomeText = cancelRequested ? "Cancelled. Nothing was saved." : result.outcome().text() + ".";
                if (saved != null) {
                    runId = saved.id();
                    summary = "Saved as run #" + saved.id() + ". " + result.breakdown().describe();
                } else if (result.precheck() != null && result.precheck().provenInfeasible()) {
                    reasons = result.precheck().reasons();
                    summary = "No clash-free timetable can exist.";
                } else if (!result.log().isEmpty()) {
                    summary = result.log().get(result.log().size() - 1);
                }
            }
        } catch (RuntimeException e) {
            synchronized (this) {
                outcome = "ERROR";
                outcomeText = "Generation failed.";
                error = e.getMessage() == null ? e.toString() : e.getMessage();
            }
        } finally {
            synchronized (this) {
                running = false;
                progress = 1;
                stage = outcomeText == null ? "Done." : outcomeText;
            }
        }
    }

    synchronized Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("running", running);
        out.put("stage", stage);
        out.put("progress", progress);
        out.put("log", List.copyOf(log));
        out.put("trace", List.copyOf(trace));
        out.put("outcome", outcome);
        out.put("outcomeText", outcomeText);
        out.put("runId", runId);
        out.put("summary", summary);
        out.put("reasons", reasons);
        out.put("error", error);
        return out;
    }
}
