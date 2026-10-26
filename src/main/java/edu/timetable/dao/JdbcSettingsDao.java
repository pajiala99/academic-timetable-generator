package edu.timetable.dao;

import java.util.Optional;

public final class JdbcSettingsDao extends JdbcSupport implements SettingsDao {
    public JdbcSettingsDao(Database db) {
        super(db);
    }

    @Override
    public Optional<String> get(String key) {
        return queryOne("SELECT setting_value FROM settings WHERE setting_key = ?", rs -> rs.getString(1), key);
    }

    @Override
    public void put(String key, String value) {
        transaction(c -> {
            if (update(c, "UPDATE settings SET setting_value = ? WHERE setting_key = ?", value, key) == 0) {
                update(c, "INSERT INTO settings (setting_key, setting_value) VALUES (?, ?)", key, value);
            }
            return null;
        });
    }
}
