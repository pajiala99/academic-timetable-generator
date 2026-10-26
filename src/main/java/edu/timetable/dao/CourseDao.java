package edu.timetable.dao;

import edu.timetable.model.Course;

import java.util.List;

public interface CourseDao extends CrudDao<Course> {
    List<Course> findByBatch(int batchId);
}
