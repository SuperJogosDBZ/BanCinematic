package br.com.bancinematic.storage;

import br.com.bancinematic.BanCinematicPlugin;
import br.com.bancinematic.command.CheckCommand;
import br.com.bancinematic.command.HistoryCommand;
import br.com.bancinematic.command.PunishmentTabListener;
import br.com.bancinematic.listener.BanLoginListener;
import br.com.bancinematic.listener.ChatListener;
import br.com.bancinematic.listener.MuteJoinListener;
import br.com.bancinematic.punishment.*;
import br.com.bancinematic.util.MessageUtil;
import com.destroystokyo.paper.event.server.AsyncTabCompleteEvent;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlayerLookupTest {
    @TempDir Path directory;

    private BanCinematicPlugin plugin(DatabaseExecutor executor) {
        var plugin = mock(BanCinematicPlugin.class);
        var config = new YamlConfiguration();
        config.set("database.type", "SQLITE");
        config.set("database.login-timeout-seconds", 1);
        config.set("messages.login-storage-error", "UNAVAILABLE");
        config.set("messages.storage-error", "STORAGE_ERROR");
        config.set("messages.player-never-joined", "UNKNOWN");
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getLanguageConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("player-lookup-test"));
        when(plugin.messages()).thenReturn(new MessageUtil(plugin));
        when(plugin.databaseExecutor()).thenReturn(executor);
        doAnswer(call -> { call.<Runnable>getArgument(0).run(); return null; }).when(plugin).runSync(any());
        return plugin;
    }

    @Test
    void historyLookupFailureIsNotReportedAsAnEmptyHistory() throws Exception {
        var plugin = plugin(DatabaseExecutor.inline());
        plugin.getConfig().set("messages.history.no-records", "EMPTY_HISTORY");
        var identities = mock(IdentityStorage.class);
        when(plugin.identities()).thenReturn(identities);
        when(identities.resolve("Alvo")).thenReturn(UUID.randomUUID());
        var database = mock(DatabaseManager.class);
        when(database.execute(any())).thenThrow(new SQLException("offline"));
        var sender = mock(CommandSender.class);

        new HistoryCommand(plugin, new PunishmentLogger(plugin, database))
                .onCommand(sender, mock(Command.class), "history", new String[]{"Alvo"});

        verify(sender).sendMessage("STORAGE_ERROR");
        verify(sender, never()).sendMessage("EMPTY_HISTORY");
    }

    @Test
    void joinPreparationDisconnectsOnEitherBanOrMuteLookupFailure() {
        for (boolean failBan : new boolean[]{true, false}) {
            var plugin = plugin(DatabaseExecutor.inline());
            var bans = mock(BanManager.class);
            var mutes = mock(MuteManager.class);
            var player = mock(Player.class);
            UUID id = UUID.randomUUID();
            when(player.getUniqueId()).thenReturn(id);
            when(player.isOnline()).thenReturn(true);
            var event = mock(PlayerJoinEvent.class);
            when(event.getPlayer()).thenReturn(player);
            if (failBan) when(bans.get(id)).thenThrow(new IllegalStateException("offline"));
            else when(mutes.loadOnline(id)).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("offline")));

            new MuteJoinListener(plugin, bans, mutes).onJoin(event);

            verify(player).kick(plugin.messages().component("UNAVAILABLE"));
            if (failBan) verifyNoInteractions(mutes);
        }
    }

    @Test
    void leavingBeforeBanLookupFinishesDoesNotStartMuteLoading() {
        var executor = mock(DatabaseExecutor.class);
        var result = new CompletableFuture<BanData>();
        when(executor.<BanData>submit(any())).thenReturn(result);
        var plugin = plugin(executor);
        var mutes = mock(MuteManager.class);
        var player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        var event = mock(PlayerJoinEvent.class);
        when(event.getPlayer()).thenReturn(player);

        new MuteJoinListener(plugin, mock(BanManager.class), mutes).onJoin(event);
        result.complete(null);

        verifyNoInteractions(mutes);
        verify(player, never()).kick(any(net.kyori.adventure.text.Component.class));
    }

    @Test
    void leavingDuringMuteLookupDoesNotSendLateReminder() {
        var plugin = plugin(DatabaseExecutor.inline());
        var mutes = mock(MuteManager.class);
        var result = new CompletableFuture<MuteData>();
        var player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        when(player.isOnline()).thenReturn(true);
        when(mutes.loadOnline(id)).thenReturn(result);
        var event = mock(PlayerJoinEvent.class);
        when(event.getPlayer()).thenReturn(player);
        var listener = new MuteJoinListener(plugin, mock(BanManager.class), mutes);
        listener.onJoin(event);

        when(player.isOnline()).thenReturn(false);
        var quit = mock(PlayerQuitEvent.class);
        when(quit.getPlayer()).thenReturn(player);
        listener.onQuit(quit);
        result.complete(new MuteData(id, "Alvo", "reason", "A", null));

        verify(mutes).forgetOnline(id);
        verify(mutes, never()).muteReminder(any());
        verify(player, never()).sendMessage(anyString());
    }

    @Test
    void constructorsDoNotLoadPunishmentsOrIdentitiesAndLookupsSeeRemoteChanges() throws Exception {
        var plugin = plugin(DatabaseExecutor.inline());
        try (var database = spy(new DatabaseManager(plugin))) {
            database.initialize();
            clearInvocations(database);
            var bans = new BanStorage(plugin, database);
            var mutes = new MuteStorage(plugin, database);
            var identities = new IdentityStorage(plugin, database);
            verify(database, never()).execute(any());
            assertEquals(0, identities.cachedCount());
            UUID id = UUID.randomUUID();
            try (var remote = new DatabaseManager(plugin)) {
                assertTrue(new BanStorage(plugin, remote).put(new BanData(id, "Alvo", "remote", "A", null)));
                assertEquals("remote", bans.get(id).reason());
                assertTrue(new MuteStorage(plugin, remote).put(new MuteData(id, "Alvo", "remote", "A", null)));
                assertEquals("remote", mutes.get(id).reason());
            }
        }
    }

    @Test
    void batchLookupsReturnOnlyRequestedActivePlayersAcrossChunkBoundaries() throws Exception {
        var plugin = plugin(DatabaseExecutor.inline());
        try (var database = new DatabaseManager(plugin)) {
            database.initialize();
            var ids = new ArrayList<UUID>();
            database.transaction(connection -> {
                try (var statement = connection.prepareStatement("INSERT INTO ban_records VALUES (?, 'Alvo', 'reason', 'A', ?)")) {
                    for (int i = 0; i < 205; i++) {
                        UUID id = UUID.randomUUID();
                        ids.add(id);
                        statement.setString(1, id.toString());
                        if (i == 204) statement.setLong(2, 1);
                        else statement.setNull(2, java.sql.Types.BIGINT);
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
                return null;
            });
            var bans = new BanStorage(plugin, database);
            assertEquals(204, bans.find(ids).size());
            var selected = bans.find(List.of(ids.get(0), ids.get(202), UUID.randomUUID()));
            assertEquals(Set.of(ids.get(0), ids.get(202)), selected.keySet());
            assertTrue(bans.find(List.of()).isEmpty());
        }
    }

    @Test
    void identityCacheIsBoundedAndEvictedNamesRemainResolvableFromSql() throws Exception {
        var plugin = plugin(DatabaseExecutor.inline());
        try (var database = new DatabaseManager(plugin)) {
            database.initialize();
            UUID id = UUID.randomUUID();
            database.transaction(connection -> {
                try (var statement = connection.prepareStatement("INSERT INTO player_names VALUES (?, ?, ?, ?)")) {
                    for (int i = 0; i < 1200; i++) {
                        statement.setString(1, "player" + i);
                        statement.setString(2, id.toString());
                        statement.setString(3, "Player" + i);
                        statement.setLong(4, 1000);
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
                return null;
            });
            var names = new IdentityStorage(plugin, database);
            for (int i = 0; i < 1200; i++) assertEquals(id, names.resolve("Player" + i));
            assertEquals(1024, names.cachedCount());
            assertEquals(id, names.resolve("PLAYER0"));
            names.invalidate();
            assertEquals(0, names.cachedCount());
            assertEquals(id, names.resolve("Player1199"));
        }
    }

    @Test
    void sqlFailureBlocksPreloginAndDoesNotOverrideAnotherPluginsRejection() {
        var plugin = plugin(DatabaseExecutor.inline());
        var bans = mock(BanManager.class);
        var event = mock(AsyncPlayerPreLoginEvent.class);
        UUID id = UUID.randomUUID();
        when(event.getUniqueId()).thenReturn(id);
        when(event.getLoginResult()).thenReturn(AsyncPlayerPreLoginEvent.Result.ALLOWED);
        when(bans.get(id)).thenThrow(new IllegalStateException("SQL unavailable"));
        new BanLoginListener(plugin, bans).onLogin(event);
        verify(event).disallow(eq(AsyncPlayerPreLoginEvent.Result.KICK_OTHER), any(net.kyori.adventure.text.Component.class));
        clearInvocations(bans);
        when(event.getLoginResult()).thenReturn(AsyncPlayerPreLoginEvent.Result.KICK_WHITELIST);
        new BanLoginListener(plugin, bans).onLogin(event);
        verifyNoInteractions(bans);
    }

    @Test
    void unavailableStartupBlocksLoginBeforeManagersExist() {
        var plugin = plugin(DatabaseExecutor.inline());
        var event = mock(AsyncPlayerPreLoginEvent.class);
        when(event.getLoginResult()).thenReturn(AsyncPlayerPreLoginEvent.Result.ALLOWED);
        new BanLoginListener(plugin, () -> null).onLogin(event);
        verify(event).disallow(eq(AsyncPlayerPreLoginEvent.Result.KICK_OTHER), any(net.kyori.adventure.text.Component.class));
        verify(plugin, never()).databaseExecutor();
    }

    @Test
    void preloginTimesOutQueuedWorkAndDoesNotRunTheCancelledQueryLater() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        try (var executor = DatabaseExecutor.create()) {
            executor.execute(() -> { started.countDown(); await(release); });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            var plugin = plugin(executor);
            var bans = mock(BanManager.class);
            var event = mock(AsyncPlayerPreLoginEvent.class);
            when(event.getLoginResult()).thenReturn(AsyncPlayerPreLoginEvent.Result.ALLOWED);
            try {
                new BanLoginListener(plugin, bans).onLogin(event);
                verify(event).disallow(eq(AsyncPlayerPreLoginEvent.Result.KICK_OTHER), any(net.kyori.adventure.text.Component.class));
            } finally { release.countDown(); }
            executor.execute(() -> {}).get(5, TimeUnit.SECONDS);
            verifyNoInteractions(bans);
        }
    }

    @Test
    void checkQueriesRunOnWorkerAndStorageErrorsAreNotReportedAsUnknownPlayers() throws Exception {
        try (var executor = DatabaseExecutor.create()) {
            var plugin = plugin(executor);
            var identities = mock(IdentityStorage.class);
            when(plugin.identities()).thenReturn(identities);
            Thread caller = Thread.currentThread();
            when(identities.get("Alvo")).thenAnswer(call -> {
                assertNotEquals(caller, Thread.currentThread());
                throw new IllegalStateException("offline");
            });
            var sender = mock(CommandSender.class);
            new CheckCommand(plugin, mock(BanManager.class), mock(MuteManager.class))
                    .onCommand(sender, mock(Command.class), "check", new String[]{"Alvo"});
            executor.execute(() -> {}).get(5, TimeUnit.SECONDS);
            verify(sender).sendMessage("STORAGE_ERROR");
            verify(sender, never()).sendMessage("UNKNOWN");
        }
    }

    @Test
    void muteCacheDoesNotGrowWithOfflinePunishmentsAndQuitDiscardsPendingLoads() throws Exception {
        CountDownLatch first = new CountDownLatch(1), second = new CountDownLatch(1), started = new CountDownLatch(1);
        try (var executor = DatabaseExecutor.create()) {
            var plugin = plugin(executor);
            var storage = mock(MuteStorage.class);
            var manager = new MuteManager(plugin, storage, mock(PunishmentLogger.class), executor);
            UUID id = UUID.randomUUID();
            when(storage.get(id)).thenAnswer(call -> { started.countDown(); await(first); return new MuteData(id, "Alvo", "old", "A", null); })
                    .thenAnswer(call -> { await(second); return null; });
            var old = manager.loadOnline(id);
            assertTrue(started.await(5, TimeUnit.SECONDS));
            manager.forgetOnline(id);
            var current = manager.loadOnline(id);
            try {
                first.countDown();
                old.get(5, TimeUnit.SECONDS);
                assertFalse(manager.isLoaded(id), "A conexão anterior não pode liberar o chat da nova.");
                assertNull(manager.getOnline(id));
            } finally { first.countDown(); second.countDown(); }
            current.get(5, TimeUnit.SECONDS);
            assertTrue(manager.isLoaded(id));
            when(storage.revision()).thenReturn(1L);
            var stale = new MuteData(id, "Alvo", "stale sync", "A", null);
            manager.updateOnlineFromSync(id, stale, 0L);
            assertNull(manager.getOnline(id), "Sincronização antiga não pode restaurar um mute removido localmente.");
            manager.updateOnlineFromSync(id, stale, 1L);
            assertEquals(stale, manager.getOnline(id));
            manager.forgetOnline(id);
            assertFalse(manager.isLoaded(id));
            when(storage.putAndLog(any(), any(), any(), any())).thenReturn(PunishmentResult.SUCCESS);
            for (int i = 0; i < 100; i++) {
                UUID offline = UUID.randomUUID();
                assertEquals(PunishmentResult.SUCCESS, manager.mute(offline, "Offline", "regra", "A").get(5, TimeUnit.SECONDS));
                assertFalse(manager.isLoaded(offline));
                assertNull(manager.getOnline(offline));
            }
        }
    }

    @Test
    void chatIsBlockedUntilMuteIsLoadedWithoutQueryingSqlPerMessage() {
        var manager = mock(MuteManager.class);
        var event = mock(AsyncPlayerChatEvent.class);
        var player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        when(event.getPlayer()).thenReturn(player);
        new ChatListener(manager).onChat(event);
        verify(event).setCancelled(true);
        verify(manager, never()).get(any());
        when(manager.isLoaded(id)).thenReturn(true);
        clearInvocations(event);
        new ChatListener(manager).onChat(event);
        verify(event, never()).setCancelled(true);
        verify(manager).getOnline(id);
    }

    @Test
    void suggestionsAreBoundedEscapeWildcardsAndNeverIncludeUuids() throws Exception {
        var plugin = plugin(DatabaseExecutor.inline());
        try (var database = new DatabaseManager(plugin)) {
            database.initialize();
            var bans = new BanStorage(plugin, database);
            database.transaction(connection -> {
                try (var statement = connection.prepareStatement("INSERT INTO ban_records VALUES (?, ?, 'reason', 'A', NULL)")) {
                    for (int i = 0; i < 130; i++) {
                        statement.setString(1, UUID.randomUUID().toString());
                        statement.setString(2, "Player" + i);
                        statement.addBatch();
                    }
                    statement.executeBatch();
                }
                return null;
            });
            assertEquals(100, bans.suggestNames("").size());
            assertTrue(bans.suggestNames("Player1").stream().allMatch(name -> name.startsWith("Player1")));
            assertEquals(List.of(), bans.suggestNames("%"));
            assertEquals(List.of(), bans.suggestNames("_"));
            assertEquals(List.of(), bans.suggestNames(UUID.randomUUID().toString()));
        }
    }

    @Test
    void asyncCompletionChecksPermissionAndDoesNotQueryFromSynchronousFallback() throws Exception {
        try (var executor = DatabaseExecutor.create()) {
            var plugin = plugin(executor);
            var command = mock(PluginCommand.class);
            when(command.getAliases()).thenReturn(List.of());
            when(plugin.getCommand("unban")).thenReturn(command);
            var bans = mock(BanManager.class);
            var listener = new PunishmentTabListener(plugin, bans, mock(MuteManager.class));
            var sender = mock(CommandSender.class);
            var event = mock(AsyncTabCompleteEvent.class);
            when(event.isCommand()).thenReturn(true);
            when(event.getBuffer()).thenReturn("/unban aL");
            when(event.getSender()).thenReturn(sender);
            when(event.isAsynchronous()).thenReturn(true);
            listener.onComplete(event);
            verifyNoInteractions(bans);
            when(sender.hasPermission("bancinematic.unban")).thenReturn(true);
            Thread caller = Thread.currentThread();
            when(bans.suggestNames("aL")).thenAnswer(call -> { assertNotEquals(caller, Thread.currentThread()); return List.of("Alice"); });
            listener.onComplete(event);
            verify(event).setCompletions(List.of("Alice"));
            clearInvocations(bans);
            when(event.isAsynchronous()).thenReturn(false);
            listener.onComplete(event);
            verifyNoInteractions(bans);
        }
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("test timed out"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
    }
}
