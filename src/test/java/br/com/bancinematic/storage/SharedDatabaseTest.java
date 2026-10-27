package br.com.bancinematic.storage;

import br.com.bancinematic.BanCinematicPlugin;
import br.com.bancinematic.punishment.*;
import br.com.bancinematic.util.MessageUtil;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;

class SharedDatabaseTest {
    @TempDir Path directory;

    private BanCinematicPlugin plugin(YamlConfiguration config) {
        BanCinematicPlugin plugin = mock(BanCinematicPlugin.class);
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getLanguageConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("shared-database-test"));
        when(plugin.messages()).thenReturn(new MessageUtil(plugin));
        return plugin;
    }

    @Test
    void sqliteInstancesReadCurrentDatabaseOutcomesWithoutGlobalCaches() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.set("database.type", "SQLITE");
        exerciseTwoInstances(plugin(config));
    }

    @Test
    void mysqlInstancesRefreshAndHandleConcurrentWritesWhenConfigured() throws Exception {
        String host = System.getenv("BANCINEMATIC_TEST_MYSQL_HOST");
        assumeTrue(host != null && System.getenv("BANCINEMATIC_TEST_MYSQL_PASSWORD") != null);
        YamlConfiguration config = new YamlConfiguration();
        config.set("database.type", "MYSQL");
        config.set("database.table-prefix", "bc_sync_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + "_");
        config.set("database.mysql.host", host);
        config.set("database.mysql.database", System.getenv("BANCINEMATIC_TEST_MYSQL_DATABASE"));
        config.set("database.mysql.username", System.getenv("BANCINEMATIC_TEST_MYSQL_USERNAME"));
        config.set("database.mysql.password", System.getenv("BANCINEMATIC_TEST_MYSQL_PASSWORD"));
        BanCinematicPlugin plugin = plugin(config);
        try {
            exerciseTwoInstances(plugin);
        } finally {
            try (DatabaseManager database = new DatabaseManager(plugin); var connection = database.connect();
                 var statement = connection.createStatement()) {
                for (String table : new String[]{"ban_records", "mute_records", "punishment_history", "player_names"}) {
                    statement.executeUpdate("DROP TABLE IF EXISTS " + database.sql(table));
                }
            }
        }
    }

    private void exerciseTwoInstances(BanCinematicPlugin plugin) throws Exception {
        try (DatabaseManager first = new DatabaseManager(plugin); DatabaseManager second = new DatabaseManager(plugin)) {
            first.initialize();
            BanStorage aBans = new BanStorage(plugin, first), bBans = new BanStorage(plugin, second);
            MuteStorage aMutes = new MuteStorage(plugin, first), bMutes = new MuteStorage(plugin, second);
            IdentityStorage aNames = new IdentityStorage(plugin, first), bNames = new IdentityStorage(plugin, second);
            PunishmentLogger history = new PunishmentLogger(plugin, first);
            BanManager a = new BanManager(plugin, aBans, history, DatabaseExecutor.inline());
            BanManager b = new BanManager(plugin, bBans, new PunishmentLogger(plugin, second), DatabaseExecutor.inline());
            MuteManager am = new MuteManager(plugin, aMutes, history, DatabaseExecutor.inline());
            MuteManager bm = new MuteManager(plugin, bMutes, new PunishmentLogger(plugin, second), DatabaseExecutor.inline());
            UUID uuid = UUID.randomUUID();
            assertEquals(PunishmentResult.SUCCESS, a.ban(uuid, "Alvo", "original", "A").join());
            assertNotNull(b.get(uuid));
            assertEquals(PunishmentResult.ALREADY_ACTIVE, b.tempBan(uuid, "Alvo", "overwrite", "B", 10).join());
            assertEquals(1, history.getHistoryPage(uuid, 0, 8).totalEntries());

            assertEquals("original", b.get(uuid).reason());
            assertEquals(PunishmentResult.SUCCESS, b.unban(uuid, "Alvo", "B").join());
            assertNull(a.get(uuid));
            assertEquals(PunishmentResult.NOT_ACTIVE, a.unban(uuid, "Alvo", "A").join());
            assertEquals(2, history.getHistoryPage(uuid, 0, 8).totalEntries());

            assertNull(a.get(uuid));

            assertEquals(PunishmentResult.SUCCESS, am.mute(uuid, "Alvo", "original-mute", "A").join());
            assertEquals(PunishmentResult.ALREADY_ACTIVE, bm.mute(uuid, "Alvo", "overwrite", "B").join());

            assertEquals("original-mute", bm.get(uuid).reason());
            assertEquals(PunishmentResult.SUCCESS, am.unmute(uuid, "Alvo", "A").join());
            assertEquals(PunishmentResult.NOT_ACTIVE, bm.unmute(uuid, "Alvo", "B").join());
            assertEquals(4, history.getHistoryPage(uuid, 0, 8).totalEntries());

            assertNull(bm.get(uuid));

            assertTrue(aNames.record(uuid, "Alvo"));
            assertEquals(uuid, bNames.resolve("Alvo"));
            bNames.invalidate();
            assertEquals(uuid, bNames.resolve("ALVO"));
            UUID reused = UUID.randomUUID();
            assertTrue(aNames.record(reused, "Alvo"));
            bNames.invalidate();
            assertEquals(reused, bNames.resolve("alvo"));
            assertNotNull(bNames.get("Alvo").lastSeen());

            UUID expired = UUID.randomUUID();
            assertTrue(aBans.put(new BanData(expired, "Expirado", "old", "A", Instant.now().minusSeconds(60))));
            assertEquals(PunishmentResult.SUCCESS, b.ban(expired, "Expirado", "new", "B").join());

            assertEquals("new", a.get(expired).reason());

            try (ExecutorService threads = Executors.newFixedThreadPool(2)) {
                for (int i = 0; i < 4; i++) {
                    UUID race = UUID.randomUUID();
                    if (i % 2 == 0) assertTrue(aBans.put(new BanData(race, "Race", "expired", "A", Instant.now().minusSeconds(60))));
                    CyclicBarrier barrier = new CyclicBarrier(2);
                    Future<PunishmentResult> left = threads.submit(() -> { barrier.await(); return a.ban(race, "Race", "left", "A").join(); });
                    Future<PunishmentResult> right = threads.submit(() -> { barrier.await(); return b.ban(race, "Race", "right", "B").join(); });
                    var outcomes = java.util.List.of(left.get(30, TimeUnit.SECONDS), right.get(30, TimeUnit.SECONDS));
                    assertEquals(1, outcomes.stream().filter(r -> r == PunishmentResult.SUCCESS).count(), outcomes.toString());
                    assertEquals(1, outcomes.stream().filter(r -> r == PunishmentResult.ALREADY_ACTIVE).count(), outcomes.toString());
                    assertEquals(1, history.getHistoryPage(race, 0, 8).totalEntries());
                }
                for (int i = 0; i < 4; i++) {
                    UUID race = UUID.randomUUID();
                    if (i % 2 == 0) assertTrue(aMutes.put(new MuteData(race, "Race", "expired", "A", Instant.now().minusSeconds(60))));
                    CyclicBarrier barrier = new CyclicBarrier(2);
                    Future<PunishmentResult> left = threads.submit(() -> { barrier.await(); return am.mute(race, "Race", "left", "A").join(); });
                    Future<PunishmentResult> right = threads.submit(() -> { barrier.await(); return bm.mute(race, "Race", "right", "B").join(); });
                    var outcomes = java.util.List.of(left.get(30, TimeUnit.SECONDS), right.get(30, TimeUnit.SECONDS));
                    assertEquals(1, outcomes.stream().filter(r -> r == PunishmentResult.SUCCESS).count(), outcomes.toString());
                    assertEquals(1, outcomes.stream().filter(r -> r == PunishmentResult.ALREADY_ACTIVE).count(), outcomes.toString());
                    assertEquals(1, history.getHistoryPage(race, 0, 8).totalEntries());
                }
            }
        }
    }

    @Test
    void identityRefreshPreservesQueuedLocalJoins() throws Exception {
        YamlConfiguration config = new YamlConfiguration();
        config.set("database.type", "SQLITE");
        BanCinematicPlugin plugin = plugin(config);
        try (DatabaseManager database = new DatabaseManager(plugin); DatabaseExecutor executor = DatabaseExecutor.create()) {
            database.initialize();
            IdentityStorage identities = new IdentityStorage(plugin, database);
            assertTrue(identities.record(UUID.randomUUID(), "Alvo"));
            CountDownLatch release = new CountDownLatch(1);
            executor.execute(() -> { try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
            UUID joined = UUID.randomUUID();
            CompletableFuture<Boolean> saved;
            try {
                saved = identities.recordAsync(executor, joined, "Alvo");
                identities.invalidate();
                assertEquals(joined, identities.resolve("Alvo"));
            } finally {
                release.countDown();
            }
            assertTrue(saved.get(5, TimeUnit.SECONDS));
            identities.invalidate();
            assertEquals(joined, identities.resolve("Alvo"));
        }
    }
}
