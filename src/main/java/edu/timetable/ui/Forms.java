package edu.timetable.ui;

import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.Optional;
import java.util.function.Supplier;

/** Small helpers for building and validating edit dialogs. */
final class Forms {
    private Forms() {
    }

    static GridPane grid() {
        GridPane g = new GridPane();
        g.setHgap(10);
        g.setVgap(8);
        ColumnConstraints labels = new ColumnConstraints();
        labels.setMinWidth(130);
        ColumnConstraints fields = new ColumnConstraints();
        fields.setHgrow(Priority.ALWAYS);
        fields.setMinWidth(220);
        g.getColumnConstraints().addAll(labels, fields);
        return g;
    }

    static void row(GridPane g, int row, String label, Node field) {
        g.add(new Label(label), 0, row);
        g.add(field, 1, row);
    }

    static TextField field(String text) {
        TextField f = new TextField(text);
        f.setPrefColumnCount(18);
        return f;
    }

    /** The parsed integer, or null if the text is not a whole number within [min, max]. */
    static Integer parseInt(String text, int min, int max) {
        try {
            int v = Integer.parseInt(text.trim());
            return v < min || v > max ? null : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Long parseLong(String text, long min) {
        try {
            long v = Long.parseLong(text.trim().replace("_", "").replace(",", ""));
            return v < min ? null : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Double parseDouble(String text, double min) {
        try {
            double v = Double.parseDouble(text.trim());
            return v < min || Double.isNaN(v) ? null : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Shows a modal OK/Cancel dialog. {@code validate} returns an error message (keeping the
     * dialog open) or null; {@code result} builds the value once validation passes.
     */
    static <T> Optional<T> show(Window owner, String title, Node content, Supplier<String> validate, Supplier<T> result) {
        Dialog<T> dialog = new Dialog<>();
        if (owner != null) dialog.initOwner(owner);
        dialog.setTitle(title);
        dialog.setHeaderText(title);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        Label error = new Label();
        error.getStyleClass().add("error-label");
        error.setWrapText(true);
        VBox box = new VBox(12, content, error);
        box.setPadding(new Insets(4, 0, 0, 0));
        dialog.getDialogPane().setContent(box);
        Button ok = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        ok.addEventFilter(ActionEvent.ACTION, e -> {
            String problem = validate.get();
            if (problem != null) {
                error.setText(problem);
                e.consume();
            }
        });
        dialog.setResultConverter(b -> b == ButtonType.OK ? result.get() : null);
        return dialog.showAndWait();
    }
}
