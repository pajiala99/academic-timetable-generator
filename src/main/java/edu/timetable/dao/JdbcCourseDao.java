package edu.timetable.dao;

import edu.timetable.model.Course;
import edu.timetable.model.RoomType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Courses and the batches attending them (the {@code course_batch} join table). */
public final class JdbcCourseDao extends JdbcSupport implements CourseDao {
    private static final String COLUMNS = "id, code, title, faculty_id, meetings_per_week, room_type";

    private record Row(int id, String code, String title, int facultyId, int meetings, RoomType type) {
    }

    public JdbcCourseDao(Database db) {
        super(db);
    }

    @Override
    public List<Course> findAll() {
        return load("SELECT " + COLUMNS + " FROM course ORDER BY code, id");
    }

    @Override
    public List<Course> findByBatch(int batchId) {
        return load("SELECT " + COLUMNS + " FROM course WHERE id IN (SELECT course_id FROM course_batch WHERE batch_id = ?) "
                + "ORDER BY code, id", batchId);
    }

    @Override
    public Optional<Course> findById(int id) {
        return load("SELECT " + COLUMNS + " FROM course WHERE id = ?", id).stream().findFirst();
    }

    private List<Course> load(String sql, Object... params) {
        try (Connection c = db.connect()) {
            List<Row> rows = query(c, sql, rs -> new Row(rs.getInt("id"), rs.getString("code"), rs.getString("title"),
                    rs.getInt("faculty_id"), rs.getInt("meetings_per_week"), RoomType.valueOf(rs.getString("room_type"))), params);
            Map<Integer, List<Integer>> batches = new HashMap<>();
            query(c, "SELECT course_id, batch_id FROM course_batch", rs -> {
                batches.computeIfAbsent(rs.getInt(1), k -> new ArrayList<>()).add(rs.getInt(2));
                return null;
            });
            List<Course> out = new ArrayList<>();
            for (Row r : rows) {
                List<Integer> ids = batches.getOrDefault(r.id(), List.of());
                if (ids.isEmpty()) continue; // cannot happen through this DAO; skip rather than fail on hand-edited data
                out.add(new Course(r.id(), r.code(), r.title(), r.facultyId(), ids, r.meetings(), r.type()));
            }
            return out;
        } catch (SQLException e) {
            throw new DataAccessException(e.getMessage(), e);
        }
    }

    @Override
    public Course insert(Course course) {
        return transaction(c -> {
            int id = insert(c, "INSERT INTO course (code, title, faculty_id, meetings_per_week, room_type) VALUES (?, ?, ?, ?, ?)",
                    course.code(), course.title(), course.facultyId(), course.meetingsPerWeek(), course.roomType().name());
            writeBatches(c, id, course.batchIds());
            return course.withId(id);
        });
    }

    @Override
    public void update(Course course) {
        transaction(c -> {
            update(c, "UPDATE course SET code = ?, title = ?, faculty_id = ?, meetings_per_week = ?, room_type = ? WHERE id = ?",
                    course.code(), course.title(), course.facultyId(), course.meetingsPerWeek(), course.roomType().name(), course.id());
            update(c, "DELETE FROM course_batch WHERE course_id = ?", course.id());
            writeBatches(c, course.id(), course.batchIds());
            return null;
        });
    }

    @Override
    public void delete(int id) {
        update("DELETE FROM course WHERE id = ?", id);
    }

    private static void writeBatches(Connection c, int courseId, List<Integer> batchIds) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO course_batch (course_id, batch_id) VALUES (?, ?)")) {
            for (int b : batchIds) {
                ps.setInt(1, courseId);
                ps.setInt(2, b);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }
}
