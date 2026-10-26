package edu.timetable.ui;

/**
 * Plain main class for the runnable jar: JavaFX refuses to start from a fat jar whose main
 * class extends {@code Application}, so this indirection is needed.
 */
public final class Launcher {
    private Launcher() {
    }

    public static void main(String[] args) {
        TimetableApp.main(args);
    }
}
