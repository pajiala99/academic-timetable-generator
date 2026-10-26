package edu.timetable.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Small JDBC helpers shared by the DAO implementations: parameter binding, mapping, transactions. */
abstract class JdbcSupport {
    protected final Database db;

    @FunctionalInterface
    protected interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    @FunctionalInterface
    protected interface SqlWork<T> {
        T run(Connection c) throws SQLException;
    }

    protected JdbcSupport(Database db) {
        this.db = db;
    }

    protected <T> List<T> query(String sql, RowMapper<T> mapper, Object... params) {
        try (Connection c = db.connect()) {
            return query(c, sql, mapper, params);
        } catch (SQLException e) {
            throw fail(sql, e);
        }
    }

    protected static <T> List<T> query(Connection c, String sql, RowMapper<T> mapper, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                List<T> out = new ArrayList<>();
                while (rs.next()) out.add(mapper.map(rs));
                return out;
            }
        }
    }

    protected <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
        return query(sql, mapper, params).stream().findFirst();
    }

    protected int update(String sql, Object... params) {
        try (Connection c = db.connect()) {
            return update(c, sql, params);
        } catch (SQLException e) {
            throw fail(sql, e);
        }
    }

    protected static int update(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            return ps.executeUpdate();
        }
    }

    /** Executes an INSERT and returns the generated integer key. */
    protected static int insert(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            bind(ps, params);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("No generated key returned for: " + sql);
                return keys.getInt(1);
            }
        }
    }

    protected int insert(String sql, Object... params) {
        return transaction(c -> insert(c, sql, params));
    }

    /** Runs {@code work} in a single transaction, rolling back on any failure. */
    protected <T> T transaction(SqlWork<T> work) {
        try (Connection c = db.connect()) {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                T result = work.run(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
        } catch (SQLException e) {
            throw fail("transaction", e);
        }
    }

    private static void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
    }

    private static DataAccessException fail(String what, SQLException e) {
        return new DataAccessException(e.getMessage() + " [" + what.trim().split("\\s+")[0] + "]", e);
    }
}
