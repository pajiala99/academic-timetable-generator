package edu.timetable.ui;

import edu.timetable.model.Course;
import edu.timetable.model.Faculty;
import edu.timetable.model.SchedulingConfig;
import edu.timetable.model.TimeSlot;
import edu.timetable.service.TimetableService;
import javafx.beans.binding.Bindings;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

final class FacultyTab extends EntityTab<Faculty> {
    private final Map<Integer, Integer> weeklyMeetings = new HashMap<>();
    private SchedulingConfig config = new SchedulingConfig(5, 7);

    FacultyTab(TimetableService service) {
        super("Faculty", "faculty member", service);
        table.getColumns().add(column("Name", Faculty::name, 220));
        table.getColumns().add(column("Weekly meetings", f -> weeklyMeetings.getOrDefault(f.id(), 0), 140));
        table.getColumns().add(column("Unavailable", this::summarise, 360));
    }

    @Override
    protected List<Faculty> load() {
        config = service.store().settings().loadConfig();
        weeklyMeetings.clear();
        for (Course c : service.store().courses().findAll()) weeklyMeetings.merge(c.facultyId(), c.meetingsPerWeek(), Integer::sum);
        return service.store().faculty().findAll();
    }

    @Override
    protected Optional<Faculty> showEditor(Faculty existing) {
        TextField name = Forms.field(existing == null ? "" : existing.name());
        Set<TimeSlot> initial = existing == null ? Set.of() : existing.unavailable();

        GridPane slots = new GridPane();
        slots.setHgap(4);
        slots.setVgap(4);
        List<ToggleButton> toggles = new ArrayList<>();
        for (int p = 0; p < config.periodsPerDay(); p++) {
            Label l = new Label("P" + (p + 1));
            l.setMinWidth(44);
            l.setAlignment(Pos.CENTER);
            slots.add(l, p + 1, 0);
        }
        for (int d = 0; d < config.days(); d++) {
            slots.add(new Label(SchedulingConfig.dayName(d)), 0, d + 1);
            for (int p = 0; p < config.periodsPerDay(); p++) {
                TimeSlot t = new TimeSlot(d, p);
                ToggleButton b = new ToggleButton();
                b.setUserData(t);
                b.setMinSize(44, 28);
                b.getStyleClass().add("slot-toggle");
                b.setSelected(initial.contains(t));
                b.textProperty().bind(Bindings.when(b.selectedProperty()).then("off").otherwise(""));
                toggles.add(b);
                slots.add(b, p + 1, d + 1);
            }
        }
        Label help = new Label("Mark the slots in which this person cannot teach.");
        help.getStyleClass().add("hint");

        GridPane g = Forms.grid();
        Forms.row(g, 0, "Name", name);
        VBox content = new VBox(12, g, help, slots);
        return Forms.show(owner(), existing == null ? "Add faculty member" : "Edit " + existing.name(), content,
                () -> name.getText().isBlank() ? "Enter a name." : null,
                () -> {
                    Set<TimeSlot> off = new HashSet<>();
                    // Keep slots outside the current week shape, so shrinking the week never loses data.
                    for (TimeSlot t : initial) if (t.day() >= config.days() || t.period() >= config.periodsPerDay()) off.add(t);
                    for (ToggleButton b : toggles) if (b.isSelected()) off.add((TimeSlot) b.getUserData());
                    return new Faculty(existing == null ? 0 : existing.id(), name.getText().trim(), off);
                });
    }

    @Override
    protected void insert(Faculty item) {
        service.store().faculty().insert(item);
    }

    @Override
    protected void update(Faculty item) {
        service.store().faculty().update(item);
    }

    @Override
    protected void delete(Faculty item) {
        service.store().faculty().delete(item.id());
    }

    @Override
    protected String describe(Faculty f) {
        return f.name();
    }

    @Override
    protected String constraintHint(String action) {
        return action.equals("delete")
                ? "This person still teaches courses. Reassign or delete those courses first."
                : "Another faculty member already has this name.";
    }

    private String summarise(Faculty f) {
        Map<Integer, TreeSet<Integer>> byDay = new HashMap<>();
        for (TimeSlot t : f.unavailable()) {
            if (t.day() < config.days() && t.period() < config.periodsPerDay())
                byDay.computeIfAbsent(t.day(), k -> new TreeSet<>()).add(t.period());
        }
        if (byDay.isEmpty()) return "-";
        List<String> parts = new ArrayList<>();
        for (int d = 0; d < config.days(); d++) {
            TreeSet<Integer> periods = byDay.get(d);
            if (periods == null) continue;
            if (periods.size() == config.periodsPerDay()) {
                parts.add(SchedulingConfig.dayName(d) + " all day");
            } else {
                parts.add(SchedulingConfig.dayName(d) + " " + String.join(",", periods.stream().map(p -> "P" + (p + 1)).toList()));
            }
        }
        return String.join("; ", parts);
    }
}
