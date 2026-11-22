package edu.timetable.dao;

import edu.timetable.model.Batch;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

public final class JdbcBatchDao extends JdbcSupport implements BatchDao {
    public JdbcBatchDao(Database db) {
        super(db);
    }

    @Override
    public List<Batch> findAll() {
        return query("SELECT id, name, student_count, parent_id FROM batch ORDER BY name", JdbcBatchDao::map);
    }

    @Override
    public Optional<Batch> findById(int id) {
        return queryOne("SELECT id, name, student_count, parent_id FROM batch WHERE id = ?", JdbcBatchDao::map, id);
    }

    @Override
    public Batch insert(Batch b) {
        return b.withId(insert("INSERT INTO batch (name, student_count, parent_id) VALUES (?, ?, ?)",
                b.name(), b.studentCount(), b.parentId()));
    }

    @Override
    public void update(Batch b) {
        update("UPDATE batch SET name = ?, student_count = ?, parent_id = ? WHERE id = ?",
                b.name(), b.studentCount(), b.parentId(), b.id());
    }

    @Override
    public void delete(int id) {
        update("DELETE FROM batch WHERE id = ?", id);
    }

    private static Batch map(ResultSet rs) throws SQLException {
        int parent = rs.getInt("parent_id");
        Integer parentId = rs.wasNull() ? null : parent; // wasNull() refers to the column read just before
        return new Batch(rs.getInt("id"), rs.getString("name"), rs.getInt("student_count"), parentId);
    }
}
