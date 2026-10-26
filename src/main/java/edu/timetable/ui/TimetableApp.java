package edu.timetable.ui;

import edu.timetable.dao.DataAccessException;
import edu.timetable.dao.DataStore;
import edu.timetable.dao.Database;
import edu.timetable.service.TimetableService;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.List;
import java.util.Optional;

/**
 * JavaFX entry point. Connects to MySQL (or, on request or if MySQL is unreachable, to the
 * embedded database) and shows the data-entry, generation and timetable tabs.
 *
 * <p>Command-line options: {@code --embedded} skips MySQL; {@code --sample} loads the sample
 * department into an empty database without asking.
 */
public class TimetableApp extends Application {
    private TabPane tabs;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        List<String> args = getParameters().getRaw();
        DataStore store = openStore(args.contains("--embedded"));
        if (store == null) {
            Platform.exit();
            return;
        }
        TimetableService service = new TimetableService(store);
        if (store.isEmpty() && (args.contains("--sample") || Dialogs.confirm(null, "The database is empty",
                "Load the sample Computer Science department (6 batches, 16 faculty, 10 rooms, 42 courses)?\n\n"
                        + "You can edit or replace it afterwards."))) {
            service.replaceWithSampleData();
        }

        TimetableTab timetable = new TimetableTab(service);
        GenerateTab generate = new GenerateTab(service, run -> {
            timetable.showRun(run.id());
            tabs.getSelectionModel().select(timetable);
        });
        tabs = new TabPane(new RoomsTab(service), new BatchesTab(service), new FacultyTab(service),
                new CoursesTab(service), generate, timetable);
        tabs.getSelectionModel().selectedItemProperty().addListener((o, a, tab) -> refresh(tab));

        Label title = new Label("Academic Timetable Generator");
        title.getStyleClass().add("app-title");
        Label db = new Label(store.database().describe());
        db.getStyleClass().add("hint");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(12, title, spacer, db);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(10, 14, 10, 14));
        header.getStyleClass().add("header");

        BorderPane root = new BorderPane();
        root.setTop(new VBox(menu(stage, service), header));
        root.setCenter(tabs);

        Scene scene = new Scene(root, 1320, 820);
        scene.getStylesheets().add(TimetableApp.class.getResource("app.css").toExternalForm());
        stage.setTitle("Academic Timetable Generator");
        stage.setScene(scene);
        stage.setMinWidth(900);
        stage.setMinHeight(600);
        stage.show();
        refresh(tabs.getSelectionModel().getSelectedItem());
    }

    private static void refresh(Tab tab) {
        if (tab instanceof Refreshable r) r.refresh();
    }

    private MenuBar menu(Stage stage, TimetableService service) {
        MenuItem sample = new MenuItem("Replace all data with the sample department...");
        sample.setOnAction(e -> {
            if (Dialogs.confirm(stage, "Replace all data?",
                    "This deletes every room, batch, faculty member, course and saved timetable, "
                            + "then loads the sample department.")) {
                try {
                    service.replaceWithSampleData();
                } catch (DataAccessException ex) {
                    Dialogs.error(stage, "Could not load the sample data", ex);
                }
                refresh(tabs.getSelectionModel().getSelectedItem());
            }
        });
        MenuItem exit = new MenuItem("Exit");
        exit.setOnAction(e -> Platform.exit());
        Menu file = new Menu("File", null, sample, exit);

        MenuItem about = new MenuItem("How it works");
        about.setOnAction(e -> Dialogs.info(stage, "How the timetable is built", """
                1. Every course is split into its weekly meetings. Meetings that share a batch or a \
                faculty member conflict, so the problem is graph colouring: meetings are vertices, \
                conflicts are edges and time slots are colours.

                2. Before searching, the generator looks for proofs that no timetable exists: a \
                clique of mutually conflicting meetings larger than the number of slots, and \
                Hall's-theorem checks on slots and on rooms.

                3. A backtracking search with minimum-remaining-values ordering, saturation-degree \
                tie-breaking and forward checking finds a timetable with no clashes. Rooms are kept \
                as an exact matching per slot.

                4. Simulated annealing then reduces idle gaps, uneven faculty loads, badly spaced \
                sessions and building changes, rejecting every move that would create a clash.

                5. The result is re-checked by an independent validator before it is saved."""));
        Menu help = new Menu("Help", null, about);
        return new MenuBar(file, help);
    }

    /** MySQL as configured; on failure offers the embedded database. Returns null to quit. */
    private static DataStore openStore(boolean embedded) {
        if (embedded) return new DataStore(Database.embedded());
        Database configured = Database.fromConfig();
        try {
            return new DataStore(configured);
        } catch (DataAccessException e) {
            ButtonType useEmbedded = new ButtonType("Use embedded database", ButtonBar.ButtonData.OK_DONE);
            ButtonType quit = new ButtonType("Exit", ButtonBar.ButtonData.CANCEL_CLOSE);
            Alert alert = Dialogs.alert(Alert.AlertType.WARNING, null, "Could not connect to MySQL",
                    configured.describe() + "\n\n" + rootMessage(e) + "\n\nYou can continue with the built-in "
                            + "embedded database (stored in " + Database.userDirectory() + "), or exit and configure "
                            + "MySQL in db.properties (see README.md).");
            alert.getButtonTypes().setAll(useEmbedded, quit);
            Optional<ButtonType> choice = alert.showAndWait();
            if (choice.isPresent() && choice.get() == useEmbedded) {
                try {
                    return new DataStore(Database.embedded());
                } catch (DataAccessException e2) {
                    Dialogs.error(null, "Could not open the embedded database", e2);
                }
            }
            return null;
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null) c = c.getCause();
        return c.getMessage() == null ? c.toString() : c.getMessage();
    }
}
