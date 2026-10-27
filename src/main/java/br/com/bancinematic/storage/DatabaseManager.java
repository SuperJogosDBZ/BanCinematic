package br.com.bancinematic.storage;

import br.com.bancinematic.BanCinematicPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

public final class DatabaseManager implements AutoCloseable {
    private static final long MYSQL_VALIDATION_IDLE_NANOS = TimeUnit.MINUTES.toNanos(1);
    @FunctionalInterface
    public interface SqlOperation<T> {
        T execute(Connection connection) throws SQLException;
    }

    private final BanCinematicPlugin plugin;
    private final boolean sqlite;
    private final String jdbcUrl;
    private final Properties properties = new Properties();
    private final String tablePrefix;
    private Connection mysqlConnection;
    private final LongSupplier nanoTime;
    private long mysqlLastUsedNanos;
    private boolean closed;

    public DatabaseManager(BanCinematicPlugin plugin) throws SQLException {
        this(plugin, System::nanoTime);
    }

    DatabaseManager(BanCinematicPlugin plugin, LongSupplier nanoTime) throws SQLException {
        this.nanoTime = nanoTime;
        this.plugin = plugin;
        tablePrefix = plugin.getConfig().getString("database.table-prefix", "");
        if (tablePrefix == null || !tablePrefix.matches("[a-zA-Z0-9_]{0,32}")) {
            throw new SQLException("database.table-prefix deve conter até 32 letras, números ou sublinhados.");
        }
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
            String passwordFromEnvironment = System.getenv("BANCINEMATIC_MYSQL_PASSWORD");
            properties.setProperty("password", passwordFromEnvironment != null ? passwordFromEnvironment
                    : plugin.getConfig().getString("database.mysql.password", ""));
            loadDriver("com.mysql.cj.jdbc.Driver");
        } else {
            throw new SQLException("database.type deve ser SQLITE ou MYSQL (valor atual: " + type + ").");
        }
    }

    public void initialize() throws SQLException {
        execute(connection -> {
            try (Statement statement = connection.createStatement()) {
                if (sqlite) {
                    // WAL persiste no arquivo; cada conexão mantém synchronous no padrão FULL.
                    statement.execute(sql("PRAGMA journal_mode=WAL"));
                }
                statement.execute(sql("CREATE TABLE IF NOT EXISTS ban_records ("
                        + "subject_uuid VARCHAR(36) PRIMARY KEY NOT NULL, name VARCHAR(64) NOT NULL,"
                        + "reason TEXT NOT NULL, source VARCHAR(128) NOT NULL, expires_at BIGINT)"));
                statement.execute(sql("CREATE TABLE IF NOT EXISTS mute_records ("
                        + "subject_uuid VARCHAR(36) PRIMARY KEY NOT NULL, name VARCHAR(64) NOT NULL,"
                        + "reason TEXT NOT NULL, source VARCHAR(128) NOT NULL, expires_at BIGINT)"));
                statement.execute(sql("CREATE TABLE IF NOT EXISTS punishment_history ("
                        + "id VARCHAR(64) NOT NULL PRIMARY KEY, subject_uuid VARCHAR(36) NOT NULL,"
                        + "punishment_type VARCHAR(24) NOT NULL, player VARCHAR(64) NOT NULL,"
                        + "reason TEXT NOT NULL, source VARCHAR(128) NOT NULL,"
                        + "punished_at VARCHAR(40) NOT NULL, duration_seconds BIGINT)"));
                statement.execute(sql("CREATE TABLE IF NOT EXISTS player_names ("
                        + "normalized_name VARCHAR(64) NOT NULL PRIMARY KEY, player_uuid VARCHAR(36) NOT NULL,"
                        + "name VARCHAR(64) NOT NULL, last_seen BIGINT NOT NULL)"));
                ensureIndex(connection, "punishment_history", "idx_history_subject_date_id", "subject_uuid, punished_at, id");
                String nameColumn = sqlite ? "name COLLATE NOCASE" : "name";
                ensureIndex(connection, "ban_records", "idx_ban_records_name", nameColumn);
                ensureIndex(connection, "mute_records", "idx_mute_records_name", nameColumn);
            }
            return null;
        });

        plugin.getLogger().info("Banco de dados inicializado usando " + (sqlite ? "SQLite" : "MySQL") + ".");
    }

    private void ensureIndex(Connection connection, String table, String name, String columns) throws SQLException {
        String indexName = tablePrefix + name;
        try (ResultSet indexes = connection.getMetaData().getIndexInfo(
                connection.getCatalog(), null, tablePrefix + table, false, false)) {
            while (indexes.next()) {
                if (indexName.equalsIgnoreCase(indexes.getString("INDEX_NAME"))) return;
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql("CREATE INDEX " + indexName + " ON " + table + " (" + columns + ")"));
        }
    }

    public String sql(String query) {
        if (tablePrefix.isEmpty()) return query;
        return query.replaceAll("\\b(ban_records|mute_records|punishment_history|player_names)\\b",
                tablePrefix + "$1");
    }

    /** Abre uma conexão independente; o chamador deve fechá-la. Operações do plugin usam execute. */
    public Connection connect() throws SQLException {
        Connection connection = properties.isEmpty()
                ? DriverManager.getConnection(jdbcUrl)
                : DriverManager.getConnection(jdbcUrl, properties);
        try {
            if (sqlite) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute(sql("PRAGMA busy_timeout=5000"));
                }
            } else {
                // Evita gap locks ao remover expirados antes de um INSERT concorrente.
                connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            }
            return connection;
        } catch (SQLException error) {
            try { connection.close(); } catch (SQLException closeError) { error.addSuppressed(closeError); }
            throw error;
        }
    }

    public synchronized <T> T execute(SqlOperation<T> operation) throws SQLException {
        if (closed) throw new SQLException("O banco de dados já foi fechado.");
        if (sqlite) {
            try (Connection connection = connect()) {
                return operation.execute(connection);
            }
        }
        if (!isMysqlConnectionUsable()) {
            closeMysqlConnection();
            mysqlConnection = connect();
        }
        try {
            return operation.execute(mysqlConnection);
        } catch (SQLException | RuntimeException | Error error) {
            // Não repetir escritas: um erro de rede pode ocorrer depois do commit.
            closeMysqlConnection();
            throw error;
        } finally {
            if (mysqlConnection != null) {
                try {
                    if (!mysqlConnection.getAutoCommit()) {
                        mysqlConnection.rollback();
                        mysqlConnection.setAutoCommit(true);
                    }
                } catch (SQLException error) {
                    closeMysqlConnection();
                    plugin.getLogger().warning("Conexão MySQL descartada ao restaurar a transação: "
                            + error.getMessage());
                }
                if (mysqlConnection != null) mysqlLastUsedNanos = nanoTime.getAsLong();
            }
        }
    }

    public <T> T transaction(SqlOperation<T> operation) throws SQLException {
        return execute(connection -> {
            connection.setAutoCommit(false);
            try {
                T result = operation.execute(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException | Error error) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackError) {
                    error.addSuppressed(rollbackError);
                }
                throw error;
            }
        });
    }

    static boolean isDuplicateKey(SQLException error) {
        if (error.getErrorCode() == 1062 && "23000".equals(error.getSQLState())) return true;
        if (error instanceof org.sqlite.SQLiteException sqliteError) {
            return sqliteError.getResultCode() == org.sqlite.SQLiteErrorCode.SQLITE_CONSTRAINT_PRIMARYKEY
                    || sqliteError.getResultCode() == org.sqlite.SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE;
        }
        return false;
    }

    boolean isSqlite() { return sqlite; }

    private boolean isMysqlConnectionUsable() {
        try {
            if (mysqlConnection == null || mysqlConnection.isClosed()) return false;
            // Uma operação recente já confirmou a conexão; validar só após ociosidade.
            return nanoTime.getAsLong() - mysqlLastUsedNanos < MYSQL_VALIDATION_IDLE_NANOS
                    || mysqlConnection.isValid(2);
        } catch (SQLException error) {
            return false;
        }
    }

    private void closeMysqlConnection() {
        if (mysqlConnection == null) return;
        try {
            mysqlConnection.close();
        } catch (SQLException error) {
            plugin.getLogger().warning("Não foi possível fechar a conexão MySQL: " + error.getMessage());
        } finally {
            mysqlConnection = null;
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        closeMysqlConnection();
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
