package edu.timetable.dao;

import edu.timetable.model.Faculty;
import edu.timetable.model.TimeSlot;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Faculty members with their unavailable slots, stored in a child table. */
public final class JdbcFacultyDao extends JdbcSupport implements FacultyDao {
    public JdbcFacultyDao(Database db) {
        super(db);
    }

    @Override
    public List<Faculty> findAll() {
        try (Connection c = db.connect()) {
            Map<Integer, Set<TimeSlot>> off = new HashMap<>();
            query(c, "SELECT faculty_id, day_index, period_index FROM faculty_unavailability", rs -> {
                off.computeIfAbsent(rs.getInt(1), k -> new HashSet<>()).add(new TimeSlot(rs.getInt(2), rs.getInt(3)));
                return null;
            });
            return query(c, "SELECT id, name FROM faculty ORDER BY name",
                    rs -> new Faculty(rs.getInt("id"), rs.getString("name"), off.getOrDefault(rs.getInt("id"), Set.of())));
        } catch (SQLException e) {
            throw new DataAccessException(e.getMessage(), e);
        }
    }

    @Override
    public Optional<Faculty> findById(int id) {
        try (Connection c = db.connect()) {
            List<TimeSlot> off = query(c, "SELECT day_index, period_index FROM faculty_unavailability WHERE faculty_id = ?",
                    rs -> new TimeSlot(rs.getInt(1), rs.getInt(2)), id);
            return query(c, "SELECT id, name FROM faculty WHERE id = ?",
                    rs -> new Faculty(rs.getInt("id"), rs.getString("name"), new HashSet<>(off)), id).stream().findFirst();
        } catch (SQLException e) {
            throw new DataAccessException(e.getMessage(), e);
        }
    }

    @Override
    public Faculty insert(Faculty f) {
        return transaction(c -> {
            int id = insert(c, "INSERT INTO faculty (name) VALUES (?)", f.name());
            writeUnavailability(c, id, f.unavailable());
            return f.withId(id);
        });
    }

    @Override
    public void update(Faculty f) {
        transaction(c -> {
            update(c, "UPDATE faculty SET name = ? WHERE id = ?", f.name(), f.id());
            update(c, "DELETE FROM faculty_unavailability WHERE faculty_id = ?", f.id());
            writeUnavailability(c, f.id(), f.unavailable());
            return null;
        });
    }

    @Override
    public void delete(int id) {
        update("DELETE FROM faculty WHERE id = ?", id);
    }

    private static void writeUnavailability(Connection c, int facultyId, Set<TimeSlot> slots) throws SQLException {
        if (slots.isEmpty()) return;
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO faculty_unavailability (faculty_id, day_index, period_index) VALUES (?, ?, ?)")) {
            for (TimeSlot t : new ArrayList<>(slots)) {
                ps.setInt(1, facultyId);
                ps.setInt(2, t.day());
                ps.setInt(3, t.period());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }
}
