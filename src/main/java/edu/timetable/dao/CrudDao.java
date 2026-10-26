package edu.timetable.dao;

import java.util.List;
import java.util.Optional;

/** Basic persistence operations for an entity with a generated integer id. */
public interface CrudDao<T> {
    List<T> findAll();

    Optional<T> findById(int id);

    /** Stores a new entity (its id is ignored) and returns it with the generated id. */
    T insert(T item);

    void update(T item);

    void delete(int id);
}
