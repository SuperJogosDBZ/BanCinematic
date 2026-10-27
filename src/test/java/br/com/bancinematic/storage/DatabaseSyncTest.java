package br.com.bancinematic.storage;

import br.com.bancinematic.BanCinematicPlugin;
import br.com.bancinematic.cinematic.BanCinematic;
import br.com.bancinematic.punishment.BanData;
import br.com.bancinematic.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DatabaseSyncTest {
    private BanCinematicPlugin plugin;
    private BanStorage bans;
    private MuteStorage mutes;
    private IdentityStorage identities;
    private BanCinematic cinematic;
    private br.com.bancinematic.punishment.MuteManager muteManager;
    private DatabaseSync sync;
    private YamlConfiguration config;

    @BeforeEach
    void setUp() {
        plugin = mock(BanCinematicPlugin.class);
        bans = mock(BanStorage.class);
        mutes = mock(MuteStorage.class);
        identities = mock(IdentityStorage.class);
        cinematic = mock(BanCinematic.class);
        muteManager = mock(br.com.bancinematic.punishment.MuteManager.class);
        config = new YamlConfiguration();
        config.set("messages.ban-screen.title", "BAN_SCREEN");
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getLanguageConfig()).thenReturn(config);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("sync-test"));
        when(plugin.messages()).thenReturn(new MessageUtil(plugin));
        when(plugin.databaseExecutor()).thenReturn(DatabaseExecutor.inline());
        doAnswer(invocation -> { invocation.<Runnable>getArgument(0).run(); return null; })
                .when(plugin).runSync(any(Runnable.class));
        sync = new DatabaseSync(plugin, bans, mutes, identities, muteManager, cinematic);
    }

    @Test
    void disabledOrInvalidIntervalDoesNotScheduleAndPositiveIntervalUsesSeconds() {
        sync.start();
        verify(plugin, never()).getServer();
        for (long invalid : new long[]{-1, Long.MAX_VALUE}) {
            config.set("database.sync-interval-seconds", invalid);
            sync.start();
        }
        verify(plugin, never()).getServer();
        config.set("database.sync-interval-seconds", 15);
        Server server = mock(Server.class);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        sync.start();
        verify(scheduler).runTaskTimer(plugin, sync, 300L, 300L);
    }

    @Test
    void synchronizedBanKicksWithoutAnnouncingOrInterruptingLocalCinematics() {
        Player remote = mock(Player.class), local = mock(Player.class), clean = mock(Player.class);
        UUID remoteId = UUID.randomUUID(), localId = UUID.randomUUID();
        when(remote.getUniqueId()).thenReturn(remoteId);
        when(remote.getName()).thenReturn("Remoto");
        when(local.getUniqueId()).thenReturn(localId);
        when(clean.getUniqueId()).thenReturn(UUID.randomUUID());
        when(remote.isOnline()).thenReturn(true);
        when(local.isOnline()).thenReturn(true);
        when(clean.isOnline()).thenReturn(true);
        when(bans.find(any())).thenReturn(java.util.Map.of(remoteId, new BanData(remoteId, "Remoto", "regra", "OutroServidor", null),
                localId, new BanData(localId, "Local", "regra", "CONSOLE", null)));

        when(cinematic.isRunning(localId)).thenReturn(true);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(remote, local, clean));
            sync.run();
        }
        verify(remote).kick(any(net.kyori.adventure.text.Component.class));
        verify(local, never()).kick(any(net.kyori.adventure.text.Component.class));
        verify(clean, never()).kick(any(net.kyori.adventure.text.Component.class));
        verify(plugin, never()).broadcastPunishment(anyString(), anyString(), anyString(), anyString(), anyString());
        verify(mutes).find(any());
        verify(identities).invalidate();
    }

    @Test
    void failedBanQueryDoesNotPublishPartialSynchronization() {
        when(bans.find(any())).thenThrow(new IllegalStateException("offline"));
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
            sync.run();
        }
        verify(mutes).revision();
        verify(mutes, never()).find(any());
        verifyNoInteractions(identities);
        verify(plugin, never()).runSync(any());
    }

    @Test
    void newerLocalWriteMakesDelayedSynchronizationResultsObsolete() {
        Player player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        when(player.isOnline()).thenReturn(true);
        when(bans.find(any())).thenReturn(java.util.Map.of(id, new BanData(id, "Alvo", "old", "A", null)));
        var callback = new java.util.concurrent.atomic.AtomicReference<Runnable>();
        doAnswer(call -> { callback.set(call.getArgument(0)); return null; }).when(plugin).runSync(any());
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));
            sync.run();
        }
        when(bans.revision()).thenReturn(1L);
        when(mutes.revision()).thenReturn(1L);
        callback.get().run();
        verify(player, never()).kick(any(net.kyori.adventure.text.Component.class));
        verifyNoInteractions(muteManager);
    }

    @Test
    void slowDatabaseDoesNotAccumulateSynchronizationTasks() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try (DatabaseExecutor executor = DatabaseExecutor.create()) {
            when(plugin.databaseExecutor()).thenReturn(executor);
            executor.execute(() -> { try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } });
            try {
                try (var bukkit = mockStatic(Bukkit.class)) {
                    bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
                    sync.run();
                    sync.run();
                    sync.run();
                }
                verifyNoInteractions(bans, mutes, identities);
            } finally {
                release.countDown();
            }
            executor.execute(() -> {}).get(5, TimeUnit.SECONDS);
            verify(bans).find(any());
            verify(mutes).find(any());
            verify(identities).invalidate();
        }
    }
}
