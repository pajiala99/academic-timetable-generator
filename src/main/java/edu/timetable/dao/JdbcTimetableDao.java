package edu.timetable.dao;

import edu.timetable.model.TimetableEntry;
import edu.timetable.model.TimetableRun;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public final class JdbcTimetableDao extends JdbcSupport implements TimetableDao {
    public JdbcTimetableDao(Database db) {
        super(db);
    }

    @Override
    public TimetableRun save(String label, double softCost, String notes, List<TimetableEntry> entries) {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        int id = transaction(c -> {
            int runId = insert(c, "INSERT INTO timetable_run (created_at, label, soft_cost, notes) VALUES (?, ?, ?, ?)",
                    Timestamp.valueOf(now), label, softCost, notes);
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO timetable_entry "
                    + "(run_id, course_id, meeting_index, day_index, period_index, room_id) VALUES (?, ?, ?, ?, ?, ?)")) {
                for (TimetableEntry e : entries) {
                    ps.setInt(1, runId);
                    ps.setInt(2, e.courseId());
                    ps.setInt(3, e.meetingIndex());
                    ps.setInt(4, e.day());
                    ps.setInt(5, e.period());
                    ps.setInt(6, e.roomId());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return runId;
        });
        return new TimetableRun(id, now, label, softCost, notes, entries);
    }

    @Override
    public List<TimetableRun> findAllSummaries() {
        return query("SELECT id, created_at, label, soft_cost, notes FROM timetable_run ORDER BY id DESC",
                rs -> mapRun(rs, List.of()));
    }

    @Override
    public Optional<TimetableRun> findById(int id) {
        try (Connection c = db.connect()) {
            List<TimetableEntry> entries = query(c, "SELECT course_id, meeting_index, day_index, period_index, room_id "
                            + "FROM timetable_entry WHERE run_id = ? ORDER BY day_index, period_index",
                    rs -> new TimetableEntry(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5)), id);
            return query(c, "SELECT id, created_at, label, soft_cost, notes FROM timetable_run WHERE id = ?",
                    rs -> mapRun(rs, entries), id).stream().findFirst();
        } catch (SQLException e) {
            throw new DataAccessException(e.getMessage(), e);
        }
    }

    @Override
    public void delete(int id) {
        update("DELETE FROM timetable_run WHERE id = ?", id);
    }

    private static TimetableRun mapRun(ResultSet rs, List<TimetableEntry> entries) throws SQLException {
        Timestamp created = rs.getTimestamp("created_at");
        return new TimetableRun(rs.getInt("id"), created == null ? null : created.toLocalDateTime(),
                rs.getString("label"), rs.getDouble("soft_cost"), rs.getString("notes"), entries);
    }
}
