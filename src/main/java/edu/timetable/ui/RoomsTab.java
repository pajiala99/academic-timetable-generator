package edu.timetable.ui;

import edu.timetable.model.Room;
import edu.timetable.model.RoomType;
import edu.timetable.service.TimetableService;
import javafx.collections.FXCollections;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;

import java.util.List;
import java.util.Optional;

final class RoomsTab extends EntityTab<Room> {
    RoomsTab(TimetableService service) {
        super("Rooms", "room", service);
        table.getColumns().add(column("Code", Room::code, 140));
        table.getColumns().add(column("Building", Room::building, 180));
        table.getColumns().add(column("Capacity", Room::capacity, 100));
        table.getColumns().add(column("Type", Room::type, 120));
    }

    @Override
    protected List<Room> load() {
        return service.store().rooms().findAll();
    }

    @Override
    protected Optional<Room> showEditor(Room existing) {
        TextField code = Forms.field(existing == null ? "" : existing.code());
        List<String> buildings = load().stream().map(Room::building).distinct().sorted().toList();
        ComboBox<String> building = new ComboBox<>(FXCollections.observableArrayList(buildings));
        building.setEditable(true);
        building.setMaxWidth(Double.MAX_VALUE);
        building.getEditor().setText(existing == null ? "" : existing.building());
        TextField capacity = Forms.field(existing == null ? "60" : Integer.toString(existing.capacity()));
        ComboBox<RoomType> type = new ComboBox<>(FXCollections.observableArrayList(RoomType.values()));
        type.setValue(existing == null ? RoomType.LECTURE : existing.type());

        GridPane g = Forms.grid();
        Forms.row(g, 0, "Room code", code);
        Forms.row(g, 1, "Building", building);
        Forms.row(g, 2, "Seating capacity", capacity);
        Forms.row(g, 3, "Room type", type);
        return Forms.show(owner(), existing == null ? "Add room" : "Edit room " + existing.code(), g,
                () -> {
                    if (code.getText().isBlank()) return "Enter a room code.";
                    if (building.getEditor().getText().isBlank()) return "Enter a building.";
                    if (Forms.parseInt(capacity.getText(), 1, 10_000) == null) return "Capacity must be a whole number from 1 to 10000.";
                    return null;
                },
                () -> new Room(existing == null ? 0 : existing.id(), code.getText().trim(),
                        building.getEditor().getText().trim(), Forms.parseInt(capacity.getText(), 1, 10_000), type.getValue()));
    }

    @Override
    protected void insert(Room item) {
        service.store().rooms().insert(item);
    }

    @Override
    protected void update(Room item) {
        service.store().rooms().update(item);
    }

    @Override
    protected void delete(Room item) {
        service.store().rooms().delete(item.id());
    }

    @Override
    protected String describe(Room r) {
        return r.code() + " (" + r.building() + ", " + r.capacity() + " seats, " + r.type() + ")";
    }

    @Override
    protected String constraintHint(String action) {
        return "Another room already uses this code.";
    }
}
