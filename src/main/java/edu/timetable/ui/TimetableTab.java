package edu.timetable.ui;

import edu.timetable.dao.DataAccessException;
import edu.timetable.model.Batch;
import edu.timetable.model.Dataset;
import edu.timetable.model.SchedulingConfig;
import edu.timetable.model.TimetableRun;
import edu.timetable.service.CsvExporter;
import edu.timetable.service.TimetableGrid;
import edu.timetable.service.TimetableService;
import edu.timetable.solver.SoftConstraintEvaluator;
import javafx.collections.FXCollections;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Tab;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.RowConstraints;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.File;
import java.io.IOException;
import java.util.List;

/** Weekly grid of a saved timetable, from the point of view of a batch, faculty member or room. */
final class TimetableTab extends Tab implements Refreshable {
    private static final String[] PALETTE = {
            "#dbeafe", "#dcfce7", "#fef3c7", "#fce7f3", "#e0e7ff", "#ccfbf1",
            "#fee2e2", "#ede9fe", "#ecfccb", "#ffedd5", "#cffafe", "#f5f5f4"};

    private final TimetableService service;
    private final ComboBox<TimetableRun> runs = new ComboBox<>();
    private final ComboBox<TimetableGrid.Perspective> perspective =
            new ComboBox<>(FXCollections.observableArrayList(TimetableGrid.Perspective.values()));
    private final ComboBox<TimetableGrid.Subject> subject = new ComboBox<>();
    private final GridPane grid = new GridPane();
    private final Label status = new Label();
    private Dataset data;
    private TimetableRun loaded;
    private Integer pendingRunId;
    private boolean updating;

    TimetableTab(TimetableService service) {
        super("Timetable");
        this.service = service;
        setClosable(false);

        perspective.setValue(TimetableGrid.Perspective.BATCH);
        runs.setPrefWidth(300);
        subject.setPrefWidth(220);
        runs.valueProperty().addListener((o, a, b) -> {
            if (!updating) loadRun();
        });
        perspective.valueProperty().addListener((o, a, b) -> {
            if (!updating) {
                updateSubjects();
                redraw();
            }
        });
        subject.valueProperty().addListener((o, a, b) -> {
            if (!updating) redraw();
        });

        Button delete = new Button("Delete run");
        delete.setOnAction(e -> deleteRun());
        Button exportView = new Button("Export view (CSV)");
        exportView.setOnAction(e -> exportView());
        Button exportAll = new Button("Export all (CSV)");
        exportAll.setOnAction(e -> exportAll());
        delete.disableProperty().bind(runs.valueProperty().isNull());
        exportView.disableProperty().bind(runs.valueProperty().isNull());
        exportAll.disableProperty().bind(runs.valueProperty().isNull());

        HBox toolbar = new HBox(8, new Label("Run"), runs, delete, new Separator(Orientation.VERTICAL),
                new Label("View by"), perspective, subject, new Separator(Orientation.VERTICAL), exportView, exportAll);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(12, 12, 6, 12));

        grid.setHgap(4);
        grid.setVgap(4);
        grid.setPadding(new Insets(6, 12, 12, 12));
        ScrollPane scroll = new ScrollPane(grid);
        scroll.setFitToWidth(true);

        status.setWrapText(true);
        status.setPadding(new Insets(6, 12, 12, 12));

