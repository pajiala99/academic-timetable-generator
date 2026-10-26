package edu.timetable.dao;

import edu.timetable.model.Room;
import edu.timetable.model.RoomType;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

public final class JdbcRoomDao extends JdbcSupport implements RoomDao {
    private static final String COLUMNS = "id, code, building, capacity, room_type";

    public JdbcRoomDao(Database db) {
        super(db);
    }

    @Override
    public List<Room> findAll() {
        return query("SELECT " + COLUMNS + " FROM room ORDER BY code", JdbcRoomDao::map);
    }

    @Override
    public Optional<Room> findById(int id) {
        return queryOne("SELECT " + COLUMNS + " FROM room WHERE id = ?", JdbcRoomDao::map, id);
    }

    @Override
    public Room insert(Room r) {
        int id = insert("INSERT INTO room (code, building, capacity, room_type) VALUES (?, ?, ?, ?)",
                r.code(), r.building(), r.capacity(), r.type().name());
        return r.withId(id);
    }

    @Override
    public void update(Room r) {
        update("UPDATE room SET code = ?, building = ?, capacity = ?, room_type = ? WHERE id = ?",
                r.code(), r.building(), r.capacity(), r.type().name(), r.id());
    }

    @Override
    public void delete(int id) {
        update("DELETE FROM room WHERE id = ?", id);
    }

    private static Room map(ResultSet rs) throws SQLException {
        return new Room(rs.getInt("id"), rs.getString("code"), rs.getString("building"),
                rs.getInt("capacity"), RoomType.valueOf(rs.getString("room_type")));
    }
}
