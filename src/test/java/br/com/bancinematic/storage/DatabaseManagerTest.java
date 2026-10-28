package br.com.bancinematic.storage;

import br.com.bancinematic.BanCinematicPlugin;
import br.com.bancinematic.punishment.PunishmentData;
import br.com.bancinematic.punishment.PunishmentType;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DatabaseManagerTest {
    @TempDir Path directory;
    private BanCinematicPlugin plugin;
    private YamlConfiguration config;

    @BeforeEach
    void setUp() {
        plugin = mock(BanCinematicPlugin.class);
        config = new YamlConfiguration();
        config.set("database.type", "MYSQL");
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("database-test"));
    }

    private Connection healthyConnection() throws SQLException {
        Connection connection = mock(Connection.class);
        when(connection.isValid(2)).thenReturn(true);
        when(connection.getAutoCommit()).thenReturn(true);
        return connection;
    }

    @Test
    void tablePrefixIsValidatedAndKeepsAllTablesIsolated() throws Exception {
        config.set("database.type", "SQLITE");
        for (String invalid : new String[]{"bad-prefix", "bad;DROP", "a".repeat(33)}) {
            config.set("database.table-prefix", invalid);
            assertThrows(SQLException.class, () -> new DatabaseManager(plugin));
        }
        config.set("database.table-prefix", "bc_test_");
        try (DatabaseManager database = new DatabaseManager(plugin)) {
            database.initialize();
            assertTrue(new IdentityStorage(plugin, database).record(UUID.randomUUID(), "Isolado"));
            try (var connection = database.connect(); var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT name FROM sqlite_master WHERE type='table'")) {
                var names = new java.util.HashSet<String>();
                while (result.next()) names.add(result.getString(1));
                assertEquals(java.util.Set.of("bc_test_ban_records", "bc_test_mute_records",
                        "bc_test_punishment_history", "bc_test_player_names"), names);
            }
        }
    }

    @Test
    void sqliteKeepsWalFullSynchronousAndBusyTimeoutAfterReopening() throws Exception {
        config.set("database.type", "SQLITE");
        try (DatabaseManager database = new DatabaseManager(plugin)) {
            database.initialize();
            for (int i = 0; i < 2; i++) {
                database.execute(connection -> {
                    try (var statement = connection.createStatement()) {
                        try (var result = statement.executeQuery("PRAGMA journal_mode")) {
                            assertTrue(result.next());
                            assertEquals("wal", result.getString(1));
                        }
                        try (var result = statement.executeQuery("PRAGMA synchronous")) {
                            assertTrue(result.next());
                            assertEquals(2, result.getInt(1), "Conexões de operação devem manter FULL.");
                        }
                    }
                    try (var statement = connection.createStatement(); var result = statement.executeQuery("PRAGMA busy_timeout")) {
                        assertTrue(result.next());
                        assertEquals(5000, result.getInt(1));
                    }
                    return null;
                });
                if (i == 0) assertThrows(SQLException.class, () -> database.execute(c -> { throw new SQLException("forced reopen"); }));
            }
        }
    }

    @Test
    void sqliteReusesConnectionAndRollsBackFailedWritesWithoutReplay() throws Exception {
        config.set("database.type", "SQLITE");
        try (DatabaseManager database = spy(new DatabaseManager(plugin))) {
            database.initialize();
            Connection first = database.execute(c -> c);
            assertSame(first, database.execute(c -> c));
            verify(database).connect();
            assertFalse(first.isClosed());
            assertThrows(SQLException.class, () -> database.transaction(c -> {
                try (var statement = c.createStatement()) {
                    statement.executeUpdate("INSERT INTO player_names VALUES ('alvo', 'id', 'Alvo', 1)");
                }
                throw new SQLException("failed transaction");
            }));
            assertTrue(first.isClosed());
            Connection second = database.execute(c -> c);
            assertNotSame(first, second);
            assertTrue(second.getAutoCommit());
            assertEquals(0, database.execute(c -> {
                try (var statement = c.createStatement(); var result = statement.executeQuery("SELECT COUNT(*) FROM player_names")) {
                    result.next(); return result.getInt(1);
                }
            }).intValue());
            verify(database, times(2)).connect();
            second.close();
            assertNotSame(second, database.execute(c -> c));
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void bothBackendsReuseConnectionAndCloseOnlyAtShutdown(boolean useSqlite) throws Exception {
        if (useSqlite) config.set("database.type", "SQLITE");
        DatabaseManager database = spy(new DatabaseManager(plugin));
        Connection connection = healthyConnection();
        doReturn(connection).when(database).connect();
        try (database) {
            assertSame(connection, database.execute(c -> c));
            assertSame(connection, database.execute(c -> c));
            verify(database).connect();
            verify(connection, never()).close();
            verify(connection, never()).isValid(anyInt());
        }
        verify(connection).close();
        assertThrows(SQLException.class, () -> database.execute(c -> c));
    }

    @Test
    void mysqlReopensAnInvalidOrClosedConnection() throws Exception {
        for (boolean closed : new boolean[]{false, true}) {
            var clock = new java.util.concurrent.atomic.AtomicLong();
            DatabaseManager database = spy(new DatabaseManager(plugin, clock::get));
            Connection first = healthyConnection();
            Connection second = healthyConnection();
            doReturn(first, second).when(database).connect();
            try (database) {
                database.execute(c -> null);
                clock.set(java.util.concurrent.TimeUnit.SECONDS.toNanos(60));
                when(first.isClosed()).thenReturn(closed);
                when(first.isValid(2)).thenReturn(false);
                assertSame(second, database.execute(c -> c));
                verify(first).close();
                verify(database, times(2)).connect();
            }
        }
    }

    @Test
    void mysqlValidatesOnlyAfterOneMinuteWithoutUse() throws Exception {
        var clock = new java.util.concurrent.atomic.AtomicLong();
        DatabaseManager database = spy(new DatabaseManager(plugin, clock::get));
        Connection connection = healthyConnection();
        doReturn(connection).when(database).connect();
        try (database) {
            database.execute(c -> null);
            clock.set(java.util.concurrent.TimeUnit.SECONDS.toNanos(59));
            database.execute(c -> null);
            clock.set(java.util.concurrent.TimeUnit.SECONDS.toNanos(118));
            database.execute(c -> null);
            verify(connection, never()).isValid(anyInt());
            clock.set(java.util.concurrent.TimeUnit.SECONDS.toNanos(178));
            database.execute(c -> null);
            database.execute(c -> null);
            verify(connection).isValid(2);
            verify(database).connect();
        }
    }

    @Test
    void failedWriteIsNotRetriedAndTheNextOperationReconnects() throws Exception {
        DatabaseManager database = spy(new DatabaseManager(plugin));
        Connection first = healthyConnection();
        Connection second = healthyConnection();
        doReturn(first, second).when(database).connect();
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        try (database) {
            assertThrows(SQLException.class, () -> database.execute(c -> {
                attempts.incrementAndGet();
                throw new SQLException("conexão perdida após escrita");
            }));
            assertEquals(1, attempts.get());
            verify(first).close();
            assertSame(second, database.execute(c -> c));
        }
    }

    @Test
    void connectionStateIsResetAndBrokenResetDiscardsTheConnection() throws Exception {
        DatabaseManager database = spy(new DatabaseManager(plugin));
        Connection first = healthyConnection();
        Connection second = healthyConnection();
        doReturn(first, second).when(database).connect();
        when(first.getAutoCommit()).thenReturn(false);
        try (database) {
            database.execute(c -> null);
            verify(first).rollback();
            verify(first).setAutoCommit(true);
            doThrow(new SQLException("reset falhou")).when(first).setAutoCommit(true);
            assertEquals("committed", database.execute(c -> "committed"));
            verify(first).close();
            assertSame(second, database.execute(c -> c));
        }
    }

    @Test
    void completedTransactionDoesNotRollbackAndFailedTransactionRollsBackOnce() throws Exception {
        DatabaseManager database = spy(new DatabaseManager(plugin));
        Connection connection = healthyConnection();
        var autoCommit = new java.util.concurrent.atomic.AtomicBoolean(true);
        when(connection.getAutoCommit()).thenAnswer(call -> autoCommit.get());
        doAnswer(call -> { autoCommit.set(call.getArgument(0)); return null; })
                .when(connection).setAutoCommit(anyBoolean());
        doReturn(connection).when(database).connect();
        try (database) {
            assertEquals("saved", database.transaction(c -> "saved"));
            verify(connection).commit();
            verify(connection, never()).rollback();
            assertTrue(autoCommit.get());
            assertThrows(SQLException.class, () -> database.transaction(c -> { throw new SQLException("failed"); }));
            verify(connection).rollback();
            verify(connection).commit();
            verify(connection).close();
        }
    }

    @Test
    void duplicatePunishmentRollsBackWithoutCommittingOrDiscardingConnection() throws Exception {
        DatabaseManager database = spy(new DatabaseManager(plugin));
        Connection connection = healthyConnection();
        var autoCommit = new java.util.concurrent.atomic.AtomicBoolean(true);
        when(connection.getAutoCommit()).thenAnswer(call -> autoCommit.get());
        doAnswer(call -> { autoCommit.set(call.getArgument(0)); return null; })
                .when(connection).setAutoCommit(anyBoolean());
        var statement = mock(java.sql.PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeUpdate()).thenReturn(0).thenThrow(new SQLException("duplicate", "23000", 1062));
        doReturn(connection).when(database).connect();
        try (database) {
            var storage = new PunishmentStorage(plugin, database, PunishmentStorage.Kind.BAN);
            var history = mock(PunishmentLogger.class);
            assertEquals(br.com.bancinematic.punishment.PunishmentResult.ALREADY_ACTIVE,
                    storage.putAndLog(new PunishmentData(UUID.randomUUID(), "Alvo", "reason", "A", null),
                            PunishmentType.BAN, null, history));
            verify(connection).rollback();
            verify(connection, never()).commit();
            verify(connection, never()).close();
            assertTrue(autoCommit.get());
            assertEquals(0, storage.revision());
            verifyNoInteractions(history);
            assertSame(connection, database.execute(c -> c));
            verify(database).connect();
        }
    }

    @Test
    void sharedConnectionKeepsPunishmentAndHistoryTransactionsIndependent() throws Exception {
        config.set("database.type", "SQLITE");
        DatabaseManager sqlite = new DatabaseManager(plugin);
        sqlite.initialize();
        Connection physical = sqlite.connect();
        config.set("database.type", "MYSQL");
        DatabaseManager database = spy(new DatabaseManager(plugin));
        doReturn(physical).when(database).connect();
        try (database) {
            PunishmentStorage bans = new PunishmentStorage(plugin, database, PunishmentStorage.Kind.BAN);
            PunishmentLogger history = new PunishmentLogger(plugin, database);
            UUID uuid = UUID.randomUUID();
            assertEquals(br.com.bancinematic.punishment.PunishmentResult.SUCCESS, bans.putAndLog(new PunishmentData(uuid, "Alvo", "regra", "CONSOLE", null),
                    PunishmentType.BAN, null, history));
            assertTrue(physical.getAutoCommit());
            UUID kickId = UUID.randomUUID();
            assertTrue(history.log(PunishmentType.KICK, kickId, "Outro", "regra", "CONSOLE", null));
            assertEquals(br.com.bancinematic.punishment.PunishmentResult.SUCCESS, bans.removeAndLog(uuid, "Alvo", "Removido", "CONSOLE", PunishmentType.UNBAN, history));
            try (var independent = sqlite.connect(); var statement = independent.createStatement();
                 var result = statement.executeQuery("SELECT COUNT(*) FROM punishment_history")) {
                assertTrue(result.next());
                assertEquals(3, result.getInt(1));
            }
            verify(database).connect();
        }
        assertTrue(physical.isClosed());
        sqlite.close();
    }
}
