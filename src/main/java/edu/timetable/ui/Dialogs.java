package edu.timetable.ui;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.TextArea;
import javafx.scene.layout.Region;
import javafx.stage.Window;

import java.io.PrintWriter;
import java.io.StringWriter;

final class Dialogs {
    private Dialogs() {
    }

    static void error(Window owner, String header, String message) {
        Alert a = alert(Alert.AlertType.ERROR, owner, header, message);
        a.showAndWait();
    }

    static void error(Window owner, String header, Throwable t) {
        Alert a = alert(Alert.AlertType.ERROR, owner, header, t.getMessage() == null ? t.toString() : t.getMessage());
        StringWriter trace = new StringWriter();
        t.printStackTrace(new PrintWriter(trace));
        TextArea details = new TextArea(trace.toString());
        details.setEditable(false);
        details.setPrefRowCount(14);
        a.getDialogPane().setExpandableContent(details);
        a.showAndWait();
    }

    static void info(Window owner, String header, String message) {
        alert(Alert.AlertType.INFORMATION, owner, header, message).showAndWait();
    }

    static boolean confirm(Window owner, String header, String message) {
        Alert a = alert(Alert.AlertType.CONFIRMATION, owner, header, message);
        return a.showAndWait().filter(b -> b == ButtonType.OK).isPresent();
    }

    static Alert alert(Alert.AlertType type, Window owner, String header, String message) {
        Alert a = new Alert(type, message);
        if (owner != null) a.initOwner(owner);
        a.setTitle("Timetable Generator");
        a.setHeaderText(header);
        a.getDialogPane().setMinHeight(Region.USE_PREF_SIZE);
        a.getDialogPane().setPrefWidth(560);
        a.setResizable(true);
        return a;
    }
}
