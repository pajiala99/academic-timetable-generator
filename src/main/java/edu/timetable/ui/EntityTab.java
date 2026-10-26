package edu.timetable.ui;

import edu.timetable.dao.DataAccessException;
import edu.timetable.service.TimetableService;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/** A tab with a searchable table of one entity type and Add / Edit / Delete actions. */
abstract class EntityTab<T> extends Tab implements Refreshable {
    protected final TimetableService service;
    protected final TableView<T> table = new TableView<>();
    private final ObservableList<T> items = FXCollections.observableArrayList();
    private final FilteredList<T> filtered = new FilteredList<>(items);
    private final Label count = new Label();
    private final String noun;

    EntityTab(String title, String noun, TimetableService service) {
        super(title);
        this.service = service;
        this.noun = noun;
        setClosable(false);

        SortedList<T> sorted = new SortedList<>(filtered);
        sorted.comparatorProperty().bind(table.comparatorProperty());
        table.setItems(sorted);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("No " + noun + "s yet. Click \"Add\" to create one."));
        table.setRowFactory(tv -> {
            TableRow<T> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2 && !row.isEmpty()) edit();
            });
            return row;
        });

        Button add = new Button("Add");
        add.getStyleClass().add("accent");
        Button edit = new Button("Edit");
        Button delete = new Button("Delete");
        edit.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        delete.disableProperty().bind(table.getSelectionModel().selectedItemProperty().isNull());
        add.setOnAction(e -> showEditor(null).ifPresent(item -> perform(() -> insert(item), "save")));
        edit.setOnAction(e -> edit());
        delete.setOnAction(e -> {
            T selected = table.getSelectionModel().getSelectedItem();
            if (selected != null && Dialogs.confirm(owner(), "Delete " + noun + "?", "Delete " + describe(selected) + "?"))
                perform(() -> delete(selected), "delete");
        });

        TextField search = new TextField();
        search.setPromptText("Search " + noun + "s");
        search.textProperty().addListener((o, a, q) -> {
            String needle = q == null ? "" : q.trim().toLowerCase();
            filtered.setPredicate(item -> needle.isEmpty() || searchText(item).toLowerCase().contains(needle));
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox toolbar = new HBox(8, add, edit, delete, spacer, count, search);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(10, toolbar, table);
        VBox.setVgrow(table, Priority.ALWAYS);
        box.setPadding(new Insets(12));
        setContent(box);
    }

    protected abstract List<T> load();

    /** Shows the add (existing == null) or edit dialog and returns the edited value. */
    protected abstract Optional<T> showEditor(T existing);

    protected abstract void insert(T item);

    protected abstract void update(T item);

    protected abstract void delete(T item);

    protected abstract String describe(T item);

    /** Explanation shown when the database rejects a change because of its constraints. */
    protected abstract String constraintHint(String action);

    protected String searchText(T item) {
        return describe(item);
    }

    @Override
    public void refresh() {
        try {
            items.setAll(load());
            count.setText(items.size() + " " + noun + (items.size() == 1 ? "" : "s"));
        } catch (DataAccessException e) {
            Dialogs.error(owner(), "Could not load " + noun + "s", e);
        }
    }

    protected Window owner() {
        return getTabPane() == null || getTabPane().getScene() == null ? null : getTabPane().getScene().getWindow();
    }

    protected <V> TableColumn<T, V> column(String name, Function<T, V> value, double prefWidth) {
        TableColumn<T, V> c = new TableColumn<>(name);
        c.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(value.apply(cell.getValue())));
        c.setPrefWidth(prefWidth);
        return c;
    }

    private void edit() {
        T selected = table.getSelectionModel().getSelectedItem();
        if (selected != null) showEditor(selected).ifPresent(item -> perform(() -> update(item), "save"));
    }

    private void perform(Runnable action, String verb) {
        try {
            action.run();
        } catch (DataAccessException e) {
            if (e.isConstraintViolation()) Dialogs.error(owner(), "Could not " + verb + " the " + noun, constraintHint(verb));
            else Dialogs.error(owner(), "Could not " + verb + " the " + noun, e);
        }
        refresh();
    }
}
