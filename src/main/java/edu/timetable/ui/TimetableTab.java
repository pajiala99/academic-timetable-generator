package edu.timetable.ui;

import edu.timetable.dao.DataAccessException;
import edu.timetable.model.Dataset;
import edu.timetable.model.SchedulingConfig;
import edu.timetable.model.TimetableRun;
import edu.timetable.service.CsvExporter;
import edu.timetable.service.PdfExporter;
import edu.timetable.service.TimetableGrid;
import edu.timetable.service.TimetableService;
import edu.timetable.solver.SoftConstraintEvaluator;
import javafx.collections.FXCollections;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.print.PageLayout;
import javafx.print.PageOrientation;
import javafx.print.Paper;
import javafx.print.Printer;
import javafx.print.PrinterJob;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
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
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.transform.Scale;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
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
        MenuButton print = new MenuButton("PDF / Print");
        MenuItem pdfView = new MenuItem("Save this timetable as PDF...");
        pdfView.setOnAction(e -> savePdf(false));
        MenuItem pdfEvery = new MenuItem("Save every timetable in this view as PDF (one page each)...");
        pdfEvery.setOnAction(e -> savePdf(true));
        MenuItem printView = new MenuItem("Print this timetable...");
        printView.setOnAction(e -> print(false));
        MenuItem printEvery = new MenuItem("Print every timetable in this view...");
        printEvery.setOnAction(e -> print(true));
        print.getItems().addAll(pdfView, pdfEvery, new SeparatorMenuItem(), printView, printEvery);
        delete.disableProperty().bind(runs.valueProperty().isNull());
        exportView.disableProperty().bind(runs.valueProperty().isNull());
        exportAll.disableProperty().bind(runs.valueProperty().isNull());
        print.disableProperty().bind(runs.valueProperty().isNull());

        HBox toolbar = new HBox(8, new Label("Run"), runs, delete, new Separator(Orientation.VERTICAL),
                new Label("View by"), perspective, subject, new Separator(Orientation.VERTICAL), exportView, exportAll, print);
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
        fill(grid, TimetableGrid.build(data, loaded.entries(), perspective.getValue(), subject.getValue()));
    }

    /** Lays out a weekly grid: a header row of periods, then one row per day. */
    private void fill(GridPane target, TimetableGrid tg) {
        SchedulingConfig config = tg.config();
        int lunch = config.lunchAfter();
        target.getColumnConstraints().add(new ColumnConstraints(56));
        target.getRowConstraints().add(new RowConstraints());
        for (int p = 0; p < config.periodsPerDay(); p++) {
            // A fixed preferred width (rather than one computed from the content) keeps all periods equally wide.
            ColumnConstraints cc = new ColumnConstraints(120, 150, Double.MAX_VALUE);
            cc.setHgrow(Priority.ALWAYS);
            cc.setFillWidth(true);
            target.getColumnConstraints().add(cc);
            Label head = new Label("P" + (p + 1));
            head.getStyleClass().add("tt-head");
            head.setMaxWidth(Double.MAX_VALUE);
            GridPane.setHalignment(head, HPos.CENTER);
            target.add(head, column(p, lunch), 0);
            if (p + 1 == lunch) {
                // A narrow Lunch column after period `lunch`: no class runs through it.
                target.getColumnConstraints().add(new ColumnConstraints(44));
                Label l = new Label("Lunch");
                l.getStyleClass().add("tt-lunch-head");
                GridPane.setHalignment(l, HPos.CENTER);
                target.add(l, lunch + 1, 0);
            }
        }
        for (int d = 0; d < config.days(); d++) {
            RowConstraints rc = new RowConstraints();
            rc.setMinHeight(84);
            rc.setVgrow(Priority.ALWAYS);
            target.getRowConstraints().add(rc);
            Label day = new Label(SchedulingConfig.dayName(d));
            day.getStyleClass().add("tt-head");
            target.add(day, 0, d + 1);
            for (int p = 0; p < config.periodsPerDay(); p++) target.add(cell(tg, d, p), column(p, lunch), d + 1);
            if (lunch > 0) {
                Label l = new Label("Lunch");
                l.setRotate(-90);
                l.getStyleClass().add("tt-lunch");
                StackPane band = new StackPane(l);
                band.getStyleClass().add("tt-lunch-band");
                target.add(band, lunch + 1, d + 1);
            }
        }
    }

    /** Grid column of period p: one to the right of the day names, plus one after the lunch column. */
    private static int column(int p, int lunch) {
        return p + 1 + (lunch > 0 && p >= lunch ? 1 : 0);
    }

    private VBox cell(TimetableGrid tg, int day, int period) {
        List<TimetableGrid.Cell> cells = tg.at(day, period);
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
            Label code = new Label(tg.heading(c));
            code.getStyleClass().add("tt-code");
            Label title = new Label(c.course().title());
            title.getStyleClass().add("tt-title");
            Label details = new Label(tg.detailsLine(c));
            details.getStyleClass().add("tt-details");
            box.getChildren().addAll(code, title, details);
            String joint = tg.jointWith(c);
            if (!joint.isEmpty()) {
                Label shared = new Label(joint);
                shared.getStyleClass().add("tt-shared");
                shared.setWrapText(true);
                box.getChildren().add(shared);
            }
            if (!tip.isEmpty()) tip.append("\n\n");
            tip.append(TimetableGrid.tooltip(c));
        }
        String key = TimetableGrid.colourKey(cells.get(0));
        box.setStyle("-fx-background-color: " + PALETTE[Math.floorMod(key.hashCode(), PALETTE.length)] + ";");
        if (tg.clashAt(day, period)) box.getStyleClass().add("tt-clash");
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
        File file = chooseFile(perspective.getValue() + "-" + subject.getValue().name().replaceAll("[^A-Za-z0-9-]+", "_") + ".csv",
                "CSV files", "*.csv");
        if (file == null) return;
        try {
            CsvExporter.writeGrid(file.toPath(), TimetableGrid.build(data, loaded.entries(), perspective.getValue(), subject.getValue()));
        } catch (IOException e) {
            Dialogs.error(owner(), "Export failed", e);
        }
    }

    private void exportAll() {
        if (loaded == null) return;
        File file = chooseFile("timetable-run-" + loaded.id() + ".csv", "CSV files", "*.csv");
        if (file == null) return;
        try {
            CsvExporter.writeEntries(file.toPath(), data, loaded.entries());
        } catch (IOException e) {
            Dialogs.error(owner(), "Export failed", e);
        }
    }

    /** Prints the current timetable, or every one in the current view, one A4 landscape page each. */
    private void print(boolean every) {
        if (loaded == null || subject.getValue() == null) return;
        PrinterJob job = PrinterJob.createPrinterJob();
        if (job == null) {
            Dialogs.error(owner(), "Printing is not available", "No printer is set up on this computer. "
                    + "Use \"Save as PDF\" instead: it does not need a printer.");
            return;
        }
        if (!job.showPrintDialog(owner())) {
            job.cancelJob();
            return;
        }
        PageLayout layout = job.getPrinter().createPageLayout(Paper.A4, PageOrientation.LANDSCAPE, Printer.MarginType.DEFAULT);
        List<TimetableGrid.Subject> pages = every ? List.copyOf(subject.getItems()) : List.of(subject.getValue());
        for (TimetableGrid.Subject s : pages) {
            if (!job.printPage(layout, printablePage(s, layout))) {
                Dialogs.error(owner(), "Printing failed", "The printer reported an error: " + job.getJobStatus());
                job.cancelJob();
                return;
            }
        }
        job.endJob();
    }

    /** Writes the current timetable, or every one in the current view, to a PDF. Needs no printer. */
    private void savePdf(boolean every) {
        if (loaded == null || subject.getValue() == null) return;
        List<TimetableGrid.Subject> pages = every ? List.copyOf(subject.getItems()) : List.of(subject.getValue());
        String name = every ? perspective.getValue() + "-timetables" : perspective.getValue() + "-" + subject.getValue().name();
        File file = chooseFile(name.replaceAll("[^A-Za-z0-9-]+", "_") + ".pdf", "PDF files", "*.pdf");
        if (file == null) return;
        try {
            List<BufferedImage> images = new ArrayList<>();
            for (TimetableGrid.Subject s : pages) images.add(render(pageNode(s, 0, 0)));
            PdfExporter.write(file.toPath(), images);
        } catch (IOException | RuntimeException e) {
            Dialogs.error(owner(), "Could not save the PDF", e);
        }
    }

    /** Snapshots a page at twice its size, so the PDF stays sharp when zoomed or printed. */
    private static BufferedImage render(Node page) {
        SnapshotParameters params = new SnapshotParameters();
        params.setTransform(new Scale(2, 2));
        WritableImage image = page.snapshot(params, null);
        int w = (int) image.getWidth(), h = (int) image.getHeight();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        PixelReader pixels = image.getPixelReader();
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) out.setRGB(x, y, pixels.getArgb(x, y));
        return out;
    }

    /** A white page with a heading and the weekly grid, scaled to fit the printable area. */
    Node printablePage(TimetableGrid.Subject s, PageLayout layout) {
        return pageNode(s, layout.getPrintableWidth(), layout.getPrintableHeight());
    }

    /** A page for one timetable, scaled down to fit {@code fitWidth} x {@code fitHeight} (0 = natural size). */
    private Node pageNode(TimetableGrid.Subject s, double fitWidth, double fitHeight) {
        TimetableGrid tg = TimetableGrid.build(data, loaded.entries(), perspective.getValue(), s);
        GridPane g = new GridPane();
        g.setHgap(4);
        g.setVgap(4);
        fill(g, tg);
        Label heading = new Label(perspective.getValue() + ": " + s.name());
        heading.getStyleClass().add("print-heading");
        Label sub = new Label(loaded.label() + "  |  printed " + LocalDate.now());
        sub.getStyleClass().add("hint");
        VBox page = new VBox(8, heading, sub, g);
        page.getStyleClass().add("print-page");
        Scene scene = new Scene(new Group(page));
        if (getTabPane() != null && getTabPane().getScene() != null) scene.getStylesheets().setAll(getTabPane().getScene().getStylesheets());
        double width = 1150;
        page.applyCss();
        page.resize(width, page.prefHeight(width));
        page.layout();
        if (fitWidth > 0 && fitHeight > 0) {
            double scale = Math.min(fitWidth / width, fitHeight / page.getHeight());
            page.getTransforms().setAll(new Scale(scale, scale));
        }
        return page;
    }

    private File chooseFile(String name, String kind, String pattern) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Save as");
        chooser.setInitialFileName(name);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(kind, pattern));
        return chooser.showSaveDialog(owner());
    }

    private Window owner() {
        return getTabPane() == null ? null : getTabPane().getScene().getWindow();
    }
}
