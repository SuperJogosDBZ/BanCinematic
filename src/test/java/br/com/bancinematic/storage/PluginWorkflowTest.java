package br.com.bancinematic.storage;

import br.com.bancinematic.BanCinematicPlugin;
import br.com.bancinematic.punishment.BanData;
import br.com.bancinematic.punishment.BanManager;
import br.com.bancinematic.punishment.MuteData;
import br.com.bancinematic.punishment.MuteManager;
import br.com.bancinematic.punishment.PunishmentResult;
import br.com.bancinematic.util.MessageUtil;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PluginWorkflowTest {
    @TempDir
    Path temporaryDirectory;

    private BanCinematicPlugin plugin;
    private DatabaseManager database;
    private YamlConfiguration config;

    @BeforeEach
    void setUp() throws SQLException {
        plugin = mock(BanCinematicPlugin.class);
        var dataFolder = temporaryDirectory.resolve("plugin-data").toFile();
        assertTrue(dataFolder.mkdirs() || dataFolder.isDirectory());
        config = new YamlConfiguration();
        config.set("database.type", "SQLITE");
        config.set("messages.muted-chat", "MUTED:%reason%:%time%");
        config.set("messages.muted-chat-permanent", "MUTED_PERMANENT:%reason%");
        config.set("messages.tempmute-notice", "TEMPMUTE:%reason%:%time%");
        config.set("messages.mute-notice", "MUTE:%reason%");
        config.set("messages.mute-reminder", "REMINDER:%reason%:%time%");
        config.set("messages.mute-reminder-permanent", "REMINDER_PERMANENT:%reason%");
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getLanguageConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(dataFolder);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("BanCinematic-test"));
        when(plugin.messages()).thenReturn(new MessageUtil(plugin));

        database = new DatabaseManager(plugin);
    }

    @Test
    void freshSqliteDatabaseSupportsIdentitiesPunishmentsAndHistory() throws SQLException {
        database.initialize();
        UUID uuid = UUID.randomUUID();
        IdentityStorage identities = new IdentityStorage(plugin, database);

        assertTrue(identities.record(uuid, "Taupaipai"));
        assertTrue(identities.record(uuid, "TaupaipaiNovo"));
        assertEquals(uuid, identities.resolve("taupaipai"));
        assertEquals(uuid, identities.resolve("TAUPAIPAInovo"));
        assertNull(identities.resolve("NuncaEntrou"));
        assertEquals(uuid, new IdentityStorage(plugin, database).resolve("taupaipai"),
                "Nick antigo deve continuar relacionado à mesma UUID após recarregar do banco.");

        BanStorage bans = new BanStorage(plugin, database);
        MuteStorage mutes = new MuteStorage(plugin, database);
        PunishmentLogger history = new PunishmentLogger(plugin, database);
        BanManager banManager = new BanManager(plugin, bans, history, DatabaseExecutor.inline());
        MuteManager muteManager = new MuteManager(plugin, mutes, history, DatabaseExecutor.inline());

        String admin = "Admin123";
        assertEquals(PunishmentResult.SUCCESS, banManager.ban(uuid, "TaupaipaiNovo", "teste ban", admin).join());
        assertTrue(banManager.isBanned(uuid));
        assertEquals("teste ban", banManager.get(uuid).reason());
        assertTrue(new BanStorage(plugin, database).isBanned(uuid), "Ban precisa sobreviver a um reload.");
        assertEquals(PunishmentResult.SUCCESS, banManager.unban(uuid, "TaupaipaiNovo", admin).join());
        assertFalse(banManager.isBanned(uuid));

        assertEquals(PunishmentResult.SUCCESS, muteManager.tempMute(uuid, "TaupaipaiNovo", "spam", admin, 120).join());
        MuteData mute = muteManager.get(uuid);
        assertNotNull(mute);
        assertTrue(muteManager.isMuted(uuid));
        assertTrue(muteManager.mutedChatMessage(mute).startsWith("MUTED:spam:"));
        assertTrue(muteManager.muteNotice(mute).startsWith("TEMPMUTE:spam:"));
        assertTrue(muteManager.muteReminder(mute).startsWith("REMINDER:spam:"));
        assertTrue(new MuteStorage(plugin, database).isMuted(uuid), "Mute precisa sobreviver a um reload.");
        assertEquals(PunishmentResult.SUCCESS, muteManager.unmute(uuid, "TaupaipaiNovo", admin).join());
        assertFalse(muteManager.isMuted(uuid));

        UUID permanentMuteId = UUID.randomUUID();
        assertEquals(PunishmentResult.SUCCESS, muteManager.mute(permanentMuteId, "Permanente", "reincidência", "CONSOLE").join());
        MuteData permanentMute = muteManager.get(permanentMuteId);
        assertEquals("MUTE:reincidência", muteManager.muteNotice(permanentMute));
        assertEquals("REMINDER_PERMANENT:reincidência", muteManager.muteReminder(permanentMute));
        assertEquals("MUTED_PERMANENT:reincidência", muteManager.mutedChatMessage(permanentMute));
        assertEquals(PunishmentResult.SUCCESS, muteManager.unmute(permanentMuteId, "Permanente", "CONSOLE").join());

        var entries = history.getHistoryPage(uuid, 0, 8).entries();
        assertEquals(4, entries.size());
        assertEquals("UNMUTE", entries.get(0).type());
        assertEquals("TEMPMUTE", entries.get(1).type());
        assertEquals("UNBAN", entries.get(2).type());
        assertEquals("BAN", entries.get(3).type());
        assertEquals("Admin123", entries.get(3).source());
        assertEquals("CONSOLE", history.getHistoryPage(permanentMuteId, 0, 8).entries().get(0).source());

        UUID temporaryBanId = UUID.randomUUID();
        assertEquals(PunishmentResult.SUCCESS, banManager.tempBan(temporaryBanId, "TempBanido", "griefing", admin, 120).join());
        var temporaryBanHistory = history.getHistoryPage(temporaryBanId, 0, 8).entries().get(0);
        assertEquals("TEMPBAN", temporaryBanHistory.type());
        assertEquals(admin, temporaryBanHistory.source());
    }

    @Test
    void expiredPunishmentsStopApplyingAndAreRemovedFromStorage() throws SQLException {
        database.initialize();
        UUID banId = UUID.randomUUID();
        UUID muteId = UUID.randomUUID();
        BanStorage bans = new BanStorage(plugin, database);
        MuteStorage mutes = new MuteStorage(plugin, database);
        Instant expired = Instant.now().minusSeconds(5);

        assertTrue(bans.put(new BanData(banId, "Banido", "teste", "console", expired)));
        assertNull(bans.get(banId), "Ban expirado não deve mais valer.");
        assertEquals(1, countRows("ban_records", banId), "A leitura não deve mais escrever no banco.");
        bans.cleanupExpired();
        assertEquals(0, countRows("ban_records", banId));

        assertTrue(mutes.put(new MuteData(muteId, "Mutado", "teste", "console", expired)));
        assertFalse(mutes.isMuted(muteId));
        mutes.cleanupExpired();
        assertEquals(0, countRows("mute_records", muteId));
    }

    @Test
    void duplicateAndMissingPunishmentsAreReportedWithoutChangingHistory() throws SQLException {
        database.initialize();
        UUID uuid = UUID.randomUUID();
        PunishmentLogger history = new PunishmentLogger(plugin, database);
        BanManager bans = new BanManager(plugin, new BanStorage(plugin, database), history,
                DatabaseExecutor.inline());
        MuteManager mutes = new MuteManager(plugin, new MuteStorage(plugin, database), history,
                DatabaseExecutor.inline());

        assertEquals(PunishmentResult.NOT_ACTIVE, bans.unban(uuid, "Alvo", "CONSOLE").join());
        assertEquals(PunishmentResult.NOT_ACTIVE, mutes.unmute(uuid, "Alvo", "CONSOLE").join());

        assertEquals(PunishmentResult.SUCCESS, bans.ban(uuid, "Alvo", "spam", "CONSOLE").join());
        assertEquals(PunishmentResult.ALREADY_ACTIVE, bans.ban(uuid, "Alvo", "outra", "CONSOLE").join());
        assertEquals(PunishmentResult.ALREADY_ACTIVE, bans.tempBan(uuid, "Alvo", "outra", "CONSOLE", 60).join());
        assertEquals("spam", bans.get(uuid).reason(), "O ban original não pode ser sobrescrito.");

        assertEquals(PunishmentResult.SUCCESS, mutes.mute(uuid, "Alvo", "spam", "CONSOLE").join());
        assertEquals(PunishmentResult.ALREADY_ACTIVE, mutes.mute(uuid, "Alvo", "outra", "CONSOLE").join());

        assertEquals(2, history.getHistoryPage(uuid, 0, 8).totalEntries(),
                "Só o ban e o mute bem-sucedidos entram no histórico.");
    }

    @Test
    void banStorageReadsWorkWhileTheDatabaseThreadIsBusy() throws Exception {
        database.initialize();
        UUID uuid = UUID.randomUUID();
        BanStorage bans = new BanStorage(plugin, database);
        assertTrue(bans.put(new BanData(uuid, "Alvo", "teste", "console", null)));

        java.util.concurrent.CountDownLatch locked = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Thread writer = new Thread(() -> {
            synchronized (bans) {
                locked.countDown();
                try {
                    release.await();
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        writer.start();
        try {
            assertTrue(locked.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(2),
                    () -> assertNotNull(bans.get(uuid)),
                    "O login não pode esperar por uma gravação no banco.");
        } finally {
            release.countDown();
            writer.join();
        }
    }

    @Test
    void mysqlJdbcDriverIsAvailableWhenMysqlIsSelected() {
        config.set("database.type", "MYSQL");
        assertDoesNotThrow(() -> new DatabaseManager(plugin));
    }

    @Test
    void mysqlConnectionTimeoutsArePassedToTheJdbcUrl() throws Exception {
        config.set("database.type", "MYSQL");
        config.set("database.mysql.connect-timeout", 4321);
        config.set("database.mysql.socket-timeout", 8765);

        DatabaseManager manager = new DatabaseManager(plugin);
        var field = DatabaseManager.class.getDeclaredField("jdbcUrl");
        field.setAccessible(true);
        String jdbcUrl = (String) field.get(manager);

        assertTrue(jdbcUrl.contains("connectTimeout=4321"));
        assertTrue(jdbcUrl.contains("socketTimeout=8765"));
    }

    @Test
    void negativeMysqlTimeoutsFallBackToSafeDefaults() throws Exception {
        config.set("database.type", "MYSQL");
        config.set("database.mysql.connect-timeout", -1);
        config.set("database.mysql.socket-timeout", -50);

        DatabaseManager manager = new DatabaseManager(plugin);
        var field = DatabaseManager.class.getDeclaredField("jdbcUrl");
        field.setAccessible(true);
        String jdbcUrl = (String) field.get(manager);

        assertTrue(jdbcUrl.contains("connectTimeout=5000"));
        assertTrue(jdbcUrl.contains("socketTimeout=10000"));
    }

    @Test
    void simultaneousBansForOnePlayerCreateOnlyOneRecord() throws SQLException {
        database.initialize();
        UUID uuid = UUID.randomUUID();
        try (DatabaseExecutor executor = DatabaseExecutor.create()) {
            PunishmentLogger history = new PunishmentLogger(plugin, database);
            BanManager manager = new BanManager(plugin, new BanStorage(plugin, database), history, executor);

            CompletableFuture<PunishmentResult> first = manager.ban(uuid, "Alvo", "primeiro", "staff-a");
            CompletableFuture<PunishmentResult> second = manager.ban(uuid, "Alvo", "segundo", "staff-b");
            CompletableFuture.allOf(first, second).join();

            long successes = java.util.stream.Stream.of(first.join(), second.join())
                    .filter(result -> result == PunishmentResult.SUCCESS)
                    .count();
            long alreadyActive = java.util.stream.Stream.of(first.join(), second.join())
                    .filter(result -> result == PunishmentResult.ALREADY_ACTIVE)
                    .count();

            assertEquals(1, successes);
            assertEquals(1, alreadyActive);
            assertEquals(1, history.getHistoryPage(uuid, 0, 8).totalEntries());
        }
    }

    @Test
    void failedHistoryWriteRollsBackNewBanAndRestoresRemovedBan() throws SQLException {
        database.initialize();
        UUID uuid = UUID.randomUUID();
        BanStorage storage = new BanStorage(plugin, database);
        PunishmentLogger history = mock(PunishmentLogger.class);
        BanManager manager = new BanManager(plugin, storage, history, DatabaseExecutor.inline());

        when(history.log(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(false);

        assertEquals(PunishmentResult.STORAGE_ERROR,
                manager.ban(uuid, "Alvo", "teste", "CONSOLE").join());
        assertNull(manager.get(uuid), "Um ban sem histórico não deve permanecer ativo.");

        assertTrue(storage.put(new BanData(uuid, "Alvo", "teste", "staff", null)));
        assertEquals(PunishmentResult.STORAGE_ERROR,
                manager.unban(uuid, "Alvo", "CONSOLE").join());
        assertNotNull(manager.get(uuid), "Falha ao registrar /unban deve restaurar o ban anterior.");
    }

    @Test
    void mysqlConnectionWorksWhenIntegrationEnvironmentIsConfigured() throws SQLException {
        configureMysqlFromEnvironment();

        try (Connection connection = new DatabaseManager(plugin).connect();
             PreparedStatement statement = connection.prepareStatement("SELECT 1");
             ResultSet result = statement.executeQuery()) {
            assertTrue(result.next());
            assertEquals(1, result.getInt(1));
        }
    }

    @Test
    void mysqlSchemaSupportsPunishmentHistorySourcesWhenIntegrationEnvironmentIsConfigured() throws SQLException {
        configureMysqlFromEnvironment();
        DatabaseManager mysql = new DatabaseManager(plugin);
        mysql.initialize();

        UUID uuid = UUID.randomUUID();
        BanStorage bans = new BanStorage(plugin, mysql);
        MuteStorage mutes = new MuteStorage(plugin, mysql);
        PunishmentLogger history = new PunishmentLogger(plugin, mysql);
        try {
            BanData ban = new BanData(uuid, "MySqlIntegration", "teste", "JUnit", null);
            assertTrue(bans.put(ban));
            assertEquals("teste", bans.get(uuid).reason());
            assertTrue(bans.remove(uuid));
            assertNull(bans.get(uuid));

            MuteData mute = new MuteData(uuid, "MySqlIntegration", "teste", "JUnit", null);
            assertTrue(mutes.put(mute));
            assertEquals("teste", mutes.get(uuid).reason());
            assertTrue(mutes.remove(uuid));
            assertNull(mutes.get(uuid));

            assertTrue(history.log(br.com.bancinematic.punishment.PunishmentType.BAN, uuid,
                    "MySqlIntegration", "teste", "MySqlAdmin", null));
            assertTrue(history.log(br.com.bancinematic.punishment.PunishmentType.KICK, uuid,
                    "MySqlIntegration", "teste", "CONSOLE", null));
            var entries = history.getHistoryPage(uuid, 0, 8).entries();
            assertEquals(2, entries.size());
            var consoleEntry = entries.stream()
                    .filter(entry -> "CONSOLE".equals(entry.source())).findFirst().orElseThrow();
            var playerEntry = entries.stream()
                    .filter(entry -> "MySqlAdmin".equals(entry.source())).findFirst().orElseThrow();
            assertEquals("CONSOLE", consoleEntry.source());
            assertEquals("MySqlAdmin", playerEntry.source());
        } finally {
            try (Connection connection = mysql.connect()) {
                deleteTestRecord(connection, "ban_records", "subject_uuid", uuid);
                deleteTestRecord(connection, "mute_records", "subject_uuid", uuid);
                deleteTestRecord(connection, "punishment_history", "subject_uuid", uuid);
            }
        }
    }

    private void configureMysqlFromEnvironment() {
        String host = System.getenv("BANCINEMATIC_TEST_MYSQL_HOST");
        String databaseName = System.getenv("BANCINEMATIC_TEST_MYSQL_DATABASE");
        String username = System.getenv("BANCINEMATIC_TEST_MYSQL_USERNAME");
        String password = System.getenv("BANCINEMATIC_TEST_MYSQL_PASSWORD");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                host != null && databaseName != null && username != null && password != null,
                "Configure as variáveis BANCINEMATIC_TEST_MYSQL_* para executar a integração MySQL."
        );

        config.set("database.type", "MYSQL");
        config.set("database.mysql.host", host);
        config.set("database.mysql.port", Integer.parseInt(
                System.getenv().getOrDefault("BANCINEMATIC_TEST_MYSQL_PORT", "3306")));
        config.set("database.mysql.database", databaseName);
        config.set("database.mysql.username", username);
        config.set("database.mysql.password", password);
    }

    private static void deleteTestRecord(Connection connection, String table, String column, UUID uuid)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM " + table + " WHERE " + column + " = ?")) {
            statement.setString(1, uuid.toString());
            statement.executeUpdate();
        }
    }

    private int countRows(String table, UUID uuid) throws SQLException {
        String query = "SELECT COUNT(*) FROM " + table + (uuid == null ? "" : " WHERE subject_uuid = ?");
        try (Connection connection = database.connect(); PreparedStatement statement = connection.prepareStatement(query)) {
            if (uuid != null) statement.setString(1, uuid.toString());
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

}
