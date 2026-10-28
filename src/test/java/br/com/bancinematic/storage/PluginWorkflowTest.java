package br.com.bancinematic.storage;

import br.com.bancinematic.BanCinematicPlugin;
import br.com.bancinematic.punishment.PunishmentData;
import br.com.bancinematic.punishment.BanManager;
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
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
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
        assertFalse(tableExists("player_identities"),
                "A tabela não utilizada de identidade atual não deve ser criada.");

        PunishmentStorage bans = new PunishmentStorage(plugin, database, PunishmentStorage.Kind.BAN);
        PunishmentStorage mutes = new PunishmentStorage(plugin, database, PunishmentStorage.Kind.MUTE);
        PunishmentLogger history = new PunishmentLogger(plugin, database);
        BanManager banManager = new BanManager(plugin, bans, history, DatabaseExecutor.inline());
        MuteManager muteManager = new MuteManager(plugin, mutes, history, DatabaseExecutor.inline());

        String admin = "Admin123";
        assertEquals(PunishmentResult.SUCCESS, banManager.ban(uuid, "TaupaipaiNovo", "teste ban", admin).join());
        assertNotNull(banManager.get(uuid));
        assertEquals("teste ban", banManager.get(uuid).reason());
        assertNotNull(new PunishmentStorage(plugin, database, PunishmentStorage.Kind.BAN).get(uuid), "Ban precisa sobreviver a um reload.");
        assertEquals(PunishmentResult.SUCCESS, banManager.unban(uuid, "TaupaipaiNovo", admin).join());
        assertNull(banManager.get(uuid));

        assertEquals(PunishmentResult.SUCCESS, muteManager.tempMute(uuid, "TaupaipaiNovo", "spam", admin, 120).join());
        PunishmentData mute = muteManager.get(uuid);
        assertNotNull(mute);
        assertNotNull(muteManager.get(uuid));
        assertTrue(muteManager.mutedChatMessage(mute).startsWith("MUTED:spam:"));
        assertTrue(muteManager.muteNotice(mute).startsWith("TEMPMUTE:spam:"));
        assertTrue(muteManager.muteReminder(mute).startsWith("REMINDER:spam:"));
        assertNotNull(new PunishmentStorage(plugin, database, PunishmentStorage.Kind.MUTE).get(uuid), "Mute precisa sobreviver a um reload.");
        assertEquals(PunishmentResult.SUCCESS, muteManager.unmute(uuid, "TaupaipaiNovo", admin).join());
        assertNull(muteManager.get(uuid));

        UUID permanentMuteId = UUID.randomUUID();
        assertEquals(PunishmentResult.SUCCESS, muteManager.mute(permanentMuteId, "Permanente", "reincidência", "CONSOLE").join());
        PunishmentData permanentMute = muteManager.get(permanentMuteId);
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
        PunishmentStorage bans = new PunishmentStorage(plugin, database, PunishmentStorage.Kind.BAN);
        PunishmentStorage mutes = new PunishmentStorage(plugin, database, PunishmentStorage.Kind.MUTE);
        Instant expired = Instant.now().minusSeconds(5);

        assertTrue(bans.put(new PunishmentData(banId, "Banido", "teste", "console", expired)));
        assertNull(bans.get(banId), "Ban expirado não deve mais valer.");
        assertEquals(1, countRows("ban_records", banId), "A leitura não deve mais escrever no banco.");
        bans.cleanupExpired();
        assertEquals(0, countRows("ban_records", banId));

        assertTrue(mutes.put(new PunishmentData(muteId, "Mutado", "teste", "console", expired)));
        assertNull(mutes.get(muteId));
        mutes.cleanupExpired();
        assertEquals(0, countRows("mute_records", muteId));
    }

    @Test
    void duplicateAndMissingPunishmentsAreReportedWithoutChangingHistory() throws SQLException {
        database.initialize();
        UUID uuid = UUID.randomUUID();
        PunishmentLogger history = new PunishmentLogger(plugin, database);
        BanManager bans = new BanManager(plugin, new PunishmentStorage(plugin, database, PunishmentStorage.Kind.BAN), history,
                DatabaseExecutor.inline());
        MuteManager mutes = new MuteManager(plugin, new PunishmentStorage(plugin, database, PunishmentStorage.Kind.MUTE), history,
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
    void storageKindsKeepTablesSuggestionsAndRevisionsIndependent() throws SQLException {
        database.initialize();
        PunishmentStorage bans = new PunishmentStorage(plugin, database, PunishmentStorage.Kind.BAN);
        PunishmentStorage mutes = new PunishmentStorage(plugin, database, PunishmentStorage.Kind.MUTE);
        PunishmentLogger history = new PunishmentLogger(plugin, database);
        UUID id = UUID.randomUUID();
        PunishmentData ban = new PunishmentData(id, "BanName", "ban reason", "BanAdmin", null);
        PunishmentData mute = new PunishmentData(id, "MuteName", "mute reason", "MuteAdmin", null);
        assertTrue(bans.put(ban));
        assertEquals(1, bans.revision());
        assertEquals(0, mutes.revision());
        assertNull(mutes.get(id));
        assertTrue(mutes.put(mute));
        assertEquals(1, bans.revision());
        assertEquals(1, mutes.revision());
        assertEquals(ban, bans.get(id));
        assertEquals(mute, mutes.get(id));
        assertEquals(java.util.List.of("BanName"), bans.suggestNames(""));
        assertEquals(java.util.List.of("MuteName"), mutes.suggestNames(""));
        assertEquals(PunishmentResult.SUCCESS, bans.removeAndLog(id, "BanName", "Removido", "Admin",
                br.com.bancinematic.punishment.PunishmentType.UNBAN, history));
        assertNull(bans.get(id));
        assertEquals(mute, mutes.get(id));
        assertEquals(2, bans.revision());
        assertEquals(1, mutes.revision());
        assertTrue(bans.put(new PunishmentData(id, "ExpiredBan", "old", "Admin", Instant.now().minusSeconds(60))));
        bans.cleanupExpired();
        assertEquals(0, countRows("ban_records", id));
        assertEquals(1, countRows("mute_records", id));
        assertEquals(1, mutes.revision());
    }

    @Test
    void joinLookupUsesOneStatementAndFiltersExpiredAndUnrelatedPunishments() throws SQLException {
        config.set("database.table-prefix", "join_test_");
        try (DatabaseManager prefixed = new DatabaseManager(plugin)) {
            prefixed.initialize();
            PunishmentStorage bans = new PunishmentStorage(plugin, prefixed, PunishmentStorage.Kind.BAN);
            PunishmentStorage mutes = new PunishmentStorage(plugin, prefixed, PunishmentStorage.Kind.MUTE);
            Instant future = Instant.now().plusSeconds(600).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
            Instant past = Instant.now().minusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
            UUID both = UUID.randomUUID(), onlyMute = UUID.randomUUID(), onlyBan = UUID.randomUUID(), expired = UUID.randomUUID();
            PunishmentData ban = new PunishmentData(both, "BanName", "ban reason", "BanAdmin", null);
            PunishmentData mute = new PunishmentData(both, "MuteName", "mute reason", "MuteAdmin", future);
            assertTrue(bans.put(ban));
            assertTrue(mutes.put(mute));
            assertTrue(bans.put(new PunishmentData(onlyMute, "Alvo", "expired", "A", past)));
            assertTrue(mutes.put(new PunishmentData(onlyMute, "Alvo", "active", "A", null)));
            assertTrue(bans.put(new PunishmentData(onlyBan, "Alvo", "active", "A", future)));
            assertTrue(mutes.put(new PunishmentData(onlyBan, "Alvo", "expired", "A", past)));
            assertTrue(bans.put(new PunishmentData(expired, "Alvo", "expired", "A", past)));
            assertTrue(mutes.put(new PunishmentData(expired, "Alvo", "expired", "A", past)));
            Connection connection = org.mockito.Mockito.spy(prefixed.connect());
            DatabaseManager observed = org.mockito.Mockito.spy(prefixed);
            org.mockito.Mockito.doReturn(connection).when(observed).connect();
            var joined = new PunishmentStorage(plugin, observed, PunishmentStorage.Kind.MUTE).getForJoin(both);
            assertEquals(ban, joined.ban());
            assertEquals(mute, joined.mute());
            org.mockito.Mockito.verify(connection).prepareStatement(org.mockito.ArgumentMatchers.contains("UNION ALL"));
            org.mockito.Mockito.verify(connection, org.mockito.Mockito.times(1))
                    .prepareStatement(org.mockito.ArgumentMatchers.anyString());
            assertNull(mutes.getForJoin(onlyMute).ban());
            assertNotNull(mutes.getForJoin(onlyMute).mute());
            assertNotNull(mutes.getForJoin(onlyBan).ban());
            assertNull(mutes.getForJoin(onlyBan).mute());
            assertEquals(new br.com.bancinematic.punishment.JoinPunishments(null, null), mutes.getForJoin(expired));
            assertEquals(new br.com.bancinematic.punishment.JoinPunishments(null, null), mutes.getForJoin(UUID.randomUUID()));
        }
    }

    @Test
    void committedPunishmentsAreAuditedOnceWithoutDependingOnBroadcasts() throws SQLException {
        database.initialize();
        Logger console = mock(Logger.class);
        when(plugin.getLogger()).thenReturn(console);
        PunishmentLogger history = new PunishmentLogger(plugin, database);
        BanManager bans = new BanManager(plugin, new PunishmentStorage(plugin, database, PunishmentStorage.Kind.BAN), history, DatabaseExecutor.inline());
        MuteManager mutes = new MuteManager(plugin, new PunishmentStorage(plugin, database, PunishmentStorage.Kind.MUTE), history, DatabaseExecutor.inline());
        UUID uuid = UUID.randomUUID();
        for (boolean temporary : new boolean[]{false, true}) {
            assertEquals(PunishmentResult.SUCCESS, (temporary
                    ? bans.tempBan(uuid, "Alvo", "spam\nforjado", "Admin", 60)
                    : bans.ban(uuid, "Alvo", "spam\nforjado", "Admin")).join());
            assertEquals(PunishmentResult.ALREADY_ACTIVE, bans.ban(uuid, "Alvo", "duplicado", "Admin").join());
            assertEquals(PunishmentResult.SUCCESS, bans.unban(uuid, "Alvo", "Admin").join());
            assertEquals(PunishmentResult.NOT_ACTIVE, bans.unban(uuid, "Alvo", "Admin").join());
            assertEquals(PunishmentResult.SUCCESS, (temporary
                    ? mutes.tempMute(uuid, "Alvo", "spam\nforjado", "Admin", 60)
                    : mutes.mute(uuid, "Alvo", "spam\nforjado", "Admin")).join());
            assertEquals(PunishmentResult.ALREADY_ACTIVE, mutes.mute(uuid, "Alvo", "duplicado", "Admin").join());
            assertEquals(PunishmentResult.SUCCESS, mutes.unmute(uuid, "Alvo", "Admin").join());
            assertEquals(PunishmentResult.NOT_ACTIVE, mutes.unmute(uuid, "Alvo", "Admin").join());
            String fields = " player=Alvo uuid=" + uuid + " source=Admin duration-seconds="
                    + (temporary ? "60" : "-") + " reason=spam forjado";
            org.mockito.Mockito.verify(console).info("[AUDIT] " + (temporary ? "TEMPBAN" : "BAN") + fields);
            org.mockito.Mockito.verify(console).info("[AUDIT] " + (temporary ? "TEMPMUTE" : "MUTE") + fields);
        }
        String removed = " player=Alvo uuid=" + uuid + " source=Admin duration-seconds=- reason=Removido";
        org.mockito.Mockito.verify(console, org.mockito.Mockito.times(2)).info("[AUDIT] UNBAN" + removed);
        org.mockito.Mockito.verify(console, org.mockito.Mockito.times(2)).info("[AUDIT] UNMUTE" + removed);
        org.mockito.Mockito.verify(console, org.mockito.Mockito.times(8)).info(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void failedHistoryWriteRollsBackNewBanAndRestoresRemovedBan() throws SQLException {
        database.initialize();
        UUID uuid = UUID.randomUUID();
        PunishmentStorage storage = new PunishmentStorage(plugin, database, PunishmentStorage.Kind.BAN);
        PunishmentLogger history = mock(PunishmentLogger.class);
        BanManager manager = new BanManager(plugin, storage, history, DatabaseExecutor.inline());

        doThrow(new SQLException("histórico indisponível")).when(history).log(
                org.mockito.ArgumentMatchers.any(Connection.class),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());

        assertEquals(PunishmentResult.STORAGE_ERROR,
                manager.ban(uuid, "Alvo", "teste", "CONSOLE").join());
        assertNull(manager.get(uuid), "Um ban sem histórico não deve permanecer ativo.");
        assertEquals(0, countRows("ban_records", uuid),
                "A transação deve desfazer o ban quando a escrita do histórico falha.");

        assertTrue(storage.put(new PunishmentData(uuid, "Alvo", "teste", "staff", null)));
        assertEquals(PunishmentResult.STORAGE_ERROR,
                manager.unban(uuid, "Alvo", "CONSOLE").join());
        assertNotNull(manager.get(uuid), "Falha ao registrar /unban deve restaurar o ban anterior.");
        assertEquals(1, countRows("ban_records", uuid),
                "A transação deve preservar o ban quando o histórico de /unban falha.");

        UUID muteId = UUID.randomUUID();
        PunishmentStorage muteStorage = new PunishmentStorage(plugin, database, PunishmentStorage.Kind.MUTE);
        MuteManager muteManager = new MuteManager(plugin, muteStorage, history, DatabaseExecutor.inline());
        assertEquals(PunishmentResult.STORAGE_ERROR,
                muteManager.mute(muteId, "Mutado", "teste", "CONSOLE").join());
        assertNull(muteManager.get(muteId), "Um mute sem histórico não deve permanecer ativo.");
        assertEquals(0, countRows("mute_records", muteId),
                "A transação deve desfazer o mute quando a escrita do histórico falha.");

        assertTrue(muteStorage.put(new PunishmentData(muteId, "Mutado", "teste", "staff", null)));
        assertEquals(PunishmentResult.STORAGE_ERROR,
                muteManager.unmute(muteId, "Mutado", "CONSOLE").join());
        assertNotNull(muteManager.get(muteId),
                "Falha ao registrar /unmute deve preservar o mute anterior.");
        assertEquals(1, countRows("mute_records", muteId),
                "A transação deve preservar o mute quando o histórico de /unmute falha.");
        org.mockito.Mockito.verify(history, org.mockito.Mockito.never()).logConsole(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void mysqlSchemaSupportsPunishmentHistorySourcesWhenIntegrationEnvironmentIsConfigured() throws SQLException {
        configureMysqlFromEnvironment();
        DatabaseManager mysql = new DatabaseManager(plugin);
        mysql.initialize();

        UUID uuid = UUID.randomUUID();
        PunishmentStorage bans = new PunishmentStorage(plugin, mysql, PunishmentStorage.Kind.BAN);
        PunishmentStorage mutes = new PunishmentStorage(plugin, mysql, PunishmentStorage.Kind.MUTE);
        PunishmentLogger history = new PunishmentLogger(plugin, mysql);
        BanManager banManager = new BanManager(plugin, bans, history, DatabaseExecutor.inline());
        MuteManager muteManager = new MuteManager(plugin, mutes, history, DatabaseExecutor.inline());
        try {
            assertEquals(PunishmentResult.SUCCESS,
                    banManager.ban(uuid, "MySqlIntegration", "teste", "MySqlAdmin").join());
            assertEquals("teste", bans.get(uuid).reason());
            assertEquals("teste", mutes.getForJoin(uuid).ban().reason());
            assertNull(mutes.getForJoin(uuid).mute());
            assertEquals(PunishmentResult.SUCCESS,
                    banManager.unban(uuid, "MySqlIntegration", "MySqlAdmin").join());
            assertNull(bans.get(uuid));

            assertEquals(PunishmentResult.SUCCESS,
                    muteManager.mute(uuid, "MySqlIntegration", "teste", "MySqlAdmin").join());
            assertEquals("teste", mutes.get(uuid).reason());
            assertNull(mutes.getForJoin(uuid).ban());
            assertEquals("teste", mutes.getForJoin(uuid).mute().reason());
            assertEquals(PunishmentResult.SUCCESS,
                    muteManager.unmute(uuid, "MySqlIntegration", "MySqlAdmin").join());
            assertNull(mutes.get(uuid));

            assertTrue(history.log(br.com.bancinematic.punishment.PunishmentType.KICK, uuid,
                    "MySqlIntegration", "teste", "CONSOLE", null));
            var entries = history.getHistoryPage(uuid, 0, 8).entries();
            assertEquals(5, entries.size());
            var consoleEntry = entries.stream()
                    .filter(entry -> "CONSOLE".equals(entry.source())).findFirst().orElseThrow();
            var playerEntry = entries.stream()
                    .filter(entry -> "MySqlAdmin".equals(entry.source())).findFirst().orElseThrow();
            assertEquals("CONSOLE", consoleEntry.source());
            assertEquals("MySqlAdmin", playerEntry.source());
        } finally {
            try (Connection connection = mysql.connect()) {
                deleteTestRecord(connection, mysql.sql("ban_records"), "subject_uuid", uuid);
                deleteTestRecord(connection, mysql.sql("mute_records"), "subject_uuid", uuid);
                deleteTestRecord(connection, mysql.sql("punishment_history"), "subject_uuid", uuid);
            }
            mysql.close();
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
        config.set("database.table-prefix", System.getenv().getOrDefault("BANCINEMATIC_TEST_MYSQL_PREFIX", "bc_test_"));
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

    private boolean tableExists(String table) throws SQLException {
        try (Connection connection = database.connect(); ResultSet tables = connection.getMetaData()
                .getTables(null, null, table, new String[]{"TABLE"})) {
            return tables.next();
        }
    }

}
