package edu.timetable.model;

public record Room(int id, String code, String building, int capacity, RoomType type) {
    public Room withId(int newId) {
        return new Room(newId, code, building, capacity, type);
    }

    @Override
    public String toString() {
        return code + " (" + building + ", " + capacity + ", " + type + ")";
    }
}
