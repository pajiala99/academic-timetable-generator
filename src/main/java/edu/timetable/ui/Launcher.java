package edu.timetable.ui;

/**
 * Plain main class for the runnable jar: JavaFX refuses to start from a fat jar whose main
 * class extends {@code Application}, so this indirection is needed. It also chooses between the
 * desktop and the web version.
 */
public final class Launcher {
    private Launcher() {
    }

    /** {@code --web} starts the browser version instead of the desktop window. */
    public static void main(String[] args) throws Exception {
        if (java.util.Arrays.asList(args).contains("--web")) edu.timetable.web.WebServer.main(args);
        else TimetableApp.main(args);
    }
}
