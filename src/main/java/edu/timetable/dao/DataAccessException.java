package edu.timetable.dao;

import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;

/** Unchecked wrapper for database errors, so DAO calls compose cleanly with UI code. */
public class DataAccessException extends RuntimeException {
    private final boolean constraintViolation;

    public DataAccessException(String message, Throwable cause) {
        super(message, cause);
        this.constraintViolation = isConstraintViolation(cause);
    }

    /** True when the database rejected the change because of a key, uniqueness or check rule. */
    public boolean isConstraintViolation() {
        return constraintViolation;
    }

    private static boolean isConstraintViolation(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SQLIntegrityConstraintViolationException) return true;
            // SQLState class 23 = integrity constraint violation
            if (c instanceof SQLException e && e.getSQLState() != null && e.getSQLState().startsWith("23")) return true;
        }
        return false;
    }
}
