package edu.timetable.ui;

import edu.timetable.model.Batch;
import edu.timetable.model.Course;
import edu.timetable.model.Faculty;
import edu.timetable.model.RoomType;
import edu.timetable.service.TimetableService;
import javafx.collections.FXCollections;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class CoursesTab extends EntityTab<Course> {
    private final Map<Integer, Batch> batches = new HashMap<>();
    private final Map<Integer, Faculty> faculty = new HashMap<>();

    CoursesTab(TimetableService service) {
        super("Courses", "course", service);
        table.getColumns().add(column("Code", Course::code, 100));
        table.getColumns().add(column("Title", Course::title, 260));
        table.getColumns().add(column("Batches", this::batchNames, 140));
        table.getColumns().add(column("Faculty", c -> name(faculty.get(c.facultyId())), 190));
        table.getColumns().add(column("Meetings / week", Course::meetingsPerWeek, 130));
        table.getColumns().add(column("Room type", Course::roomType, 110));
    }

    @Override
    protected List<Course> load() {
        batches.clear();
        faculty.clear();
        service.store().batches().findAll().forEach(b -> batches.put(b.id(), b));
        service.store().faculty().findAll().forEach(f -> faculty.put(f.id(), f));
        return service.store().courses().findAll();
    }

    @Override
    protected Optional<Course> showEditor(Course existing) {
        load();
        if (batches.isEmpty() || faculty.isEmpty()) {
            Dialogs.info(owner(), "Add batches and faculty first", "A course needs a batch to attend it and a faculty member to teach it.");
            return Optional.empty();
        }
        TextField code = Forms.field(existing == null ? "" : existing.code());
        TextField title = Forms.field(existing == null ? "" : existing.title());
        List<CheckBox> batchBoxes = new ArrayList<>();
        FlowPane batchPane = new FlowPane(12, 6);
        for (Batch b : batches.values().stream().sorted(Comparator.comparing(Batch::name)).toList()) {
            CheckBox box = new CheckBox(b.name() + " (" + b.studentCount() + ")");
            box.setUserData(b);
            box.setSelected(existing != null && existing.batchIds().contains(b.id()));
            batchBoxes.add(box);
            batchPane.getChildren().add(box);
        }
        Label attendance = new Label();
        attendance.getStyleClass().add("hint");
        Runnable updateAttendance = () -> {
            List<Batch> chosen = selected(batchBoxes);
            int students = chosen.stream().mapToInt(Batch::studentCount).sum();
            attendance.setText(chosen.size() > 1
                    ? "Combined class: all " + chosen.size() + " batches attend every meeting together (" + students + " students)."
                    : "Tick more than one batch for a combined class or shared elective.");
        };
        batchBoxes.forEach(b -> b.selectedProperty().addListener((o, a, v) -> updateAttendance.run()));
        updateAttendance.run();

        ComboBox<Faculty> teacher = new ComboBox<>(FXCollections.observableArrayList(
                faculty.values().stream().sorted(Comparator.comparing(Faculty::name)).toList()));
        teacher.setMaxWidth(Double.MAX_VALUE);
        if (existing != null) teacher.setValue(faculty.get(existing.facultyId()));
        Spinner<Integer> meetings = new Spinner<>(1, 20, existing == null ? 3 : existing.meetingsPerWeek());
        ComboBox<RoomType> type = new ComboBox<>(FXCollections.observableArrayList(RoomType.values()));
        type.setValue(existing == null ? RoomType.LECTURE : existing.roomType());

        GridPane g = Forms.grid();
        Forms.row(g, 0, "Course code", code);
        Forms.row(g, 1, "Title", title);
        Forms.row(g, 2, "Batches", new VBox(6, batchPane, attendance));
        Forms.row(g, 3, "Faculty", teacher);
        Forms.row(g, 4, "Meetings per week", meetings);
        Forms.row(g, 5, "Room type", type);
        return Forms.show(owner(), existing == null ? "Add course" : "Edit course " + existing.code(), g,
                () -> {
                    if (code.getText().isBlank()) return "Enter a course code.";
                    if (title.getText().isBlank()) return "Enter a title.";
                    if (selected(batchBoxes).isEmpty()) return "Tick the batch (or batches) that attend this course.";
                    if (teacher.getValue() == null) return "Choose who teaches this course.";
                    return null;
                },
                () -> new Course(existing == null ? 0 : existing.id(), code.getText().trim(), title.getText().trim(),
                        teacher.getValue().id(), selected(batchBoxes).stream().map(Batch::id).toList(),
                        meetings.getValue(), type.getValue()));
    }

    private static List<Batch> selected(List<CheckBox> boxes) {
        return boxes.stream().filter(CheckBox::isSelected).map(b -> (Batch) b.getUserData()).toList();
    }

    @Override
    protected void insert(Course item) {
        service.store().courses().insert(item);
    }

    @Override
    protected void update(Course item) {
        service.store().courses().update(item);
    }

    @Override
    protected void delete(Course item) {
        service.store().courses().delete(item.id());
    }

    @Override
    protected String describe(Course c) {
        return c.code() + " " + c.title() + " (" + batchNames(c) + ")";
    }

    private String batchNames(Course c) {
        return String.join("+", c.batchIds().stream().map(id -> name(batches.get(id))).toList());
    }

    @Override
    protected String searchText(Course c) {
        return describe(c) + " " + name(faculty.get(c.facultyId()));
    }

    @Override
    protected String constraintHint(String action) {
        return "The database rejected the change; check that the batches and faculty member still exist.";
    }

    private static String name(Object o) {
        return o == null ? "?" : o.toString();
    }
}
