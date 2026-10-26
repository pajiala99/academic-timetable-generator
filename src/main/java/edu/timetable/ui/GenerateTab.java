package edu.timetable.ui;

import edu.timetable.model.SchedulingConfig;
import edu.timetable.model.TimetableRun;
import edu.timetable.service.TimetableService;
import edu.timetable.solver.BacktrackingSolver;
import edu.timetable.solver.SimulatedAnnealer;
import edu.timetable.solver.SoftConstraintEvaluator;
import edu.timetable.solver.TimetableEngine;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.Tab;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

/** Parameters, progress and log of a timetable generation run. */
final class GenerateTab extends Tab implements Refreshable {
    private record Outcome(TimetableEngine.Result result, TimetableRun saved) {
    }

    private final TimetableService service;
    private final Consumer<TimetableRun> onGenerated;

    private final Spinner<Integer> days = new Spinner<>(1, 7, 5);
    private final Spinner<Integer> periods = new Spinner<>(1, 12, 7);
    private final TextField timeLimit = Forms.field("30");
    private final TextField cliqueLimit = Forms.field("2");
    private final TextField iterations = Forms.field("1000000");
    private final TextField seed = Forms.field("42");
    private final TextField wIdle = Forms.field("3");
    private final TextField wLoad = Forms.field("1");
    private final TextField wSpacing = Forms.field("4");
    private final TextField wBuilding = Forms.field("2");
    private final TextField label = Forms.field("");

    private final Button generate = new Button("Generate timetable");
    private final Button cancel = new Button("Cancel");
    private final ProgressBar progress = new ProgressBar(0);
    private final Label stage = new Label("Ready.");
    private final Label summary = new Label();
    private final TextArea log = new TextArea();
    private final XYChart.Series<Number, Number> currentSeries = new XYChart.Series<>();
    private final XYChart.Series<Number, Number> bestSeries = new XYChart.Series<>();
    private Task<Outcome> running;

    GenerateTab(TimetableService service, Consumer<TimetableRun> onGenerated) {
        super("Generate");
        this.service = service;
        this.onGenerated = onGenerated;
        setClosable(false);

        GridPane form = Forms.grid();
        int r = 0;
        form.add(heading("Week"), 0, r++, 2, 1);
        Forms.row(form, r++, "Days per week", days);
        Forms.row(form, r++, "Periods per day", periods);
        form.add(heading("Search (hard constraints)"), 0, r++, 2, 1);
        Forms.row(form, r++, "Time limit (s)", timeLimit);
        Forms.row(form, r++, "Clique bound (s)", cliqueLimit);
        form.add(heading("Annealing (soft constraints)"), 0, r++, 2, 1);
        Forms.row(form, r++, "Iterations", iterations);
        Forms.row(form, r++, "Random seed", seed);
        form.add(heading("Soft-constraint weights"), 0, r++, 2, 1);
        Forms.row(form, r++, "Batch idle gap", wIdle);
        Forms.row(form, r++, "Faculty load spread", wLoad);
        Forms.row(form, r++, "Session spacing", wSpacing);
        Forms.row(form, r++, "Building change", wBuilding);
        form.add(heading("Save as"), 0, r++, 2, 1);
        Forms.row(form, r++, "Label", label);
        label.setPromptText("defaults to date and time");

        generate.getStyleClass().add("accent");
        generate.setDefaultButton(true);
        generate.setOnAction(e -> start());
        cancel.setDisable(true);
        cancel.setOnAction(e -> {
            if (running != null) running.cancel();
        });
        HBox buttons = new HBox(8, generate, cancel);
        VBox left = new VBox(14, form, buttons);
        left.setPadding(new Insets(12));
        ScrollPane leftScroll = new ScrollPane(left);
        leftScroll.setFitToWidth(true);
        leftScroll.setPrefWidth(400);
        leftScroll.setMinWidth(340);

        NumberAxis x = new NumberAxis();
        x.setLabel("Annealing iteration");
        NumberAxis y = new NumberAxis();
        y.setLabel("Soft cost");
        y.setForceZeroInRange(false);
        LineChart<Number, Number> chart = new LineChart<>(x, y);
        chart.setTitle("Soft-constraint cost");
        chart.setCreateSymbols(false);
        chart.setAnimated(false);
        currentSeries.setName("current");
        bestSeries.setName("best so far");
        chart.getData().add(currentSeries);
        chart.getData().add(bestSeries);
        chart.setPrefHeight(300);

        log.setEditable(false);
        log.getStyleClass().add("log");
        log.setWrapText(true);
        progress.setMaxWidth(Double.MAX_VALUE);
        summary.setWrapText(true);
        summary.getStyleClass().add("summary");
        HBox status = new HBox(10, stage);
        status.setAlignment(Pos.CENTER_LEFT);
        VBox right = new VBox(10, status, progress, summary, chart, log);
        VBox.setVgrow(log, Priority.ALWAYS);
        right.setPadding(new Insets(12));

        BorderPane root = new BorderPane();
        root.setLeft(leftScroll);
        root.setCenter(right);
        setContent(root);
    }