        BorderPane root = new BorderPane();
        root.setTop(toolbar);
        root.setCenter(scroll);
        root.setBottom(status);
        setContent(root);
    }

    /** Selects the given run the next time the tab refreshes. */
    void showRun(int runId) {
        pendingRunId = runId;
        refresh();
    }

    @Override
    public void refresh() {
        try {
            data = service.loadDataset();
            Integer keep = pendingRunId != null ? pendingRunId : runs.getValue() == null ? null : runs.getValue().id();
            pendingRunId = null;
            List<TimetableRun> list = service.store().timetables().findAllSummaries();
            updating = true;
            runs.getItems().setAll(list);
            runs.setValue(list.stream().filter(r -> keep != null && r.id() == keep).findFirst()
                    .orElse(list.isEmpty() ? null : list.get(0)));
            updating = false;
            loadRun();
        } catch (DataAccessException e) {
            updating = false;
            Dialogs.error(owner(), "Could not load timetables", e);
        }
    }

    private void loadRun() {
        TimetableRun summary = runs.getValue();
        loaded = summary == null ? null : service.store().timetables().findById(summary.id()).orElse(null);
        updateSubjects();
        redraw();
        updateStatus();
    }

    private void updateSubjects() {
        Integer keep = subject.getValue() == null ? null : subject.getValue().id();
        List<TimetableGrid.Subject> list = TimetableGrid.subjects(data, perspective.getValue());
        boolean wasUpdating = updating;
        updating = true;
        subject.getItems().setAll(list);
        subject.setValue(list.stream().filter(s -> keep != null && s.id() == keep).findFirst()
                .orElse(list.isEmpty() ? null : list.get(0)));
        updating = wasUpdating;
    }

    private void redraw() {
        grid.getChildren().clear();
        grid.getColumnConstraints().clear();
        grid.getRowConstraints().clear();
        if (loaded == null || subject.getValue() == null) {
            Label empty = new Label(loaded == null
                    ? "No timetable yet. Open the Generate tab and click \"Generate timetable\"."
                    : "Nothing to show for this view.");
            empty.getStyleClass().add("hint");
            grid.add(empty, 0, 0);
            return;
        }
        TimetableGrid.Perspective view = perspective.getValue();
        TimetableGrid tg = TimetableGrid.build(data, loaded.entries(), view, subject.getValue());
        SchedulingConfig config = tg.config();

        grid.getColumnConstraints().add(new ColumnConstraints(56));
        grid.getRowConstraints().add(new RowConstraints());
        for (int p = 0; p < config.periodsPerDay(); p++) {
            // A fixed preferred width (rather than one computed from the content) keeps all periods equally wide.
            ColumnConstraints cc = new ColumnConstraints(120, 150, Double.MAX_VALUE);
            cc.setHgrow(Priority.ALWAYS);
            cc.setFillWidth(true);
            grid.getColumnConstraints().add(cc);
            Label head = new Label("P" + (p + 1));
            head.getStyleClass().add("tt-head");
            head.setMaxWidth(Double.MAX_VALUE);
            GridPane.setHalignment(head, HPos.CENTER);
            grid.add(head, p + 1, 0);
        }
        for (int d = 0; d < config.days(); d++) {
            RowConstraints rc = new RowConstraints();
            rc.setMinHeight(84);
            rc.setVgrow(Priority.ALWAYS);
            grid.getRowConstraints().add(rc);
            Label day = new Label(SchedulingConfig.dayName(d));
            day.getStyleClass().add("tt-head");
            grid.add(day, 0, d + 1);
            for (int p = 0; p < config.periodsPerDay(); p++) grid.add(cell(tg.at(d, p), view), p + 1, d + 1);
        }
    }

    private VBox cell(List<TimetableGrid.Cell> cells, TimetableGrid.Perspective view) {
        VBox box = new VBox(2);
        box.getStyleClass().add("tt-cell");
        box.setMaxWidth(Double.MAX_VALUE);
        box.setMaxHeight(Double.MAX_VALUE);
        if (cells.isEmpty()) {
            box.getStyleClass().add("tt-empty");
            return box;
        }
        StringBuilder tip = new StringBuilder();
        for (TimetableGrid.Cell c : cells) {
            Label code = new Label(c.course().code());
            code.getStyleClass().add("tt-code");
            Label title = new Label(c.course().title());
            title.getStyleClass().add("tt-title");
            boolean sharedLine = view == TimetableGrid.Perspective.BATCH && c.course().isCombined();
            Label details = new Label(sharedLine ? c.room().code() + " | " + c.faculty().name() : c.details(view));
            details.getStyleClass().add("tt-details");
            box.getChildren().addAll(code, title, details);
            if (sharedLine) {
                int self = subject.getValue().id();
                Label shared = new Label("Joint with " + String.join("+",
                        c.batches().stream().filter(b -> b.id() != self).map(Batch::name).toList()));
                shared.getStyleClass().add("tt-shared");
                shared.setWrapText(true);
                box.getChildren().add(shared);
            }
            if (!tip.isEmpty()) tip.append("\n\n");
            tip.append(c.course().code()).append(" - ").append(c.course().title())
                    .append("\nMeeting ").append(c.meetingIndex() + 1).append(" of ").append(c.course().meetingsPerWeek())
                    .append(c.course().isCombined() ? "\nCombined class: " : "\nBatch: ").append(c.batchNames())
                    .append(" (").append(c.batches().stream().mapToInt(Batch::studentCount).sum()).append(" students)")
                    .append("\nFaculty: ").append(c.faculty().name())
                    .append("\nRoom: ").append(c.room().code()).append(", ").append(c.room().building())
                    .append(" (").append(c.room().capacity()).append(" seats, ").append(c.room().type()).append(")");
        }
        String code = cells.get(0).course().code().replaceAll("L$", "");
        box.setStyle("-fx-background-color: " + PALETTE[Math.floorMod(code.hashCode(), PALETTE.length)] + ";");
        if (cells.size() > 1) box.getStyleClass().add("tt-clash");
        Tooltip.install(box, new Tooltip(tip.toString()));
        return box;
    }

    private void updateStatus() {
        status.getStyleClass().removeAll("good", "bad");
        status.setTooltip(null);
        if (loaded == null) {
            status.setText("");
            return;
        }
        try {
            TimetableService.Evaluation ev = service.evaluate(data, loaded, SoftConstraintEvaluator.Weights.defaults());
            String head = String.format("%d of %d meetings placed. Soft cost with default weights: %s",
                    ev.placedMeetings(), ev.totalMeetings(), ev.breakdown().describe());
            if (ev.valid()) {
                status.setText("All hard constraints satisfied (re-checked against current data). " + head);
                status.getStyleClass().add("good");
            } else {
                List<String> v = ev.hardViolations();
                status.setText("This timetable no longer matches the current data (" + v.size() + " problem"
                        + (v.size() == 1 ? "" : "s") + "), e.g. " + v.get(0) + ". Generate a new one. " + head);
                status.getStyleClass().add("bad");
                status.setTooltip(new Tooltip(String.join("\n", v.subList(0, Math.min(40, v.size())))));
            }
        } catch (RuntimeException e) {
            status.setText("Could not evaluate this timetable: " + e.getMessage());
            status.getStyleClass().add("bad");
        }
    }

    private void deleteRun() {
        TimetableRun run = runs.getValue();
        if (run == null || !Dialogs.confirm(owner(), "Delete timetable?", "Delete " + run + "?")) return;
        service.store().timetables().delete(run.id());
        runs.setValue(null);
        refresh();
    }

    private void exportView() {
        if (loaded == null || subject.getValue() == null) return;
        File file = chooseFile(perspective.getValue() + "-" + subject.getValue().name().replaceAll("[^A-Za-z0-9-]+", "_") + ".csv");
        if (file == null) return;
        try {
            CsvExporter.writeGrid(file.toPath(), TimetableGrid.build(data, loaded.entries(), perspective.getValue(), subject.getValue()));
        } catch (IOException e) {
            Dialogs.error(owner(), "Export failed", e);
        }
    }

    private void exportAll() {
        if (loaded == null) return;
        File file = chooseFile("timetable-run-" + loaded.id() + ".csv");
        if (file == null) return;
        try {
            CsvExporter.writeEntries(file.toPath(), data, loaded.entries());
        } catch (IOException e) {
            Dialogs.error(owner(), "Export failed", e);
        }
    }

    private File chooseFile(String name) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export CSV");
        chooser.setInitialFileName(name);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV files", "*.csv"));
        return chooser.showSaveDialog(owner());
    }

    private Window owner() {
        return getTabPane() == null ? null : getTabPane().getScene().getWindow();
    }
}
