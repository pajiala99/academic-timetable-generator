package edu.timetable.dao;

import edu.timetable.model.TimetableEntry;
import edu.timetable.model.TimetableRun;

import java.util.List;
import java.util.Optional;

/** Generated timetables. Summaries come without entries; {@link #findById} loads them. */
public interface TimetableDao {
    TimetableRun save(String label, double softCost, String notes, List<TimetableEntry> entries);

    List<TimetableRun> findAllSummaries();

    Optional<TimetableRun> findById(int id);

    void delete(int id);
}
