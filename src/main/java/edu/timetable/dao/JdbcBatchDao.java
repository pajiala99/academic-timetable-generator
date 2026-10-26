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
        return query("SELECT id, name, student_count FROM batch ORDER BY name", JdbcBatchDao::map);
    }

    @Override
    public Optional<Batch> findById(int id) {
        return queryOne("SELECT id, name, student_count FROM batch WHERE id = ?", JdbcBatchDao::map, id);
    }

    @Override
    public Batch insert(Batch b) {
        return b.withId(insert("INSERT INTO batch (name, student_count) VALUES (?, ?)", b.name(), b.studentCount()));
    }

    @Override
    public void update(Batch b) {
        update("UPDATE batch SET name = ?, student_count = ? WHERE id = ?", b.name(), b.studentCount(), b.id());
    }

    @Override
    public void delete(int id) {
        update("DELETE FROM batch WHERE id = ?", id);
    }

    private static Batch map(ResultSet rs) throws SQLException {
        return new Batch(rs.getInt("id"), rs.getString("name"), rs.getInt("student_count"));
    }
}
