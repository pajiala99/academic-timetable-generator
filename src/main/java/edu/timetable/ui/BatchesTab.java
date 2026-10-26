package edu.timetable.ui;

import edu.timetable.model.Batch;
import edu.timetable.model.Course;
import edu.timetable.service.TimetableService;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class BatchesTab extends EntityTab<Batch> {
    private final Map<Integer, Integer> weeklyMeetings = new HashMap<>();
    private final Map<Integer, Integer> courseCount = new HashMap<>();

    BatchesTab(TimetableService service) {
        super("Batches", "batch", service);
        table.getColumns().add(column("Name", Batch::name, 160));
        table.getColumns().add(column("Students", Batch::studentCount, 100));
        table.getColumns().add(column("Courses", b -> courseCount.getOrDefault(b.id(), 0), 100));
        table.getColumns().add(column("Weekly meetings", b -> weeklyMeetings.getOrDefault(b.id(), 0), 140));
    }

    @Override
    protected List<Batch> load() {
        weeklyMeetings.clear();
        courseCount.clear();
        for (Course c : service.store().courses().findAll()) {
            for (int b : c.batchIds()) {
                weeklyMeetings.merge(b, c.meetingsPerWeek(), Integer::sum);
                courseCount.merge(b, 1, Integer::sum);
            }
        }
        return service.store().batches().findAll();
    }

    @Override
    protected Optional<Batch> showEditor(Batch existing) {
        TextField name = Forms.field(existing == null ? "" : existing.name());
        TextField students = Forms.field(existing == null ? "60" : Integer.toString(existing.studentCount()));
        GridPane g = Forms.grid();
        Forms.row(g, 0, "Batch name", name);
        Forms.row(g, 1, "Number of students", students);
        return Forms.show(owner(), existing == null ? "Add batch" : "Edit batch " + existing.name(), g,
                () -> {
                    if (name.getText().isBlank()) return "Enter a batch name, e.g. CSE-3A.";
                    if (Forms.parseInt(students.getText(), 1, 10_000) == null) return "Students must be a whole number from 1 to 10000.";
                    return null;
                },
                () -> new Batch(existing == null ? 0 : existing.id(), name.getText().trim(), Forms.parseInt(students.getText(), 1, 10_000)));
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
                ? "This batch still has courses. Delete or reassign its courses first."
                : "Another batch already has this name.";
    }
}
