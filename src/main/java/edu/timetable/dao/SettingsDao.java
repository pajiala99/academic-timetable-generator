package edu.timetable.dao;

import edu.timetable.model.SchedulingConfig;

import java.util.Optional;

/** Key-value settings, including the shape of the week. */
public interface SettingsDao {
    String DAYS = "week.days";
    String PERIODS = "week.periods";
    String LUNCH = "week.lunch_after";

    Optional<String> get(String key);

    void put(String key, String value);

    default SchedulingConfig loadConfig() {
        int days = get(DAYS).map(Integer::parseInt).orElse(5);
        int periods = get(PERIODS).map(Integer::parseInt).orElse(7);
        int lunch = get(LUNCH).map(Integer::parseInt).orElse(0);
        return new SchedulingConfig(days, periods, lunch < periods ? lunch : 0);
    }

    default void saveConfig(SchedulingConfig config) {
        put(DAYS, Integer.toString(config.days()));
        put(PERIODS, Integer.toString(config.periodsPerDay()));
        put(LUNCH, Integer.toString(config.lunchAfter()));
    }
}