    private static Label heading(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("section");
        return l;
    }

    @Override
    public void refresh() {
        if (running != null) return;
        SchedulingConfig config = service.store().settings().loadConfig();
        days.getValueFactory().setValue(config.days());
        periods.getValueFactory().setValue(config.periodsPerDay());
    }

    private Window owner() {
        return getTabPane() == null ? null : getTabPane().getScene().getWindow();
    }

    private void start() {
        Long time = Forms.parseLong(timeLimit.getText(), 1);
        Long clique = Forms.parseLong(cliqueLimit.getText(), 0);
        Long iters = Forms.parseLong(iterations.getText(), 0);
        Long seedValue = Forms.parseLong(seed.getText(), Long.MIN_VALUE);
        Double a = Forms.parseDouble(wIdle.getText(), 0), b = Forms.parseDouble(wLoad.getText(), 0),
                c = Forms.parseDouble(wSpacing.getText(), 0), d = Forms.parseDouble(wBuilding.getText(), 0);
        if (time == null || clique == null || iters == null || seedValue == null) {
            Dialogs.error(owner(), "Check the parameters", "Time limits, iterations and seed must be whole numbers (time limit at least 1).");
            return;
        }
        if (a == null || b == null || c == null || d == null) {
            Dialogs.error(owner(), "Check the weights", "Weights must be numbers of at least 0.");
            return;
        }
        SchedulingConfig config = new SchedulingConfig(days.getValue(), periods.getValue());
        service.store().settings().saveConfig(config);

        TimetableEngine.Options options = new TimetableEngine.Options(clique * 1000,
                new BacktrackingSolver.Options(time * 1000, 1_000, 1.3, seedValue),
                new SimulatedAnnealer.Options(iters, 0, 0, 0.5, seedValue),
                new SoftConstraintEvaluator.Weights(a, b, c, d));
        String runLabel = label.getText().isBlank()
                ? "Run " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                : label.getText().trim();

        log.clear();
        summary.setText("");
        summary.getStyleClass().removeAll("good", "bad");
        currentSeries.getData().clear();
        bestSeries.getData().clear();

        Task<Outcome> task = new Task<>() {
            @Override
            protected Outcome call() {
                TimetableEngine.Result result = service.generate(options, new TimetableEngine.Listener() {
                    @Override
                    public void log(String line) {
                        Platform.runLater(() -> log.appendText(line + "\n"));
                    }

                    @Override
                    public void stage(String name) {
                        updateMessage(name + "...");
                        updateProgress(-1, 1);
                    }

                    @Override
                    public void annealProgress(long iteration, long total, double current, double best) {
                        updateProgress(iteration, total);
                        Platform.runLater(() -> {
                            currentSeries.getData().add(new XYChart.Data<>(iteration, current));
                            bestSeries.getData().add(new XYChart.Data<>(iteration, best));
                        });
                    }
                }, this::isCancelled);
                TimetableRun saved = result.success() && !isCancelled() ? service.save(result, runLabel) : null;
                return new Outcome(result, saved);
            }
        };
        progress.progressProperty().bind(task.progressProperty());
        stage.textProperty().bind(task.messageProperty());
        task.setOnSucceeded(e -> finish(task.getValue()));
        task.setOnCancelled(e -> {
            done("Cancelled. Nothing was saved.");
            setSummary("Generation cancelled.", false);
        });
        task.setOnFailed(e -> {
            done("Failed.");
            Dialogs.error(owner(), "Generation failed", task.getException());
        });

        running = task;
        generate.setDisable(true);
        cancel.setDisable(false);
        Thread worker = new Thread(task, "timetable-engine");
        worker.setDaemon(true);
        worker.start();
    }

    private void finish(Outcome outcome) {
        TimetableEngine.Result r = outcome.result();
        done(r.outcome().text() + ".");
        if (r.success()) {
            setSummary(String.format("Saved as run #%d. %s", outcome.saved().id(), r.breakdown().describe()), true);
            onGenerated.accept(outcome.saved());
        } else if (r.precheck() != null && r.precheck().provenInfeasible()) {
            setSummary("No clash-free timetable can exist:\n- " + String.join("\n- ", r.precheck().reasons()), false);
        } else {
            setSummary(r.log().get(r.log().size() - 1), false);
        }
    }

    private void done(String message) {
        progress.progressProperty().unbind();
        stage.textProperty().unbind();
        progress.setProgress(1);
        stage.setText(message);
        running = null;
        generate.setDisable(false);
        cancel.setDisable(true);
    }

    private void setSummary(String text, boolean good) {
        summary.setText(text);
        summary.getStyleClass().removeAll("good", "bad");
        summary.getStyleClass().add(good ? "good" : "bad");
    }
}
