package app.kspani.data;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public final class AppDatabase implements AutoCloseable {
    private final String jdbcUrl;
    private Connection connection;

    public AppDatabase(Path file) {
        this.jdbcUrl = "jdbc:sqlite:" + file.toAbsolutePath();
    }

    public synchronized void initialize() {
        try {
            connection = DriverManager.getConnection(jdbcUrl);
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA journal_mode=WAL");
                st.execute("PRAGMA foreign_keys=ON");
                st.execute("""
                        CREATE TABLE IF NOT EXISTS source_selection (
                          media_id INTEGER PRIMARY KEY,
                          source_id TEXT NOT NULL,
                          server_name TEXT NOT NULL DEFAULT '',
                          prefer_dub INTEGER NOT NULL DEFAULT 0,
                          language_preference TEXT NOT NULL DEFAULT 'AUTO',
                          video_index INTEGER NOT NULL DEFAULT 0,
                          subtitle_index INTEGER,
                          selected_episode TEXT NOT NULL DEFAULT '',
                          updated_at INTEGER NOT NULL
                        )
                        """);
                if (!hasColumn(connection, "source_selection", "language_preference")) {
                    st.execute("ALTER TABLE source_selection ADD COLUMN language_preference TEXT NOT NULL DEFAULT 'AUTO'");
                    st.execute("UPDATE source_selection SET language_preference=CASE WHEN prefer_dub<>0 THEN 'ENGLISH_DUB' ELSE 'AUTO' END");
                }
                st.execute("""
                        CREATE TABLE IF NOT EXISTS source_series_match (
                          media_id INTEGER NOT NULL,
                          source_id TEXT NOT NULL,
                          provider_series_id TEXT NOT NULL,
                          name TEXT NOT NULL,
                          link TEXT NOT NULL DEFAULT '',
                          cover_url TEXT NOT NULL DEFAULT '',
                          other_names_json TEXT NOT NULL DEFAULT '[]',
                          total INTEGER,
                          extra_json TEXT NOT NULL DEFAULT '{}',
                          updated_at INTEGER NOT NULL,
                          PRIMARY KEY(media_id, source_id)
                        )
                        """);
                st.execute("""
                        CREATE TABLE IF NOT EXISTS playback_progress (
                          media_id INTEGER NOT NULL,
                          source_id TEXT NOT NULL,
                          episode_number TEXT NOT NULL,
                          position_ms INTEGER NOT NULL,
                          duration_ms INTEGER NOT NULL,
                          watched INTEGER NOT NULL DEFAULT 0,
                          updated_at INTEGER NOT NULL,
                          PRIMARY KEY(media_id, source_id, episode_number)
                        )
                        """);
                st.execute("""
                        CREATE TABLE IF NOT EXISTS kv (
                          key TEXT PRIMARY KEY,
                          value TEXT NOT NULL
                        )
                        """);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Unable to initialize SQLite database", e);
        }
    }

    public synchronized Connection connection() {
        if (connection == null) initialize();
        return connection;
    }

    private static boolean hasColumn(Connection connection, String table, String column) throws SQLException {
        try (var ps = connection.prepareStatement("PRAGMA table_info(" + table + ")");
             var rs = ps.executeQuery()) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) return true;
            }
        }
        return false;
    }

    @Override
    public synchronized void close() {
        if (connection != null) {
            try { connection.close(); } catch (SQLException ignored) {}
            connection = null;
        }
    }
}
