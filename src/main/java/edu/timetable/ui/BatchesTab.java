package edu.timetable.ui;

import edu.timetable.model.Batch;
import edu.timetable.model.Course;
import edu.timetable.service.TimetableService;
import javafx.collections.FXCollections;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class BatchesTab extends EntityTab<Batch> {
    /** Choice in the "lab group of" box meaning "a whole batch, not a lab group". */
    private static final Batch WHOLE = new Batch(-1, "(none: a whole batch)", 1);

    private final Map<Integer, Batch> byId = new HashMap<>();
    private final Map<Integer, Integer> weeklyPeriods = new HashMap<>();
    private final Map<Integer, Integer> courseCount = new HashMap<>();

    BatchesTab(TimetableService service) {
        super("Batches", "batch", service);
        table.getColumns().add(column("Name", Batch::name, 160));
        table.getColumns().add(column("Students", Batch::studentCount, 100));
        table.getColumns().add(column("Lab group of", b -> b.isGroup() && byId.containsKey(b.parentId()) ? byId.get(b.parentId()).name() : "", 130));
        table.getColumns().add(column("Courses", b -> courseCount.getOrDefault(b.id(), 0), 90));
        table.getColumns().add(column("Weekly periods", this::periodsAttended, 130));
    }

    @Override
    protected List<Batch> load() {
        weeklyPeriods.clear();
        courseCount.clear();
        for (Course c : service.store().courses().findAll()) {
            for (int b : c.batchIds()) {
                weeklyPeriods.merge(b, c.weeklyPeriods(), Integer::sum);
                courseCount.merge(b, 1, Integer::sum);
            }
        }
        List<Batch> all = service.store().batches().findAll();
        byId.clear();
        all.forEach(b -> byId.put(b.id(), b));
        return all;
    }

    /** Periods the students sit through: a lab group also attends every class of its batch. */
    private int periodsAttended(Batch b) {
        int own = weeklyPeriods.getOrDefault(b.id(), 0);
        return b.isGroup() ? own + weeklyPeriods.getOrDefault(b.parentId(), 0) : own;
    }

    @Override
    protected Optional<Batch> showEditor(Batch existing) {
        load();
        TextField name = Forms.field(existing == null ? "" : existing.name());
        TextField students = Forms.field(existing == null ? "60" : Integer.toString(existing.studentCount()));

        List<Batch> parents = new ArrayList<>();
        parents.add(WHOLE);
        byId.values().stream()
                .filter(b -> !b.isGroup() && (existing == null || b.id() != existing.id()))
                .sorted(Comparator.comparing(Batch::name))
                .forEach(parents::add);
        ComboBox<Batch> parent = new ComboBox<>(FXCollections.observableArrayList(parents));
        parent.setMaxWidth(Double.MAX_VALUE);
        parent.setValue(existing != null && existing.isGroup() ? byId.getOrDefault(existing.parentId(), WHOLE) : WHOLE);
        boolean hasGroups = existing != null && byId.values().stream().anyMatch(b -> b.isGroup() && b.parentId() == existing.id());
        parent.setDisable(hasGroups);
        Label help = new Label(hasGroups
                ? "This batch has lab groups, so it cannot itself be a lab group."
                : "A lab group (e.g. CSE-1A G1) takes its own labs and attends every class of its batch.");
        help.getStyleClass().add("hint");
        help.setWrapText(true);

        GridPane g = Forms.grid();
        Forms.row(g, 0, "Batch name", name);
        Forms.row(g, 1, "Number of students", students);
        Forms.row(g, 2, "Lab group of", new VBox(6, parent, help));
        return Forms.show(owner(), existing == null ? "Add batch" : "Edit batch " + existing.name(), g,
                () -> {
                    if (name.getText().isBlank()) return "Enter a batch name, e.g. CSE-3A.";
                    if (Forms.parseInt(students.getText(), 1, 10_000) == null) return "Students must be a whole number from 1 to 10000.";
                    return null;
                },
                () -> new Batch(existing == null ? 0 : existing.id(), name.getText().trim(), Forms.parseInt(students.getText(), 1, 10_000),
                        parent.getValue() == WHOLE ? null : parent.getValue().id()));
    }

    @Override
    protected void insert(Batch item) {
        service.store().batches().insert(item);
    }

    @Override
    protected void update(Batch item) {
        service.store().batches().update(item);
    }

    @Override
    protected void delete(Batch item) {
        service.store().batches().delete(item.id());
    }

    @Override
    protected String describe(Batch b) {
        return b.name() + " (" + b.studentCount() + " students)";
    }

    @Override
    protected String constraintHint(String action) {
        return action.equals("delete")
                ? "This batch still has courses or lab groups. Delete or reassign them first."
                : "Another batch already has this name.";
    }
}
