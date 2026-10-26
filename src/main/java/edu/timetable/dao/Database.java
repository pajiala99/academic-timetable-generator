package edu.timetable.dao;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * Connection settings plus schema bootstrap. MySQL is the primary target; an embedded H2
 * database running in MySQL compatibility mode is available as a zero-setup fallback.
 */
public final class Database {
    public static final String DEFAULT_URL = "jdbc:mysql://localhost:3306/timetable"
            + "?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=5000";
    private static final String H2_OPTIONS = ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    private final String url;
    private final String user;
    private final String password;

    public Database(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password == null ? "" : password;
    }

    /**
     * Reads {@code db.properties} from the working directory, then from {@code ~/.timetable/},
     * then falls back to a local MySQL server; environment variables override all of these.
     */
    public static Database fromConfig() {
        Properties props = new Properties();
        for (Path candidate : new Path[]{Path.of("db.properties"), userDirectory().resolve("db.properties")}) {
            if (Files.isRegularFile(candidate)) {
                try (Reader in = Files.newBufferedReader(candidate, StandardCharsets.UTF_8)) {
                    props.load(in);
                    break;
                } catch (IOException e) {
                    throw new DataAccessException("Cannot read " + candidate.toAbsolutePath(), e);
                }
            }
        }
        String url = env("TIMETABLE_DB_URL", props.getProperty("db.url", DEFAULT_URL));
        String user = env("TIMETABLE_DB_USER", props.getProperty("db.user", "root"));
        String password = env("TIMETABLE_DB_PASSWORD", props.getProperty("db.password", ""));
        return new Database(url, user, password);
    }

    /** A file-based H2 database under {@code ~/.timetable/}. */
    public static Database embedded() {
        Path file = userDirectory().resolve("timetable-db").toAbsolutePath();
        return new Database("jdbc:h2:file:" + file + H2_OPTIONS, "sa", "");
    }

    /** A private in-memory H2 database, for tests. */
    public static Database inMemory(String name) {
        return new Database("jdbc:h2:mem:" + name + H2_OPTIONS, "sa", "");
    }

    public static Path userDirectory() {
        return Path.of(System.getProperty("user.home"), ".timetable");
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }

    public Connection connect() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    /** Creates any missing tables. Safe to call on every start. */
    public void initialize() {
        if (isEmbedded()) {
            try {
                Files.createDirectories(userDirectory());
            } catch (IOException e) {
                throw new DataAccessException("Cannot create " + userDirectory(), e);
            }
        }
        String script;
        try (InputStream in = Database.class.getResourceAsStream("/sql/schema.sql")) {
            if (in == null) throw new IllegalStateException("schema.sql missing from the classpath");
            script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new DataAccessException("Cannot read schema.sql", e);
        }
        String withoutComments = script.lines()
                .filter(line -> !line.trim().startsWith("--"))
                .collect(Collectors.joining("\n"));
        try (Connection c = connect(); Statement st = c.createStatement()) {
            for (String sql : withoutComments.split(";")) {
                if (!sql.isBlank()) st.execute(sql);
            }
        } catch (SQLException e) {
            throw new DataAccessException("Cannot initialise the database at " + url + ": " + e.getMessage(), e);
        }
    }

    public boolean isEmbedded() {
        return url.startsWith("jdbc:h2:");
    }

    public String url() {
        return url;
    }

    /** Short human-readable description, without credentials. */
    public String describe() {
        if (isEmbedded()) return "Embedded H2 database (" + url.substring(0, url.indexOf(';')).replace("jdbc:h2:", "") + ")";
        int q = url.indexOf('?');
        return "MySQL " + (q > 0 ? url.substring(0, q) : url).replace("jdbc:mysql://", "") + " as " + user;
    }
}
