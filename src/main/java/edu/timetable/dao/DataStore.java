package edu.timetable.dao;

import edu.timetable.model.Batch;
import edu.timetable.model.Course;
import edu.timetable.model.Dataset;
import edu.timetable.model.Faculty;
import edu.timetable.model.Room;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

/** Entry point of the persistence layer: one DAO per table group, over one database. */
public final class DataStore {
    private final Database database;
    private final RoomDao rooms;
    private final BatchDao batches;
    private final FacultyDao faculty;
    private final CourseDao courses;
    private final TimetableDao timetables;
    private final SettingsDao settings;

    /** Opens the database, creating the schema if needed. */
    public DataStore(Database database) {
        this.database = database;
        database.initialize();
        this.rooms = new JdbcRoomDao(database);
        this.batches = new JdbcBatchDao(database);
        this.faculty = new JdbcFacultyDao(database);
        this.courses = new JdbcCourseDao(database);
        this.timetables = new JdbcTimetableDao(database);
        this.settings = new JdbcSettingsDao(database);
    }

    public Database database() { return database; }
    public RoomDao rooms() { return rooms; }
    public BatchDao batches() { return batches; }
    public FacultyDao faculty() { return faculty; }
    public CourseDao courses() { return courses; }
    public TimetableDao timetables() { return timetables; }
    public SettingsDao settings() { return settings; }

    /** Everything the timetable engine needs, read in one go. */
    public Dataset loadDataset() {
        return new Dataset(settings.loadConfig(), rooms.findAll(), faculty.findAll(), batches.findAll(), courses.findAll());
    }

    public boolean isEmpty() {
        return rooms.findAll().isEmpty() && batches.findAll().isEmpty() && faculty.findAll().isEmpty();
    }

    /** Deletes all data, including saved timetables. */
    public void clearAll() {
        try (Connection c = database.connect(); Statement st = c.createStatement()) {
            for (String table : new String[]{"timetable_entry", "timetable_run", "course_batch", "course", "faculty_unavailability",
                    "faculty", "batch", "room"}) {
                st.executeUpdate("DELETE FROM " + table);
            }
        } catch (SQLException e) {
            throw new DataAccessException("Could not clear the database: " + e.getMessage(), e);
        }
    }

    /** Inserts a whole dataset, remapping its ids to the generated ones. */
    public void importDataset(Dataset data) {
        settings.saveConfig(data.config());
        for (Room r : data.rooms()) rooms.insert(r);
        Map<Integer, Integer> batchIds = new HashMap<>();
        for (Batch b : data.batches()) batchIds.put(b.id(), batches.insert(b).id());
        Map<Integer, Integer> facultyIds = new HashMap<>();
        for (Faculty f : data.faculty()) facultyIds.put(f.id(), faculty.insert(f).id());
        for (Course c : data.courses()) {
            courses.insert(new Course(0, c.code(), c.title(), facultyIds.get(c.facultyId()),
                    c.batchIds().stream().map(batchIds::get).toList(), c.meetingsPerWeek(), c.roomType()));
        }
    }
}
