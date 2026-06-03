package com.xpquest.timetracker.db;

import org.h2.tools.Server;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Owns the embedded H2 database lifecycle.
 *
 * <p>{@link #start()} boots an H2 TCP server bound to localhost so the app and an
 * external client (DBeaver, etc.) can both connect to the same engine. The data
 * lives in a single file under {@code ~/.xpquest/timetracker.mv.db}.
 * {@link #stop()} shuts the server down — so the DB engine starts and stops with
 * the application.
 *
 * <p>DBeaver connection string (driver: H2 Server):
 * <pre>jdbc:h2:tcp://localhost:9092/./timetracker   user: sa   password: (blank)</pre>
 */
public final class Database {

    private static final String PORT = "9092";
    private static final String USER = "sa";
    private static final String PASSWORD = "";

    private final Path dataDir = Paths.get(System.getProperty("user.home"), ".xpquest");
    private final String jdbcUrl = "jdbc:h2:tcp://localhost:" + PORT + "/./timetracker";

    private Server tcpServer;

    /** Boots the TCP server and applies the schema. Idempotent table creation. */
    public void start() {
        try {
            Files.createDirectories(dataDir);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot create data directory " + dataDir, e);
        }
        try {
            tcpServer = Server.createTcpServer(
                    "-tcpPort", PORT,
                    "-ifNotExists",
                    "-baseDir", dataDir.toString()).start();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to start H2 TCP server on port " + PORT, e);
        }
        applySchema();
    }

    /** A fresh connection to the running engine. Caller is responsible for closing it. */
    public Connection connection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, USER, PASSWORD);
    }

    public String jdbcUrl() {
        return jdbcUrl;
    }

    /** Stops the TCP server (and therefore the engine). Safe to call more than once. */
    public void stop() {
        if (tcpServer != null) {
            tcpServer.stop();
            tcpServer = null;
        }
    }

    private void applySchema() {
        String script = readResource("/schema.sql");
        try (Connection c = connection(); Statement st = c.createStatement()) {
            for (String statement : script.split(";")) {
                String trimmed = statement.trim();
                if (!trimmed.isEmpty()) {
                    st.execute(trimmed);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialise schema", e);
        }
    }

    private static String readResource(String path) {
        try (InputStream in = Database.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Resource not found on classpath: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read resource " + path, e);
        }
    }
}
