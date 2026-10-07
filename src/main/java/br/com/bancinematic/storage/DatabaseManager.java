package br.com.bancinematic.storage;

import br.com.bancinematic.BanCinematicPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Properties;

public final class DatabaseManager {
    private final BanCinematicPlugin plugin;
    private final boolean sqlite;
    private final String jdbcUrl;
    private final Properties properties = new Properties();

    public DatabaseManager(BanCinematicPlugin plugin) throws SQLException {
        this.plugin = plugin;
        String type = plugin.getConfig().getString("database.type", "SQLITE");
        type = type == null ? "SQLITE" : type.trim().toUpperCase(Locale.ROOT);

        if (type.equals("SQLITE")) {
            sqlite = true;
            File databaseFile = new File(plugin.getDataFolder(), "bancinematic.db");
            jdbcUrl = "jdbc:sqlite:" + databaseFile.getAbsolutePath();
            loadDriver("org.sqlite.JDBC");
        } else if (type.equals("MYSQL")) {
            sqlite = false;
            String host = plugin.getConfig().getString("database.mysql.host", "127.0.0.1");
            int port = plugin.getConfig().getInt("database.mysql.port", 3306);
            String database = plugin.getConfig().getString("database.mysql.database", "bancinematic");
            boolean useSsl = plugin.getConfig().getBoolean("database.mysql.use-ssl", false);
            boolean allowPublicKeyRetrieval = plugin.getConfig()
                    .getBoolean("database.mysql.allow-public-key-retrieval", false);
            int connectTimeout = timeout("database.mysql.connect-timeout", 5000);
            int socketTimeout = timeout("database.mysql.socket-timeout", 10000);
            jdbcUrl = "jdbc:mysql://" + host + ":" + port + "/" + database
                    + "?useSSL=" + useSsl
                    + "&allowPublicKeyRetrieval=" + allowPublicKeyRetrieval
                    + "&serverTimezone=UTC&characterEncoding=UTF-8"
                    + "&connectTimeout=" + connectTimeout
                    + "&socketTimeout=" + socketTimeout;
            properties.setProperty("user", plugin.getConfig().getString("database.mysql.username", "root"));
            properties.setProperty("password", plugin.getConfig().getString("database.mysql.password", ""));
            loadDriver("com.mysql.cj.jdbc.Driver");
        } else {
            throw new SQLException("database.type deve ser SQLITE ou MYSQL (valor atual: " + type + ").");
        }
    }

    public void initialize() throws SQLException {
        try (Connection connection = connect(); Statement statement = connection.createStatement()) {
            if (sqlite) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA synchronous=NORMAL");
            }

            statement.execute("CREATE TABLE IF NOT EXISTS ban_records ("
                    + "subject_uuid VARCHAR(36) PRIMARY KEY NOT NULL, name VARCHAR(64) NOT NULL,"
                    + "reason TEXT NOT NULL, source VARCHAR(128) NOT NULL, expires_at BIGINT)");
            statement.execute("CREATE TABLE IF NOT EXISTS mute_records ("
                    + "subject_uuid VARCHAR(36) PRIMARY KEY NOT NULL, name VARCHAR(64) NOT NULL,"
                    + "reason TEXT NOT NULL, source VARCHAR(128) NOT NULL, expires_at BIGINT)");
            statement.execute("CREATE TABLE IF NOT EXISTS punishment_history ("
                    + "id VARCHAR(64) NOT NULL PRIMARY KEY, subject_uuid VARCHAR(36) NOT NULL,"
                    + "punishment_type VARCHAR(24) NOT NULL, player VARCHAR(64) NOT NULL,"
                    + "reason TEXT NOT NULL, source VARCHAR(128) NOT NULL,"
                    + "punished_at VARCHAR(40) NOT NULL, duration VARCHAR(64))");
            statement.execute("CREATE TABLE IF NOT EXISTS player_names ("
                    + "normalized_name VARCHAR(64) NOT NULL PRIMARY KEY, player_uuid VARCHAR(36) NOT NULL,"
                    + "name VARCHAR(64) NOT NULL, last_seen BIGINT NOT NULL)");

            ensureHistoryIndex(connection);
        }

        plugin.getLogger().info("Banco de dados inicializado usando " + (sqlite ? "SQLite" : "MySQL") + ".");
    }

    private static void ensureHistoryIndex(Connection connection) throws SQLException {
        String indexName = "idx_history_subject_date_id";
        try (ResultSet indexes = connection.getMetaData().getIndexInfo(
                connection.getCatalog(), null, "punishment_history", false, false)) {
            while (indexes.next()) {
                if (indexName.equalsIgnoreCase(indexes.getString("INDEX_NAME"))) return;
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE INDEX " + indexName
                    + " ON punishment_history (subject_uuid, punished_at, id)");
        }
    }

    public Connection connect() throws SQLException {
        Connection connection = properties.isEmpty()
                ? DriverManager.getConnection(jdbcUrl)
                : DriverManager.getConnection(jdbcUrl, properties);
        if (sqlite) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA busy_timeout=5000");
            }
        }
        return connection;
    }

    private void loadDriver(String className) throws SQLException {
        try {
            Class.forName(className);
        } catch (ClassNotFoundException exception) {
            throw new SQLException("A biblioteca JDBC não foi carregada pelo Paper: " + className, exception);
        }
    }

    private int timeout(String path, int defaultValue) {
        int value = plugin.getConfig().getInt(path, defaultValue);
        if (value >= 0) return value;

        plugin.getLogger().warning(path + " não pode ser negativo; usando " + defaultValue + " ms.");
        return defaultValue;
    }
}
