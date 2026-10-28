package br.com.bancinematic;

import br.com.bancinematic.cinematic.BanCinematic;
import br.com.bancinematic.cinematic.CinematicSession;
import br.com.bancinematic.cinematic.CinematicSettings;
import br.com.bancinematic.command.BanCommand;
import br.com.bancinematic.command.KickCommand;
import br.com.bancinematic.command.HistoryCommand;
import br.com.bancinematic.command.MuteCommand;
import br.com.bancinematic.command.TempBanCommand;
import br.com.bancinematic.command.TempMuteCommand;
import br.com.bancinematic.command.UnbanCommand;
import br.com.bancinematic.command.UnmuteCommand;
import br.com.bancinematic.listener.BanLoginListener;
import br.com.bancinematic.listener.ChatListener;
import br.com.bancinematic.listener.MuteJoinListener;
import br.com.bancinematic.listener.PlayerIdentityListener;
import br.com.bancinematic.punishment.BanManager;
import br.com.bancinematic.punishment.PunishmentData;
import br.com.bancinematic.punishment.MuteManager;
import br.com.bancinematic.punishment.PunishmentResult;
import br.com.bancinematic.storage.PunishmentStorage;
import br.com.bancinematic.storage.DatabaseExecutor;
import br.com.bancinematic.storage.DatabaseManager;
import br.com.bancinematic.storage.IdentityStorage;
import br.com.bancinematic.storage.PunishmentLogger;
import br.com.bancinematic.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeFlowTest {
    @TempDir
    Path temporaryDirectory;

    private BanCinematicPlugin plugin;
    private DatabaseManager database;
    private IdentityStorage identities;
    private YamlConfiguration config;

    @BeforeEach
    void setUp() throws Exception {
        plugin = mock(BanCinematicPlugin.class);
        var dataFolder = Files.createDirectories(temporaryDirectory.resolve("plugin-data")).toFile();
        config = loadTestLanguageConfig();
        config.set("database.type", "SQLITE");
        config.set("messages.player-never-joined", "NEVER_JOINED");
        config.set("messages.ban-started", "BAN_STARTED:%player%");
        config.set("messages.already-banned", "ALREADY_BANNED");
        config.set("messages.storage-error", "STORAGE_ERROR");
        config.set("messages.unbanned", "UNBANNED:%player%");
        config.set("messages.invalid-time", "INVALID_TIME");
        config.set("messages.temp-muted", "TEMPMUTED:%player%:%time%:%reason%");
        config.set("messages.usage-tempmute", "USAGE_TEMPMUTE");
        config.set("messages.not-muted", "NOT_MUTED");
        config.set("messages.unmuted", "UNMUTED:%player%");
        config.set("messages.muted-chat", "MUTED:%reason%:%time%");
        config.set("messages.tempmute-notice", "NOTICE:%reason%:%time%");
        config.set("messages.mute-reminder", "REMINDER:%reason%:%time%");
        config.set("messages.tempban-screen.title", "BAN_SCREEN");
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getLanguageConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(dataFolder);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("BanCinematic-runtime-test"));
        when(plugin.messages()).thenReturn(new MessageUtil(plugin));

        database = new DatabaseManager(plugin);
        database.initialize();
        identities = new IdentityStorage(plugin, database);
        when(plugin.identities()).thenReturn(identities);
        when(plugin.databaseExecutor()).thenReturn(DatabaseExecutor.inline());
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(plugin).runSync(any(Runnable.class));
    }

    private YamlConfiguration loadTestLanguageConfig() throws Exception {
        try (var input = getClass().getClassLoader().getResourceAsStream("language_br.yml")) {
            assertNotNull(input, "language_br.yml deve estar disponível nos recursos de teste.");
            return YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(
                    input, java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    @Test
    void joinRecordsIdentityAndBanThenLoginThenUnbanWorkInSequence() {
        UUID uuid = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.getName()).thenReturn("Taupaipai");
        PlayerJoinEvent joinEvent = mock(PlayerJoinEvent.class);
        when(joinEvent.getPlayer()).thenReturn(player);

        new PlayerIdentityListener(plugin).onJoin(joinEvent);
        assertTrue(uuid.equals(identities.resolve("taupaipai")));

        PunishmentStorage storage = new PunishmentStorage(plugin, database, PunishmentStorage.Kind.BAN);
        PunishmentLogger history = new PunishmentLogger(plugin, database);
        BanManager manager = new BanManager(plugin, storage, history, DatabaseExecutor.inline());
        CommandSender sender = mock(CommandSender.class);
        when(sender.getName()).thenReturn("console");
        Command command = mock(Command.class);
        BanCinematic cinematic = mock(BanCinematic.class);

        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(uuid)).thenReturn(null);
            new BanCommand(plugin, manager, cinematic).onCommand(
                    sender, command, "ban", new String[]{"taupaipai", "cheating"});
        }
        assertNotNull(manager.get(uuid), "Ban offline deve ser persistido pela UUID conhecida.");
        verify(cinematic, never()).start(any(), any(), anyString(), any());

        AsyncPlayerPreLoginEvent loginEvent = mock(AsyncPlayerPreLoginEvent.class);
        when(loginEvent.getUniqueId()).thenReturn(uuid);
        when(loginEvent.getLoginResult()).thenReturn(AsyncPlayerPreLoginEvent.Result.ALLOWED);
        when(loginEvent.getName()).thenReturn("Taupaipai");
        new BanLoginListener(plugin, () -> manager).onLogin(loginEvent);
        verify(loginEvent).disallow(eq(AsyncPlayerPreLoginEvent.Result.KICK_BANNED), any(net.kyori.adventure.text.Component.class));

        assertTrue(new UnbanCommand(plugin, manager, cinematic).onCommand(
                sender, command, "unban", new String[]{"Taupaipai"}));
        assertNull(manager.get(uuid), "Após /unban, o jogador não deve mais ser bloqueado.");
    }

    @Test
    void cinematicUsesTheReconnectedPlayerAfterTheAsynchronousBanWrite() {
        UUID uuid = UUID.randomUUID();
        Player oldTarget = mock(Player.class);
        when(oldTarget.getUniqueId()).thenReturn(uuid);
        when(oldTarget.getName()).thenReturn("Alvo");
        when(oldTarget.isOnline()).thenReturn(false);
        Player currentTarget = mock(Player.class);
        when(currentTarget.getUniqueId()).thenReturn(uuid);
        when(currentTarget.getName()).thenReturn("Alvo");
        when(currentTarget.isOnline()).thenReturn(true);
        CommandSender source = mock(CommandSender.class);
        when(source.getName()).thenReturn("console");
        config.set("cinematic.enabled", false);
        configureCinematicConstruction();
        BanManager manager = new BanManager(plugin, new PunishmentStorage(plugin, database, PunishmentStorage.Kind.BAN),
                new PunishmentLogger(plugin, database), DatabaseExecutor.inline());
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        BukkitTask task = mock(BukkitTask.class);

        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(uuid)).thenReturn(currentTarget);
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            when(scheduler.runTaskLater(eq(plugin), any(Runnable.class), eq(1L))).thenAnswer(invocation -> {
                invocation.<Runnable>getArgument(1).run();
                return task;
            });

            new BanCinematic(plugin, manager).start(source, oldTarget, "Hacking", null);
        }

        verify(currentTarget).kick(any(net.kyori.adventure.text.Component.class));
        verify(oldTarget, never()).kick(any(net.kyori.adventure.text.Component.class));
    }

    @Test
    void cinematicAnnouncesWithoutKickingWhenThePlayerIsNoLongerOnline() {
        UUID uuid = UUID.randomUUID();
        Player oldTarget = mock(Player.class);
        when(oldTarget.getUniqueId()).thenReturn(uuid);
        when(oldTarget.getName()).thenReturn("Alvo");
        CommandSender source = mock(CommandSender.class);
        when(source.getName()).thenReturn("console");
        configureCinematicConstruction();
        BanManager manager = new BanManager(plugin, new PunishmentStorage(plugin, database, PunishmentStorage.Kind.BAN),
                new PunishmentLogger(plugin, database), DatabaseExecutor.inline());

        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(uuid)).thenReturn(null);
            new BanCinematic(plugin, manager).start(source, oldTarget, "Hacking", null);
        }

        verify(plugin).broadcastPunishment("messages.broadcast.ban", "Alvo", "Hacking", "CONSOLE", "");
        verify(oldTarget, never()).kick(any(net.kyori.adventure.text.Component.class));
    }

    private void configureCinematicConstruction() {
        Server server = mock(Server.class);
        PluginManager pluginManager = mock(PluginManager.class);
        PluginDescriptionFile description = mock(PluginDescriptionFile.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.namespace()).thenReturn("bancinematic");
        when(plugin.getName()).thenReturn("BanCinematic");
        when(server.getPluginManager()).thenReturn(pluginManager);
        when(plugin.getDescription()).thenReturn(description);
        when(description.getName()).thenReturn("BanCinematic");
    }

    @Test
    void unbanIsBlockedWhileBanCinematicIsStillRunning() {
        UUID uuid = UUID.randomUUID();
        identities.record(uuid, "EmBan");
        PunishmentStorage storage = new PunishmentStorage(plugin, database, PunishmentStorage.Kind.BAN);
        PunishmentLogger history = new PunishmentLogger(plugin, database);
        BanManager manager = new BanManager(plugin, storage, history, DatabaseExecutor.inline());
        CommandSender sender = mock(CommandSender.class);
        when(sender.getName()).thenReturn("console");
        Command command = mock(Command.class);
        BanCinematic cinematic = mock(BanCinematic.class);
        when(cinematic.isRunning(uuid)).thenReturn(true);

        new UnbanCommand(plugin, manager, cinematic).onCommand(
                sender, command, "unban", new String[]{"EmBan"});

        verify(sender).sendMessage(contains("Não é possível desbanir EmBan"));
        assertNull(manager.get(uuid), "O teste deve confirmar que o comando bloqueado não altera o banco.");
    }

    @Test
    void tempmuteJoinReminderChatBlockAndUnmuteWorkInSequence() {
        UUID uuid = UUID.randomUUID();
        identities.record(uuid, "Mutado");
        PunishmentStorage storage = new PunishmentStorage(plugin, database, PunishmentStorage.Kind.MUTE);
        PunishmentLogger history = new PunishmentLogger(plugin, database);
        MuteManager manager = new MuteManager(plugin, storage, history, DatabaseExecutor.inline());
        CommandSender sender = mock(CommandSender.class);
        when(sender.getName()).thenReturn("console");
        Command command = mock(Command.class);

        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(uuid)).thenReturn(null);
            new TempMuteCommand(plugin, manager).onCommand(
                    sender, command, "tempmute", new String[]{"Mutado", "2m", "spam"});
        }
        assertEquals("spam", manager.get(uuid).reason());
        assertNotNull(manager.get(uuid).expires(), "Tempmute deve salvar a expiração.");
        assertTrue(manager.get(uuid).expires().isAfter(Instant.now()));
        assertNotNull(new PunishmentStorage(plugin, database, PunishmentStorage.Kind.MUTE).get(uuid).expires(),
                "A expiração do tempmute deve sobreviver ao reload do armazenamento.");
        assertNotNull(manager.get(uuid));

        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.isOnline()).thenReturn(true);
        PlayerJoinEvent joinEvent = mock(PlayerJoinEvent.class);
        when(joinEvent.getPlayer()).thenReturn(player);
        new MuteJoinListener(plugin, manager).onJoin(joinEvent);
        verify(player).sendMessage(contains("REMINDER:spam:"));

        AsyncPlayerChatEvent chatEvent = mock(AsyncPlayerChatEvent.class);
        when(chatEvent.getPlayer()).thenReturn(player);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        BukkitTask task = mock(BukkitTask.class);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(invocation -> {
                invocation.<Runnable>getArgument(1).run();
                return task;
            });
            new ChatListener(manager).onChat(chatEvent);
        }
        verify(chatEvent).setCancelled(true);
        verify(player).sendMessage(contains("MUTED:spam:"));

        new UnmuteCommand(plugin, manager).onCommand(sender, command, "unmute", new String[]{"Mutado"});
        assertNull(manager.get(uuid));
    }

    @Test
    void cinematicSkipsUnconfirmedClientsWithoutChangingTheirProtectedState() {
        for (String scenario : List.of("not-loaded", "timeout", "quit", "respawn", "world-change", "reconnected")) {
            configureCinematicConstruction();
            UUID uuid = UUID.randomUUID();
            Player target = mock(Player.class);
            when(target.getUniqueId()).thenReturn(uuid);
            when(target.getName()).thenReturn("Alvo");
            when(target.isOnline()).thenReturn(true);
            BanManager manager = mock(BanManager.class);
            when(manager.ban(uuid, "Alvo", "regra", "CONSOLE"))
                    .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(PunishmentResult.SUCCESS));
            BanCinematic cinematic = new BanCinematic(plugin, manager);
            if (!scenario.equals("not-loaded")) {
                cinematic.onClientLoadedWorld(new io.papermc.paper.event.player.PlayerClientLoadedWorldEvent(target, false));
            }
            switch (scenario) {
                case "timeout" -> cinematic.onClientLoadedWorld(
                        new io.papermc.paper.event.player.PlayerClientLoadedWorldEvent(target, true));
                case "quit" -> cinematic.onQuit(new org.bukkit.event.player.PlayerQuitEvent(target,
                        net.kyori.adventure.text.Component.empty()));
                case "respawn" -> {
                    var event = mock(org.bukkit.event.player.PlayerRespawnEvent.class);
                    when(event.getPlayer()).thenReturn(target);
                    cinematic.onRespawn(event);
                }
                case "world-change" -> {
                    var event = mock(org.bukkit.event.player.PlayerChangedWorldEvent.class);
                    when(event.getPlayer()).thenReturn(target);
                    cinematic.onChangedWorld(event);
                }
                case "reconnected" -> {
                    target = mock(Player.class);
                    when(target.getUniqueId()).thenReturn(uuid);
                    when(target.getName()).thenReturn("Alvo");
                    when(target.isOnline()).thenReturn(true);
                }
            }
            Player current = target;
            try (var bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getPlayer(uuid)).thenReturn(current);
                bukkit.when(Bukkit::getScheduler).thenReturn(mock(BukkitScheduler.class));
                cinematic.start(mock(CommandSender.class), current, "regra", null);
                verify(current).kick(any(net.kyori.adventure.text.Component.class));
                verify(current, never()).isInvulnerable();
                verify(current, never()).setInvulnerable(org.mockito.ArgumentMatchers.anyBoolean());
                verify(current, never()).setGameMode(any());
                verify(current, never()).getPersistentDataContainer();
                assertFalse(cinematic.isRunning(uuid), scenario);
            }
        }
    }

    @Test
    void cinematicStillRunsForConfirmedClientsAndPreservesLegitimateInvulnerability() {
        try (var registries = mockPaperRegistries()) {
            for (boolean invulnerable : new boolean[]{false, true}) {
                configureCinematicConstruction();
                config.set("cinematic.darkness", false);
                config.set("cinematic.sounds", false);
                UUID uuid = UUID.randomUUID();
                Player target = mock(Player.class);
                when(target.getUniqueId()).thenReturn(uuid);
                when(target.getName()).thenReturn("Alvo");
                when(target.isOnline()).thenReturn(true);
                when(target.getLocation()).thenReturn(new Location(null, 0, 64, 0));
                when(target.getGameMode()).thenReturn(org.bukkit.GameMode.SURVIVAL);
                when(target.isInvulnerable()).thenReturn(invulnerable);
                when(target.isCollidable()).thenReturn(true);
                var data = mock(org.bukkit.persistence.PersistentDataContainer.class);
                when(target.getPersistentDataContainer()).thenReturn(data);
                BanManager manager = mock(BanManager.class);
                when(manager.ban(uuid, "Alvo", "regra", "CONSOLE"))
                        .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(PunishmentResult.SUCCESS));
                BanCinematic cinematic = new BanCinematic(plugin, manager);
                cinematic.onClientLoadedWorld(new io.papermc.paper.event.player.PlayerClientLoadedWorldEvent(target, false));
                BukkitScheduler scheduler = mock(BukkitScheduler.class);
                when(scheduler.runTaskTimer(eq(plugin), any(Runnable.class), eq(1L), eq(1L)))
                        .thenReturn(mock(BukkitTask.class));
                try (var bukkit = mockStatic(Bukkit.class)) {
                    bukkit.when(() -> Bukkit.getPlayer(uuid)).thenReturn(target);
                    bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
                    cinematic.start(mock(CommandSender.class), target, "regra", null);
                    assertTrue(cinematic.isRunning(uuid));
                    verify(target, never()).kick(any(net.kyori.adventure.text.Component.class));
                    verify(data).set(any(org.bukkit.NamespacedKey.class),
                            eq(org.bukkit.persistence.PersistentDataType.STRING), eq("SURVIVAL;" + invulnerable + ";true"));
                    verify(target).setGameMode(org.bukkit.GameMode.ADVENTURE);
                    cinematic.shutdown();
                    verify(target).setGameMode(org.bukkit.GameMode.SURVIVAL);
                    verify(target, org.mockito.Mockito.atLeastOnce()).setInvulnerable(invulnerable);
                    assertFalse(cinematic.isRunning(uuid));
                }
            }
        }
    }

    private org.mockito.MockedStatic<io.papermc.paper.registry.RegistryAccess> mockPaperRegistries() {
        var registryAccess = mockStatic(io.papermc.paper.registry.RegistryAccess.class);
        var access = mock(io.papermc.paper.registry.RegistryAccess.class, invocation ->
                mock(org.bukkit.Registry.class, lookup -> {
                    if (lookup.getMethod().getName().equals("getOrThrow")) return mock(org.bukkit.potion.PotionEffectType.class);
                    return org.mockito.Answers.RETURNS_DEFAULTS.answer(lookup);
                }));
        registryAccess.when(io.papermc.paper.registry.RegistryAccess::registryAccess).thenReturn(access);
        return registryAccess;
    }

    @Test
    void cinematicStorageFailureDoesNotKickAnnounceOrLeavePendingState() {
        configureCinematicConstruction();
        BanManager manager = mock(BanManager.class);
        Player target = mock(Player.class);
        UUID uuid = UUID.randomUUID();
        when(target.getUniqueId()).thenReturn(uuid);
        when(target.getName()).thenReturn("Alvo");
        when(manager.ban(uuid, "Alvo", "regra", "CONSOLE"))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(PunishmentResult.STORAGE_ERROR));
        CommandSender sender = mock(CommandSender.class);
        BanCinematic cinematic = new BanCinematic(plugin, manager);
        cinematic.start(sender, target, "regra", null);
        assertFalse(cinematic.isRunning(uuid));
        verify(target, never()).kick(any(net.kyori.adventure.text.Component.class));
        verify(manager, never()).announceBan(anyString(), anyString(), anyString(), any());
        verify(sender).sendMessage("STORAGE_ERROR");
    }

    @Test
    void cinematicShutdownRestoresOnlinePlayersAndRemovesSceneOnlyOnce() throws Exception {
        verifyCinematicCleanup(false);
    }

    @Test
    void cinematicQuitCleansSceneAndRestoresSavedStateOnNextJoin() throws Exception {
        verifyCinematicCleanup(true);
    }

    private void verifyCinematicCleanup(boolean quit) throws Exception {
        try (var registryAccess = mockPaperRegistries()) {
            verifyCinematicCleanupWithRegistries(quit);
        }
    }

    @SuppressWarnings("unchecked")
    private void verifyCinematicCleanupWithRegistries(boolean quit) throws Exception {
        configureCinematicConstruction();
        BanManager manager = mock(BanManager.class);
        BanCinematic cinematic = new BanCinematic(plugin, manager);
        Player player = mock(Player.class);
        UUID uuid = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.getName()).thenReturn("Alvo");
        when(player.getGameMode()).thenReturn(org.bukkit.GameMode.SURVIVAL);
        when(player.isInvulnerable()).thenReturn(false);
        when(player.isCollidable()).thenReturn(true);
        when(player.isOnline()).thenReturn(!quit);
        var data = mock(org.bukkit.persistence.PersistentDataContainer.class);
        when(player.getPersistentDataContainer()).thenReturn(data);
        CinematicSession session = new CinematicSession(player, new Location(null, 0, 64, 0),
                mock(CommandSender.class), "CONSOLE", "regra", null, CinematicSettings.from(config));
        var task = mock(org.bukkit.scheduler.BukkitRunnable.class);
        session.task(task);
        var monster = mock(org.bukkit.entity.Entity.class);
        UUID monsterId = UUID.randomUUID();
        when(monster.getUniqueId()).thenReturn(monsterId);
        when(monster.isValid()).thenReturn(true);
        session.monster(monster);
        var sessionsField = BanCinematic.class.getDeclaredField("sessions");
        sessionsField.setAccessible(true);
        ((java.util.Map<UUID, CinematicSession>) sessionsField.get(cinematic)).put(uuid, session);
        var monstersField = BanCinematic.class.getDeclaredField("activeMonsters");
        monstersField.setAccessible(true);
        ((java.util.Map<UUID, org.bukkit.entity.Entity>) monstersField.get(cinematic)).put(monsterId, monster);
        if (quit) {
            cinematic.onQuit(new org.bukkit.event.player.PlayerQuitEvent(player,
                    net.kyori.adventure.text.Component.text("quit")));
            cinematic.onQuit(new org.bukkit.event.player.PlayerQuitEvent(player,
                    net.kyori.adventure.text.Component.text("quit")));
            verify(player, never()).setGameMode(any());
            when(data.get(any(org.bukkit.NamespacedKey.class), eq(org.bukkit.persistence.PersistentDataType.STRING)))
                    .thenReturn("SURVIVAL;false;true");
            cinematic.onJoin(new PlayerJoinEvent(player, net.kyori.adventure.text.Component.text("join")));
        } else {
            cinematic.shutdown();
        }
        assertFalse(cinematic.isRunning(uuid));
        assertTrue(((java.util.Map<?, ?>) monstersField.get(cinematic)).isEmpty());
        verify(player).setGameMode(org.bukkit.GameMode.SURVIVAL);
        verify(player).setInvulnerable(false);
        verify(player).setCollidable(true);
        verify(player).removePotionEffect(org.bukkit.potion.PotionEffectType.DARKNESS);
        verify(data).remove(any(org.bukkit.NamespacedKey.class));
        verify(task).cancel();
        verify(monster).remove();
        cinematic.shutdown();
        verify(monster).remove();
        verify(task).cancel();
        if (quit) {
            verify(manager).announceBan("Alvo", "regra", "CONSOLE", null);
            verify(manager).sendBanConfirmation(session.source(), "Alvo", "regra", null);
        } else {
            org.mockito.Mockito.verifyNoInteractions(manager);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void normalCinematicKickDoesNotDuplicateAnnouncementsWhenQuitIsFired() throws Exception {
        try (var registries = mockPaperRegistries(); var bukkit = mockStatic(Bukkit.class)) {
            configureCinematicConstruction();
            BanManager manager = mock(BanManager.class);
            BanCinematic cinematic = new BanCinematic(plugin, manager);
            Player player = mock(Player.class);
            UUID uuid = UUID.randomUUID();
            when(player.getUniqueId()).thenReturn(uuid);
            when(player.getName()).thenReturn("Alvo");
            when(player.getGameMode()).thenReturn(org.bukkit.GameMode.SURVIVAL);
            when(player.getPersistentDataContainer()).thenReturn(mock(org.bukkit.persistence.PersistentDataContainer.class));
            var online = new java.util.concurrent.atomic.AtomicBoolean(true);
            when(player.isOnline()).thenAnswer(call -> online.get());
            CinematicSession session = new CinematicSession(player, new Location(null, 0, 64, 0),
                    mock(CommandSender.class), "CONSOLE", "regra", 60L, CinematicSettings.from(config));
            var sessionsField = BanCinematic.class.getDeclaredField("sessions");
            sessionsField.setAccessible(true);
            ((java.util.Map<UUID, CinematicSession>) sessionsField.get(cinematic)).put(uuid, session);
            doAnswer(call -> {
                online.set(false);
                cinematic.onQuit(new org.bukkit.event.player.PlayerQuitEvent(player,
                        net.kyori.adventure.text.Component.text("quit")));
                return null;
            }).when(player).kick(any(net.kyori.adventure.text.Component.class));
            BukkitScheduler scheduler = mock(BukkitScheduler.class);
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            when(scheduler.runTaskLater(eq(plugin), any(Runnable.class), eq(1L))).thenAnswer(call -> {
                call.<Runnable>getArgument(1).run();
                return mock(BukkitTask.class);
            });
            var finish = BanCinematic.class.getDeclaredMethod("finishHit", CinematicSession.class, Player.class);
            finish.setAccessible(true);
            finish.invoke(cinematic, session, player);
            verify(manager).announceBan("Alvo", "regra", "CONSOLE", 60L);
            verify(manager).sendBanConfirmation(session.source(), "Alvo", "regra", 60L);
            verify(player).kick(any(net.kyori.adventure.text.Component.class));
            assertFalse(cinematic.isRunning(uuid));
        }
    }

    @Test
    void historyPaginationHasNoMissingOrRepeatedRecordsAndRejectsInvalidPages() {
        UUID uuid = UUID.randomUUID();
        identities.record(uuid, "Alvo");
        PunishmentLogger history = new PunishmentLogger(plugin, database);
        for (int i = 0; i < 19; i++) {
            assertTrue(history.log(br.com.bancinematic.punishment.PunishmentType.KICK,
                    uuid, "Alvo", "reason-" + i, "CONSOLE", null));
        }
        var all = history.getHistoryPage(uuid, 0, 30).entries();
        var paged = new java.util.ArrayList<PunishmentLogger.HistoryEntry>();
        for (int page = 0; page < 3; page++) paged.addAll(history.getHistoryPage(uuid, page * 8, 8).entries());
        assertEquals(all, paged);
        assertEquals(19, paged.stream().map(PunishmentLogger.HistoryEntry::id).distinct().count());
        for (int i = 1; i < all.size(); i++) {
            assertFalse(Instant.parse(all.get(i).date()).isAfter(Instant.parse(all.get(i - 1).date())));
        }
        var command = new HistoryCommand(plugin, history);
        CommandSender sender = mock(CommandSender.class);
        command.onCommand(sender, mock(Command.class), "history", new String[]{"Alvo", "3"});
        verify(sender).sendMessage(contains("#17"));
        verify(sender).sendMessage(contains("#19"));
        verify(sender, never()).sendMessage(contains("#20"));
        CommandSender invalid = mock(CommandSender.class);
        command.onCommand(invalid, mock(Command.class), "history", new String[]{"Alvo", "4"});
        verify(invalid).sendMessage(contains("Essa página não existe"));
        for (String page : new String[]{"0", "-1", "abc", "2147483648"}) {
            CommandSender bad = mock(CommandSender.class);
            command.onCommand(bad, mock(Command.class), "history", new String[]{"Alvo", page});
            verify(bad).sendMessage(contains("A página deve ser"));
        }
    }

    @Test
    void queuedIdentityWritesPreserveJoinTimeAndAreVisibleToLaterQueuedQueries() throws Exception {
        var release = new java.util.concurrent.CountDownLatch(1);
        try (DatabaseExecutor executor = DatabaseExecutor.create()) {
            executor.execute(() -> {
                try { release.await(); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            });
            java.util.concurrent.CompletableFuture<Boolean> first;
            java.util.concurrent.CompletableFuture<Boolean> second;
            java.util.concurrent.CompletableFuture<IdentityStorage.Identity> query;
            UUID newest = UUID.randomUUID();
            Instant beforeJoin = Instant.now();
            Instant afterJoin;
            try {
                first = identities.recordAsync(executor, UUID.randomUUID(), "Alvo");
                second = identities.recordAsync(executor, newest, "Alvo");
                afterJoin = Instant.now();
                query = executor.submit(() -> identities.get("ALVO"));
                assertFalse(first.isDone());
                assertFalse(second.isDone());
                assertFalse(query.isDone());
                assertNull(identities.get("Alvo"), "Identidades ainda não gravadas não devem ser publicadas.");
            } finally {
                release.countDown();
            }
            assertTrue(first.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(second.get(5, java.util.concurrent.TimeUnit.SECONDS));
            var identity = query.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(newest, identity.uuid());
            assertTrue(identity.lastSeen().toEpochMilli() >= beforeJoin.toEpochMilli());
            assertTrue(identity.lastSeen().toEpochMilli() <= afterJoin.toEpochMilli());
            assertEquals(newest, identities.resolve("Alvo"));
        }
    }

    @Test
    void identityPersistenceCanBeRetriedAfterDatabaseFailure() throws Exception {
        DatabaseManager failingDatabase = org.mockito.Mockito.spy(database);
        IdentityStorage storage = new IdentityStorage(plugin, failingDatabase);
        org.mockito.Mockito.doThrow(new java.sql.SQLException("offline"))
                .doCallRealMethod().when(failingDatabase).connect();
        UUID uuid = UUID.randomUUID();
        assertFalse(storage.recordAsync(DatabaseExecutor.inline(), uuid, "Alvo").join());
        assertNull(storage.get("Alvo"), "Uma gravação com falha não pode deixar identidade em memória.");
        assertTrue(storage.recordAsync(DatabaseExecutor.inline(), uuid, "Alvo").join());
        IdentityStorage reloaded = new IdentityStorage(plugin, database);
        assertEquals(uuid, reloaded.resolve("Alvo"));
        assertNotNull(reloaded.get("Alvo").lastSeen());
    }

    @Test
    void checkRegistrationAndBukkitPermissionGateAllowOnlyAuthorizedSenders() throws Exception {
        var constructor = org.bukkit.command.PluginCommand.class.getDeclaredConstructor(
                String.class, org.bukkit.plugin.Plugin.class);
        constructor.setAccessible(true);
        var command = constructor.newInstance("check", plugin);
        command.setPermission("bancinematic.check");
        command.setPermissionMessage("DENIED");
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getCommand("check")).thenReturn(command);
        var register = BanCinematicPlugin.class.getDeclaredMethod("register", String.class,
                org.bukkit.command.CommandExecutor.class);
        register.setAccessible(true);
        var check = new br.com.bancinematic.command.CheckCommand(plugin,
                mock(BanManager.class), mock(MuteManager.class));
        register.invoke(plugin, "check", check);
        assertEquals(check, command.getExecutor());
        CommandSender denied = mock(CommandSender.class);
        command.execute(denied, "check", new String[]{"Desconhecido"});
        verify(denied).sendMessage(net.kyori.adventure.text.Component.text("DENIED"));
        verify(denied, never()).sendMessage("NEVER_JOINED");
        for (CommandSender allowed : new CommandSender[]{mock(Player.class), mock(ConsoleCommandSender.class)}) {
            when(allowed.hasPermission("bancinematic.check")).thenReturn(true);
            command.execute(allowed, "check", new String[]{"Desconhecido"});
            verify(allowed).sendMessage("NEVER_JOINED");
        }
    }

    @Test
    void checkShowsPersistedIdentityAndActivePunishmentsToConsoleThroughWorker() {
        UUID uuid = UUID.randomUUID();
        assertTrue(identities.record(uuid, "Alvo"));
        var recorded = identities.get("alvo");
        assertEquals(recorded.lastSeen().toEpochMilli(), identities.get("ALVO").lastSeen().toEpochMilli());
        PunishmentLogger history = new PunishmentLogger(plugin, database);
        BanManager bans = new BanManager(plugin, new PunishmentStorage(plugin, database, PunishmentStorage.Kind.BAN), history, DatabaseExecutor.inline());
        MuteManager mutes = new MuteManager(plugin, new PunishmentStorage(plugin, database, PunishmentStorage.Kind.MUTE), history, DatabaseExecutor.inline());
        assertEquals(PunishmentResult.SUCCESS, bans.ban(uuid, "Alvo", "BanReason", "Admin").join());
        assertEquals(PunishmentResult.SUCCESS, mutes.tempMute(uuid, "Alvo", "MuteReason", "Staff", 3600).join());
        ConsoleCommandSender sender = mock(ConsoleCommandSender.class);
        new br.com.bancinematic.command.CheckCommand(plugin, bans, mutes).onCommand(
                sender, mock(Command.class), "check", new String[]{"aLvO"});
        verify(sender).sendMessage(contains(uuid.toString()));
        verify(sender).sendMessage(contains("Última entrada:"));
        verify(sender).sendMessage(contains("BanReason"));
        verify(sender).sendMessage(contains("MuteReason"));
        verify(sender).sendMessage(contains("permanente"));
        verify(plugin).databaseExecutor();
    }

    @Test
    void checkHandlesUnknownPlayersUsageAndExpiredPunishments() {
        UUID uuid = UUID.randomUUID();
        identities.record(uuid, "Alvo");
        PunishmentStorage banStorage = new PunishmentStorage(plugin, database, PunishmentStorage.Kind.BAN);
        PunishmentStorage muteStorage = new PunishmentStorage(plugin, database, PunishmentStorage.Kind.MUTE);
        assertTrue(banStorage.put(new PunishmentData(uuid, "Alvo", "expirado", "Admin", Instant.now().minusSeconds(1))));
        assertTrue(muteStorage.put(new br.com.bancinematic.punishment.PunishmentData(
                uuid, "Alvo", "expirado", "Admin", Instant.now().minusSeconds(1))));
        PunishmentLogger history = new PunishmentLogger(plugin, database);
        var check = new br.com.bancinematic.command.CheckCommand(plugin,
                new BanManager(plugin, banStorage, history, DatabaseExecutor.inline()),
                new MuteManager(plugin, muteStorage, history, DatabaseExecutor.inline()));
        CommandSender sender = mock(CommandSender.class);
        check.onCommand(sender, mock(Command.class), "check", new String[]{});
        verify(sender).sendMessage(contains("Uso: /check"));
        check.onCommand(sender, mock(Command.class), "check", new String[]{"Desconhecido"});
        verify(sender).sendMessage("NEVER_JOINED");
        check.onCommand(sender, mock(Command.class), "check", new String[]{"Alvo"});
        verify(sender).sendMessage(contains("Ban: §aNenhum ativo"));
        verify(sender).sendMessage(contains("Mute: §aNenhum ativo"));
    }

    @Test
    void unmuteAcceptsBothUuidFormatsAndPreservesTheMutedNameDespiteNameReuse() {
        PunishmentLogger history = new PunishmentLogger(plugin, database);
        MuteManager manager = new MuteManager(plugin, new PunishmentStorage(plugin, database, PunishmentStorage.Kind.MUTE), history, DatabaseExecutor.inline());
        UUID other = UUID.randomUUID();
        identities.record(other, "NomeAntigo");
        assertEquals(PunishmentResult.SUCCESS, manager.mute(other, "OutraConta", "regra", "CONSOLE").join());
        for (boolean compact : new boolean[]{false, true}) {
            UUID uuid = UUID.randomUUID();
            assertEquals(PunishmentResult.SUCCESS, manager.mute(uuid, "NomeAntigo", "regra", "CONSOLE").join());
            CommandSender sender = mock(CommandSender.class);
            String input = compact ? uuid.toString().replace("-", "").toUpperCase(java.util.Locale.ROOT) : uuid.toString();
            new UnmuteCommand(plugin, manager).onCommand(sender, mock(Command.class), "unmute", new String[]{input});
            assertNull(manager.get(uuid));
            assertNotNull(manager.get(other));
            verify(sender).sendMessage("UNMUTED:NomeAntigo");
            var entry = history.getHistoryPage(uuid, 0, 8).entries().stream()
                    .filter(record -> record.type().equals("UNMUTE")).findFirst().orElseThrow();
            assertEquals(uuid, entry.uniqueId());
            assertEquals("NomeAntigo", entry.player());
        }
    }

    @Test
    void unmutePrefixSearchReturnsActiveNamesWithoutOnlinePlayerQueries() {
        DatabaseManager tracked = org.mockito.Mockito.spy(database);
        PunishmentStorage storage = new PunishmentStorage(plugin, tracked, PunishmentStorage.Kind.MUTE);
        PunishmentLogger history = new PunishmentLogger(plugin, tracked);
        UUID removed = UUID.randomUUID();
        assertTrue(storage.put(new br.com.bancinematic.punishment.PunishmentData(UUID.randomUUID(), "Bruno", "regra", "CONSOLE", null)));
        assertTrue(storage.put(new br.com.bancinematic.punishment.PunishmentData(UUID.randomUUID(), "Alice", "regra", "CONSOLE", Instant.now().plusSeconds(3600))));
        assertTrue(storage.put(new br.com.bancinematic.punishment.PunishmentData(UUID.randomUUID(), "Antigo", "regra", "CONSOLE", Instant.now().minusSeconds(60))));
        assertTrue(storage.put(new br.com.bancinematic.punishment.PunishmentData(removed, "Removido", "regra", "CONSOLE", null)));
        assertEquals(PunishmentResult.SUCCESS, storage.removeAndLog(removed, "Removido", "Removido", "CONSOLE",
                br.com.bancinematic.punishment.PunishmentType.UNMUTE, history));
        org.mockito.Mockito.clearInvocations(tracked);
        var command = new UnmuteCommand(plugin, new MuteManager(plugin, storage, history, DatabaseExecutor.inline()));
        CommandSender sender = mock(CommandSender.class);
        Command bukkitCommand = mock(Command.class);
        try (var bukkit = mockStatic(Bukkit.class)) {
            assertEquals(List.of("Alice", "Bruno"), storage.suggestNames(""));
            assertEquals(List.of("Alice"), storage.suggestNames("aL"));
            assertEquals(List.of(), storage.suggestNames(removed.toString()));
            assertEquals(List.of(), command.onTabComplete(sender, bukkitCommand, "unmute", new String[]{"Alice", ""}));
            bukkit.verifyNoInteractions();

        }
    }

    @Test
    void unmuteRejectsMalformedUnknownAndExpiredTargetsWithoutAddingHistory() {
        PunishmentStorage storage = new PunishmentStorage(plugin, database, PunishmentStorage.Kind.MUTE);
        UUID expired = UUID.randomUUID();
        assertTrue(storage.put(new br.com.bancinematic.punishment.PunishmentData(expired, "Expirado", "regra", "CONSOLE", Instant.now().minusSeconds(60))));
        PunishmentLogger history = new PunishmentLogger(plugin, database);
        var command = new UnmuteCommand(plugin, new MuteManager(plugin, storage, history, DatabaseExecutor.inline()));
        for (String input : new String[]{"1-1-1-1-1", "Desconhecido", UUID.randomUUID().toString(), expired.toString()}) {
            CommandSender sender = mock(CommandSender.class);
            command.onCommand(sender, mock(Command.class), "unmute", new String[]{input});
            verify(sender).sendMessage("NOT_MUTED");
        }
        assertEquals(0, history.getHistoryPage(expired, 0, 8).totalEntries());
    }

    @Test
    void unbanDistinguishesUnknownNamesFromKnownPlayersAndExplicitUuidsWithoutBans() {
        config.set("messages.already-unbanned", "NOT_BANNED");
        UUID known = UUID.randomUUID();
        assertTrue(identities.record(known, "Conhecido"));
        BanManager manager = new BanManager(plugin, new PunishmentStorage(plugin, database, PunishmentStorage.Kind.BAN),
                new PunishmentLogger(plugin, database), DatabaseExecutor.inline());
        UnbanCommand command = new UnbanCommand(plugin, manager, mock(BanCinematic.class));
        for (String input : new String[]{"Desconhecido", "Conhecido", UUID.randomUUID().toString()}) {
            CommandSender sender = mock(CommandSender.class);
            command.onCommand(sender, mock(Command.class), "unban", new String[]{input});
            String expected = input.equals("Desconhecido") ? "NEVER_JOINED" : "NOT_BANNED";
            String unexpected = input.equals("Desconhecido") ? "NOT_BANNED" : "NEVER_JOINED";
            verify(sender).sendMessage(expected);
            verify(sender, never()).sendMessage(unexpected);
            verify(sender, never()).sendMessage("STORAGE_ERROR");
        }
        assertEquals(0, new PunishmentLogger(plugin, database).getHistoryPage(known, 0, 8).totalEntries());
    }

    @Test
    void unbanAcceptsBothUuidFormatsWithoutKnownNamesAndPreservesTheBannedName() {
        BanManager manager = new BanManager(plugin, new PunishmentStorage(plugin, database, PunishmentStorage.Kind.BAN),
                new PunishmentLogger(plugin, database), DatabaseExecutor.inline());
        UUID unrelated = UUID.randomUUID();
        identities.record(unrelated, "NomeAntigo");
        assertEquals(PunishmentResult.SUCCESS, manager.ban(unrelated, "OutraConta", "regra", "CONSOLE").join());
        for (boolean compact : new boolean[]{false, true}) {
            UUID uuid = UUID.randomUUID();
            assertEquals(PunishmentResult.SUCCESS, manager.ban(uuid, "NomeAntigo", "regra", "CONSOLE").join());
            CommandSender sender = mock(CommandSender.class);
            String input = compact ? uuid.toString().replace("-", "").toUpperCase(java.util.Locale.ROOT)
                    : uuid.toString();
            new UnbanCommand(plugin, manager, mock(BanCinematic.class)).onCommand(
                    sender, mock(Command.class), "unban", new String[]{input});
            assertNull(manager.get(uuid));
            assertNotNull(manager.get(unrelated));
            verify(sender).sendMessage("UNBANNED:NomeAntigo");
            var entry = new PunishmentLogger(plugin, database).getHistoryPage(uuid, 0, 8).entries().stream()
                    .filter(record -> record.type().equals("UNBAN")).findFirst().orElseThrow();
            assertEquals(uuid, entry.uniqueId());
            assertEquals("NomeAntigo", entry.player());
        }
    }

    @Test
    void unbanUuidStillRespectsTheCinematicAndUnknownOrMalformedInputsDoNothing() {
        UUID uuid = UUID.randomUUID();
        BanManager manager = new BanManager(plugin, new PunishmentStorage(plugin, database, PunishmentStorage.Kind.BAN),
                new PunishmentLogger(plugin, database), DatabaseExecutor.inline());
        assertEquals(PunishmentResult.SUCCESS, manager.ban(uuid, "Alvo", "regra", "CONSOLE").join());
        BanCinematic cinematic = mock(BanCinematic.class);
        when(cinematic.isRunning(uuid)).thenReturn(true);
        UnbanCommand command = new UnbanCommand(plugin, manager, cinematic);
        CommandSender sender = mock(CommandSender.class);
        for (String input : new String[]{uuid.toString(), UUID.randomUUID().toString(), "1-1-1-1-1", "invalido"}) {
            command.onCommand(sender, mock(Command.class), "unban", new String[]{input});
        }
        assertNotNull(manager.get(uuid));
        assertEquals(1, new PunishmentLogger(plugin, database).getHistoryPage(uuid, 0, 8).totalEntries());
    }

    @Test
    void unbanPrefixSearchReturnsActiveNamesWithoutOnlinePlayerQueries() {
        DatabaseManager trackedDatabase = org.mockito.Mockito.spy(database);
        PunishmentStorage storage = new PunishmentStorage(plugin, trackedDatabase, PunishmentStorage.Kind.BAN);
        UUID removed = UUID.randomUUID();
        assertTrue(storage.put(new PunishmentData(UUID.randomUUID(), "Bruno", "regra", "CONSOLE", null)));
        assertTrue(storage.put(new PunishmentData(UUID.randomUUID(), "Alice", "regra", "CONSOLE",
                Instant.now().plusSeconds(3600))));
        assertTrue(storage.put(new PunishmentData(UUID.randomUUID(), "Antigo", "regra", "CONSOLE",
                Instant.now().minusSeconds(60))));
        assertTrue(storage.put(new PunishmentData(removed, "Removido", "regra", "CONSOLE", null)));
        assertEquals(PunishmentResult.SUCCESS, storage.removeAndLog(removed, "Removido", "Removido", "CONSOLE",
                br.com.bancinematic.punishment.PunishmentType.UNBAN, new PunishmentLogger(plugin, database)));
        org.mockito.Mockito.clearInvocations(trackedDatabase);
        BanManager manager = new BanManager(plugin, storage, new PunishmentLogger(plugin, database),
                DatabaseExecutor.inline());
        UnbanCommand completion = new UnbanCommand(plugin, manager, mock(BanCinematic.class));
        CommandSender sender = mock(CommandSender.class);
        Command command = mock(Command.class);
        try (var bukkit = mockStatic(Bukkit.class)) {
            assertEquals(List.of("Alice", "Bruno"),
                    storage.suggestNames(""));
            assertEquals(List.of("Alice"),
                    storage.suggestNames("aL"));
            assertEquals(List.of(),
                    storage.suggestNames("z"));
            assertEquals(List.of(),
                    completion.onTabComplete(sender, command, "unban", new String[]{"Alice", ""}));
            bukkit.verifyNoInteractions();

        }
    }

    @Test
    void kickRecordsThePunishmentAndSendsOneConfiguredConfirmation() {
        UUID uuid = UUID.randomUUID();
        Player target = mock(Player.class);
        when(target.getUniqueId()).thenReturn(uuid);
        when(target.getName()).thenReturn("Alvo");
        when(target.isOnline()).thenReturn(true);
        CommandSender sender = mock(CommandSender.class);
        when(sender.getName()).thenReturn("staff");
        Command command = mock(Command.class);
        config.set("messages.confirmations.kick", "KICKED:%player%:%reason%");
        PunishmentLogger history = new PunishmentLogger(plugin, database);

        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayerExact("Alvo")).thenReturn(target);
            bukkit.when(() -> Bukkit.getPlayer(uuid)).thenReturn(target);
            new KickCommand(plugin, history).onCommand(
                    sender, command, "kick", new String[]{"Alvo", "regra"});
        }

        verify(target).kick(any(net.kyori.adventure.text.Component.class));
        assertEquals(1, history.getHistoryPage(uuid, 0, 8).entries().size());
        assertEquals("CONSOLE", history.getHistoryPage(uuid, 0, 8).entries().get(0).source());
        long responses = mockingDetails(sender).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("sendMessage"))
                .count();
        assertEquals(1, responses, "O comando não deve enviar duas confirmações ao staff.");
    }

    @Test
    void kickRecordsThePlayerSource() {
        UUID targetId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        Player target = mock(Player.class);
        when(target.getUniqueId()).thenReturn(targetId);
        when(target.getName()).thenReturn("Alvo");
        when(target.isOnline()).thenReturn(true);
        Player admin = mock(Player.class);
        when(admin.getUniqueId()).thenReturn(adminId);
        when(admin.getName()).thenReturn("Admin123");
        Command command = mock(Command.class);
        PunishmentLogger history = new PunishmentLogger(plugin, database);

        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayerExact("Alvo")).thenReturn(target);
            bukkit.when(() -> Bukkit.getPlayer(targetId)).thenReturn(target);
            new KickCommand(plugin, history).onCommand(
                    admin, command, "kick", new String[]{"Alvo", "regra"});
        }

        var entry = history.getHistoryPage(targetId, 0, 8).entries().get(0);
        assertEquals("Admin123", entry.source());
    }

    @Test
    void kickCompletesWhileTheDatabaseQueueIsBlocked() throws Exception {
        UUID uuid = UUID.randomUUID();
        Player target = mock(Player.class);
        when(target.getUniqueId()).thenReturn(uuid);
        when(target.getName()).thenReturn("Alvo");
        CommandSender sender = mock(CommandSender.class);
        PunishmentLogger history = mock(PunishmentLogger.class);
        var release = new java.util.concurrent.CountDownLatch(1);
        try (DatabaseExecutor executor = DatabaseExecutor.create()) {
            when(plugin.databaseExecutor()).thenReturn(executor);
            executor.execute(() -> {
                try {
                    release.await();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            });
            try {
                try (var bukkit = mockStatic(Bukkit.class)) {
                    bukkit.when(() -> Bukkit.getPlayerExact("Alvo")).thenReturn(target);
                    new KickCommand(plugin, history).onCommand(
                            sender, mock(Command.class), "kick", new String[]{"Alvo", "regra"});
                }
                verify(target).kick(any(net.kyori.adventure.text.Component.class));
                verify(sender).sendMessage(any(net.kyori.adventure.text.Component.class));
                verify(history).logConsole(br.com.bancinematic.punishment.PunishmentType.KICK,
                        uuid, "Alvo", "regra", "CONSOLE", null);
                verify(history, never()).log(any(), any(), anyString(), anyString(), anyString(), any());
            } finally {
                release.countDown();
            }
            executor.execute(() -> {}).get(5, java.util.concurrent.TimeUnit.SECONDS);
            verify(history).log(br.com.bancinematic.punishment.PunishmentType.KICK,
                    uuid, "Alvo", "regra", "CONSOLE", null);
        }
    }

    @Test
    void kickStillSucceedsWhenHistoryFailsOrThrows() {
        for (boolean throwsError : new boolean[]{false, true}) {
            UUID uuid = UUID.randomUUID();
            Player target = mock(Player.class);
            when(target.getUniqueId()).thenReturn(uuid);
            when(target.getName()).thenReturn("Alvo");
            CommandSender sender = mock(CommandSender.class);
            PunishmentLogger history = mock(PunishmentLogger.class);
            when(history.log(any(), any(), anyString(), anyString(), anyString(), any()))
                    .thenAnswer(invocation -> {
                        verify(target).kick(any(net.kyori.adventure.text.Component.class));
                        if (throwsError) throw new IllegalStateException("Banco indisponível");
                        return false;
                    });
            try (var bukkit = mockStatic(Bukkit.class)) {
                bukkit.when(() -> Bukkit.getPlayerExact("Alvo")).thenReturn(target);
                new KickCommand(plugin, history).onCommand(
                        sender, mock(Command.class), "kick", new String[]{"Alvo", "regra"});
            }
            verify(target).kick(any(net.kyori.adventure.text.Component.class));
            verify(sender).sendMessage(any(net.kyori.adventure.text.Component.class));
            verify(sender, never()).sendMessage("STORAGE_ERROR");
            verify(history).logConsole(br.com.bancinematic.punishment.PunishmentType.KICK,
                    uuid, "Alvo", "regra", "CONSOLE", null);
        }
    }
    @Test
    void banCommandCapturesThePlayerExecutorAndHistoryDisplaysIt() {
        UUID targetId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        identities.record(targetId, "Alvo");
        BanManager manager = new BanManager(plugin, new PunishmentStorage(plugin, database, PunishmentStorage.Kind.BAN),
                new PunishmentLogger(plugin, database), DatabaseExecutor.inline());
        Player admin = mock(Player.class);
        when(admin.getUniqueId()).thenReturn(adminId);
        when(admin.getName()).thenReturn("Admin123");
        Command command = mock(Command.class);

        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(targetId)).thenReturn(null);
            new BanCommand(plugin, manager, mock(BanCinematic.class)).onCommand(
                    admin, command, "ban", new String[]{"Alvo", "Hacking"});
        }

        PunishmentLogger.HistoryEntry entry = new PunishmentLogger(plugin, database)
                .getHistoryPage(targetId, 0, 8).entries().get(0);
        assertEquals("Admin123", entry.source());

        CommandSender viewer = mock(CommandSender.class);
        new HistoryCommand(plugin, new PunishmentLogger(plugin, database)).onCommand(
                viewer, command, "history", new String[]{"Alvo"});
        verify(viewer).sendMessage(contains("Aplicado por: §fAdmin123"));
    }

    @Test
    void historyStoresSecondsAndFormatsUsingTheCurrentLanguage() throws Exception {
        UUID uuid = UUID.randomUUID();
        identities.record(uuid, "Alvo");
        PunishmentLogger history = new PunishmentLogger(plugin, database);
        BanManager bans = new BanManager(plugin, new PunishmentStorage(plugin, database, PunishmentStorage.Kind.BAN), history,
                DatabaseExecutor.inline());
        MuteManager mutes = new MuteManager(plugin, new PunishmentStorage(plugin, database, PunishmentStorage.Kind.MUTE), history,
                DatabaseExecutor.inline());
        assertEquals(PunishmentResult.SUCCESS, bans.tempBan(uuid, "Alvo", "regra", "CONSOLE", 172800).join());
        assertEquals(PunishmentResult.SUCCESS, mutes.tempMute(uuid, "Alvo", "regra", "CONSOLE", 3600).join());
        assertEquals(java.util.Set.of(172800L, 3600L), history.getHistoryPage(uuid, 0, 8).entries()
                .stream().map(PunishmentLogger.HistoryEntry::durationSeconds).collect(java.util.stream.Collectors.toSet()));
        try (var connection = database.connect();
             var statement = connection.prepareStatement("SELECT duration_seconds FROM punishment_history");
             var result = statement.executeQuery()) {
            assertTrue(result.next());
            assertNotNull(result.getObject(1));
            assertTrue(result.getObject(1) instanceof Number);
        }
        config.set("messages.duration.units.day.plural", "days");
        config.set("messages.duration.units.hour.singular", "hour");
        CommandSender viewer = mock(CommandSender.class);
        new HistoryCommand(plugin, history).onCommand(viewer, mock(Command.class), "history", new String[]{"Alvo"});
        verify(viewer).sendMessage(contains("2 days"));
        verify(viewer).sendMessage(contains("1 hour"));
        assertTrue(history.log(br.com.bancinematic.punishment.PunishmentType.BAN,
                uuid, "Alvo", "regra", "CONSOLE", null));
        assertTrue(history.getHistoryPage(uuid, 0, 8).entries().stream()
                .anyMatch(entry -> entry.type().equals("BAN") && entry.durationSeconds() == null));
    }

    @Test
    void historyHidesInapplicableFieldsForUnbanUnmuteAndKick() {
        UUID targetId = UUID.randomUUID();
        identities.record(targetId, "Alvo");
        PunishmentLogger history = new PunishmentLogger(plugin, database);
        history.log(br.com.bancinematic.punishment.PunishmentType.UNBAN,
                targetId, "Alvo", "Removido", "Admin123", null);
        history.log(br.com.bancinematic.punishment.PunishmentType.UNMUTE,
                targetId, "Alvo", "Removido", "CONSOLE", null);
        history.log(br.com.bancinematic.punishment.PunishmentType.KICK,
                targetId, "Alvo", "Insultos", "Admin456", null);

        CommandSender viewer = mock(CommandSender.class);
        Command command = mock(Command.class);
        new HistoryCommand(plugin, history).onCommand(viewer, command, "history", new String[]{"Alvo"});

        verify(viewer).sendMessage(contains("DESMUTE"));
        verify(viewer).sendMessage(contains("DESBANIMENTO"));
        verify(viewer).sendMessage(contains("KICK"));
        verify(viewer).sendMessage(contains("Motivo: §fInsultos"));
        verify(viewer).sendMessage(contains("└ §7Aplicado por: §fCONSOLE"));
        verify(viewer).sendMessage(contains("└ §7Aplicado por: §fAdmin123"));
        verify(viewer).sendMessage(contains("└ §7Aplicado por: §fAdmin456"));
        verify(viewer, never()).sendMessage(contains("Motivo: §fRemovido"));
        verify(viewer, never()).sendMessage(contains("Duração: §fPermanente"));
    }

    @Test
    void punishmentBroadcastIsSentToOnlinePlayersWithoutUsingServerBroadcast() {
        Player first = mock(Player.class);
        Player second = mock(Player.class);
        config.set("messages.broadcast.ban", "BAN:%player%:%reason%:%source%:%time%");
        doCallRealMethod().when(plugin).broadcastPunishment(
                anyString(), anyString(), anyString(), anyString(), anyString());

        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(first, second));

            plugin.broadcastPunishment("messages.broadcast.ban", "Alvo", "spam", "staff", "");

            verify(first).sendMessage(any(net.kyori.adventure.text.Component.class));
            verify(second).sendMessage(any(net.kyori.adventure.text.Component.class));
            bukkit.verify(
                    () -> Bukkit.broadcast(any(net.kyori.adventure.text.Component.class)),
                    never()
            );
        }
    }

    @Test
    void banTempbanMuteAndTempmuteConfirmSuccessToConsole() {
        UUID uuid = UUID.randomUUID();
        identities.record(uuid, "Alvo");
        PunishmentLogger history = new PunishmentLogger(plugin, database);
        BanManager bans = new BanManager(plugin, new PunishmentStorage(plugin, database, PunishmentStorage.Kind.BAN), history,
                DatabaseExecutor.inline());
        MuteManager mutes = new MuteManager(plugin, new PunishmentStorage(plugin, database, PunishmentStorage.Kind.MUTE), history,
                DatabaseExecutor.inline());
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        when(console.getName()).thenReturn("CONSOLE");
        Command command = mock(Command.class);
        config.set("messages.confirmations.ban", "BAN_OK:%player%");
        config.set("messages.confirmations.tempban", "TEMPBAN_OK:%player%");
        config.set("messages.muted", "MUTE_OK:%player%");
        config.set("messages.temp-muted", "TEMPMUTE_OK:%player%");
        try (var bukkit = mockStatic(Bukkit.class)) {
            new BanCommand(plugin, bans, mock(BanCinematic.class)).onCommand(
                    console, command, "ban", new String[]{"Alvo", "regra"});
            verify(console).sendMessage(net.kyori.adventure.text.Component.text("BAN_OK:Alvo"));
            assertEquals(PunishmentResult.SUCCESS, bans.unban(uuid, "Alvo", "CONSOLE").join());
            new TempBanCommand(plugin, bans, mock(BanCinematic.class)).onCommand(
                    console, command, "tempban", new String[]{"Alvo", "1d", "regra"});
            verify(console).sendMessage(net.kyori.adventure.text.Component.text("TEMPBAN_OK:Alvo"));
            new MuteCommand(plugin, mutes).onCommand(
                    console, command, "mute", new String[]{"Alvo", "regra"});
            verify(console).sendMessage("MUTE_OK:Alvo");
            assertEquals(PunishmentResult.SUCCESS, mutes.unmute(uuid, "Alvo", "CONSOLE").join());
            new TempMuteCommand(plugin, mutes).onCommand(
                    console, command, "tempmute", new String[]{"Alvo", "1d", "regra"});
            verify(console).sendMessage("TEMPMUTE_OK:Alvo");
        }
    }

    @Test
    void messageLookupUsesTheSelectedLanguageFile() {
        YamlConfiguration english = new YamlConfiguration();
        english.set("messages.player-not-found", "English translation");
        config.set("messages.player-not-found", "Texto antigo no config");
        when(plugin.getLanguageConfig()).thenReturn(english);

        assertEquals("English translation", plugin.messages().get("messages.player-not-found"));
    }

    @Test
    void banIsNotAnnouncedUntilTheCallerExplicitlyCompletesTheKickFlow() {
        UUID uuid = UUID.randomUUID();
        BanManager manager = new BanManager(
                plugin,
                new PunishmentStorage(plugin, database, PunishmentStorage.Kind.BAN),
                new PunishmentLogger(plugin, database),
                DatabaseExecutor.inline()
        );
        CommandSender sender = mock(CommandSender.class);
        when(sender.getName()).thenReturn("staff");
        config.set("messages.broadcast.ban", "BAN:%player%");
        config.set("messages.confirmations.ban", "CONFIRMED:%player%:%reason%");

        assertEquals(PunishmentResult.SUCCESS, manager.ban(uuid, "Alvo", "spam", "staff").join());
        verify(plugin, never()).broadcastPunishment(
                anyString(), anyString(), anyString(), anyString(), anyString());

        manager.announceBan("Alvo", "spam", "staff", null);
        manager.sendBanConfirmation(sender, "Alvo", "spam", null);

        verify(plugin).broadcastPunishment(
                "messages.broadcast.ban", "Alvo", "spam", "staff", "");
        verify(sender).sendMessage(any(net.kyori.adventure.text.Component.class));
    }

    @Test
    void tempbanStoresDurationAndKeepsItOutOfTheReason() {
        UUID uuid = UUID.randomUUID();
        identities.record(uuid, "BanTemporario");
        PunishmentStorage storage = new PunishmentStorage(plugin, database, PunishmentStorage.Kind.BAN);
        PunishmentLogger history = new PunishmentLogger(plugin, database);
        BanManager manager = new BanManager(plugin, storage, history, DatabaseExecutor.inline());
        CommandSender sender = mock(CommandSender.class);
        when(sender.getName()).thenReturn("staff");
        Command command = mock(Command.class);

        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(uuid)).thenReturn(null);
            new TempBanCommand(plugin, manager, mock(BanCinematic.class)).onCommand(
                    sender, command, "tempban",
                    new String[]{"BanTemporario", "spam", "no chat", "15m"});
        }

        PunishmentData stored = manager.get(uuid);
        assertNotNull(stored);
        assertEquals("spam no chat", stored.reason());
        assertNotNull(stored.expires(), "Tempban deve salvar a expiração.");
        assertTrue(stored.expires().isAfter(Instant.now()));
        assertNotNull(new PunishmentStorage(plugin, database, PunishmentStorage.Kind.BAN).get(uuid).expires(),
                "A expiração do tempban deve sobreviver ao reload do armazenamento.");
    }

    @Test
    void tempmuteAcceptsDurationAfterReason() {
        UUID uuid = UUID.randomUUID();
        identities.record(uuid, "MuteTemporario");
        PunishmentStorage storage = new PunishmentStorage(plugin, database, PunishmentStorage.Kind.MUTE);
        MuteManager manager = new MuteManager(plugin, storage, new PunishmentLogger(plugin, database), DatabaseExecutor.inline());
        CommandSender sender = mock(CommandSender.class);
        when(sender.getName()).thenReturn("staff");
        Command command = mock(Command.class);

        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(uuid)).thenReturn(null);
            new TempMuteCommand(plugin, manager).onCommand(
                    sender, command, "tempmute",
                    new String[]{"MuteTemporario", "spam", "no chat", "15m"});
        }

        assertEquals("spam no chat", manager.get(uuid).reason());
        assertNotNull(manager.get(uuid).expires());
        assertTrue(manager.get(uuid).expires().isAfter(Instant.now()));
    }

    @Test
    void permanentBanAndMuteRejectDurationLikeArguments() {
        config.set("messages.permanent-ban-time-hint", "USE_TEMPBAN");
        config.set("messages.permanent-mute-time-hint", "USE_TEMPMUTE");
        CommandSender sender = mock(CommandSender.class);
        Command command = mock(Command.class);
        BanManager bans = new BanManager(
                plugin, new PunishmentStorage(plugin, database, PunishmentStorage.Kind.BAN), new PunishmentLogger(plugin, database),
                DatabaseExecutor.inline());
        MuteManager mutes = new MuteManager(
                plugin, new PunishmentStorage(plugin, database, PunishmentStorage.Kind.MUTE), new PunishmentLogger(plugin, database),
                DatabaseExecutor.inline());

        new BanCommand(plugin, bans, mock(BanCinematic.class)).onCommand(
                sender, command, "ban", new String[]{"Alvo", "2h", "spam"});
        new MuteCommand(plugin, mutes).onCommand(
                sender, command, "mute", new String[]{"Alvo", "2h", "spam"});

        verify(sender).sendMessage("USE_TEMPBAN");
        verify(sender).sendMessage("USE_TEMPMUTE");
    }
}
