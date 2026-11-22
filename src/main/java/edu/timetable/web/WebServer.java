package edu.timetable.web;

import com.sun.net.httpserver.HttpServer;
import edu.timetable.dao.DataAccessException;
import edu.timetable.dao.DataStore;
import edu.timetable.dao.Database;
import edu.timetable.service.TimetableService;

import java.awt.Desktop;
import java.io.IOException;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * SlotWise in the browser: a small local web server for the same engine and database as the
 * desktop app. It listens on 127.0.0.1 only, so it is reachable from this computer alone.
 *
 * <pre>
 *   java -jar slotwise-1.1.0-all.jar --web [--port 8080] [--embedded] [--sample] [--no-browser]
 * </pre>
 */
public final class WebServer {
    private WebServer() {
    }

    public static void main(String[] args) throws IOException {
        List<String> flags = List.of(args);
        int port = 8080;
        int i = flags.indexOf("--port");
        if (i >= 0 && i + 1 < args.length) port = Integer.parseInt(args[i + 1]);

        DataStore store = open(flags.contains("--embedded"));
        TimetableService service = new TimetableService(store);
        if (flags.contains("--sample") && store.isEmpty()) service.replaceWithSampleData();

        HttpServer server = start(service, port);
        String url = "http://localhost:" + server.getAddress().getPort() + "/";
        System.out.println("SlotWise is running at " + url + "  (" + store.database().describe() + ")");
        System.out.println("Press Ctrl+C to stop.");
        if (!flags.contains("--no-browser")) openBrowser(url);
    }

    /** Starts the server on the given port, or the next free one; port 0 picks any free port. */
    public static HttpServer start(TimetableService service, int port) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < (port == 0 ? 1 : 10); attempt++) {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port == 0 ? 0 : port + attempt), 0);
                server.createContext("/api/", new Api(service).router());
                server.createContext("/", new Http.StaticFiles());
                server.setExecutor(Executors.newFixedThreadPool(8));
                server.start();
                return server;
            } catch (BindException e) {
                last = e; // port in use: try the next one
            }
        }
        throw last;
    }

    /** MySQL as configured; if it cannot be reached, says why and uses the embedded database. */
    private static DataStore open(boolean embedded) {
        if (embedded) return new DataStore(Database.embedded());
        Database configured = Database.fromConfig();
        try {
            return new DataStore(configured);
        } catch (DataAccessException e) {
            Throwable root = e;
            while (root.getCause() != null) root = root.getCause();
            System.out.println("Could not connect to " + configured.describe() + ": " + root.getMessage());
            System.out.println("Using the embedded database in " + Database.userDirectory() + " instead (configure MySQL in db.properties).");
            return new DataStore(Database.embedded());
        }
    }

    private static void openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (Exception | Error ignored) {
            // fall through to the message below
        }
        System.out.println("Open " + url + " in your browser.");
    }
}
