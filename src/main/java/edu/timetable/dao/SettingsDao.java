package edu.timetable.dao;

import edu.timetable.model.SchedulingConfig;

import java.util.Optional;

/** Key-value settings, including the shape of the week. */
public interface SettingsDao {
    String DAYS = "week.days";
    String PERIODS = "week.periods";

    Optional<String> get(String key);

    void put(String key, String value);

    default SchedulingConfig loadConfig() {
        int days = get(DAYS).map(Integer::parseInt).orElse(5);
        int periods = get(PERIODS).map(Integer::parseInt).orElse(7);
        return new SchedulingConfig(days, periods);
    }

    default void saveConfig(SchedulingConfig config) {
        put(DAYS, Integer.toString(config.days()));
        put(PERIODS, Integer.toString(config.periodsPerDay()));
    }
}
