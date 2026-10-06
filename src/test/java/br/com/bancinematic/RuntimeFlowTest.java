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
import br.com.bancinematic.punishment.BanData;
import br.com.bancinematic.punishment.MuteManager;
import br.com.bancinematic.punishment.PunishmentResult;
import br.com.bancinematic.punishment.ExecutorIdentity;
import br.com.bancinematic.storage.BanStorage;
import br.com.bancinematic.storage.DatabaseExecutor;
import br.com.bancinematic.storage.DatabaseManager;
import br.com.bancinematic.storage.IdentityStorage;
import br.com.bancinematic.storage.MuteStorage;
import br.com.bancinematic.storage.PunishmentLogger;
import br.com.bancinematic.util.MessageUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
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
        doCallRealMethod().when(plugin).sendStaffMessage(any(CommandSender.class), anyString());
        doCallRealMethod().when(plugin).sendStaffMessage(
                any(CommandSender.class), any(net.kyori.adventure.text.Component.class));

        database = new DatabaseManager(plugin);
        database.initialize();
        identities = new IdentityStorage(plugin, database);
        when(plugin.identities()).thenReturn(identities);
        // Nos testes o "banco" roda na própria thread e o "servidor" executa na hora.
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

        BanStorage storage = new BanStorage(plugin, database);
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
        assertTrue(manager.isBanned(uuid), "Ban offline deve ser persistido pela UUID conhecida.");
        verify(cinematic, never()).start(any(), any(), anyString(), any());

        PlayerLoginEvent loginEvent = mock(PlayerLoginEvent.class);
        when(loginEvent.getPlayer()).thenReturn(player);
        new BanLoginListener(plugin, manager).onLogin(loginEvent);
        verify(loginEvent).disallow(eq(PlayerLoginEvent.Result.KICK_BANNED), any(net.kyori.adventure.text.Component.class));

        assertTrue(new UnbanCommand(plugin, manager, cinematic).onCommand(
                sender, command, "unban", new String[]{"Taupaipai"}));
        assertFalse(manager.isBanned(uuid), "Após /unban, o jogador não deve mais ser bloqueado.");
    }

    @Test
    void unbanIsBlockedWhileBanCinematicIsStillRunning() {
        UUID uuid = UUID.randomUUID();
        identities.record(uuid, "EmBan");
        BanStorage storage = new BanStorage(plugin, database);
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
        assertFalse(manager.isBanned(uuid), "O teste deve confirmar que o comando bloqueado não altera o banco.");
    }

    @Test
    void tempmuteJoinReminderChatBlockAndUnmuteWorkInSequence() {
        UUID uuid = UUID.randomUUID();
        identities.record(uuid, "Mutado");
        MuteStorage storage = new MuteStorage(plugin, database);
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
        assertNotNull(new MuteStorage(plugin, database).get(uuid).expires(),
                "A expiração do tempmute deve sobreviver ao reload do armazenamento.");
        assertTrue(manager.isMuted(uuid));

        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(uuid);
        when(player.isOnline()).thenReturn(true);
        PlayerJoinEvent joinEvent = mock(PlayerJoinEvent.class);
        when(joinEvent.getPlayer()).thenReturn(player);
        new MuteJoinListener(manager).onJoin(joinEvent);
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
        assertFalse(manager.isMuted(uuid));
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
            new KickCommand(plugin, history).onCommand(
                    sender, command, "kick", new String[]{"Alvo", "regra"});
        }

        verify(target).kick(any(net.kyori.adventure.text.Component.class));
        assertEquals(1, history.getHistoryPage(uuid, 0, 8).entries().size());
        assertNull(history.getHistoryPage(uuid, 0, 8).entries().get(0).executorUniqueId());
        assertEquals("CONSOLE", history.getHistoryPage(uuid, 0, 8).entries().get(0).executorName());
        long responses = mockingDetails(sender).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("sendMessage"))
                .count();
        assertEquals(1, responses, "O comando não deve enviar duas confirmações ao staff.");
    }

    @Test
    void banCommandCapturesThePlayerExecutorAndHistoryDisplaysIt() {
        UUID targetId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        identities.record(targetId, "Alvo");
        BanManager manager = new BanManager(plugin, new BanStorage(plugin, database),
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
        assertEquals(adminId, entry.executorUniqueId());
        assertEquals("Admin123", entry.executorName());

        CommandSender viewer = mock(CommandSender.class);
        new HistoryCommand(plugin, new PunishmentLogger(plugin, database)).onCommand(
                viewer, command, "history", new String[]{"Alvo"});
        verify(viewer).sendMessage(contains("Aplicado por: §fAdmin123"));
    }

    @Test
    void cinematicBanKeepsTheExecutorCapturedWhenTheBanIsPersisted() {
        UUID adminId = UUID.randomUUID();
        Player target = mock(Player.class);
        when(target.getUniqueId()).thenReturn(UUID.randomUUID());
        when(target.getGameMode()).thenReturn(org.bukkit.GameMode.SURVIVAL);
        Player admin = mock(Player.class);
        when(admin.getUniqueId()).thenReturn(adminId);
        when(admin.getName()).thenReturn("Admin123");
        ExecutorIdentity executor = ExecutorIdentity.from(admin);
        CinematicSession session = new CinematicSession(target, new Location(null, 0, 64, 0),
                admin, executor, "Hacking", null, CinematicSettings.from(config));

        assertEquals(adminId, session.executor().uniqueId());
        assertEquals("Admin123", session.executor().name());
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
    void staffSuccessChatDoesNotEchoToTheServerConsole() {
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);

        plugin.sendStaffMessage(console, "BAN_SUCCESS");
        plugin.sendStaffMessage(console, net.kyori.adventure.text.Component.text("MUTE_SUCCESS"));

        verify(console, never()).sendMessage(anyString());
        verify(console, never()).sendMessage(any(net.kyori.adventure.text.Component.class));
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
                new BanStorage(plugin, database),
                new PunishmentLogger(plugin, database),
                DatabaseExecutor.inline()
        );
        CommandSender sender = mock(CommandSender.class);
        when(sender.getName()).thenReturn("staff");
        config.set("messages.broadcast.ban", "BAN:%player%");
        config.set("messages.confirmations.ban", "CONFIRMED:%player%:%reason%");

        assertEquals(PunishmentResult.SUCCESS, manager.ban(uuid, "Alvo", "spam", new ExecutorIdentity(UUID.randomUUID(), "staff")).join());
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
        BanStorage storage = new BanStorage(plugin, database);
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

        BanData stored = manager.get(uuid);
        assertNotNull(stored);
        assertEquals("spam no chat", stored.reason());
        assertNotNull(stored.expires(), "Tempban deve salvar a expiração.");
        assertTrue(stored.expires().isAfter(Instant.now()));
        assertNotNull(new BanStorage(plugin, database).get(uuid).expires(),
                "A expiração do tempban deve sobreviver ao reload do armazenamento.");
    }

    @Test
    void tempmuteAcceptsDurationAfterReason() {
        UUID uuid = UUID.randomUUID();
        identities.record(uuid, "MuteTemporario");
        MuteStorage storage = new MuteStorage(plugin, database);
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
                plugin, new BanStorage(plugin, database), new PunishmentLogger(plugin, database),
                DatabaseExecutor.inline());
        MuteManager mutes = new MuteManager(
                plugin, new MuteStorage(plugin, database), new PunishmentLogger(plugin, database),
                DatabaseExecutor.inline());

        new BanCommand(plugin, bans, mock(BanCinematic.class)).onCommand(
                sender, command, "ban", new String[]{"Alvo", "2h", "spam"});
        new MuteCommand(plugin, mutes).onCommand(
                sender, command, "mute", new String[]{"Alvo", "2h", "spam"});

        verify(sender).sendMessage("USE_TEMPBAN");
        verify(sender).sendMessage("USE_TEMPMUTE");
    }
}
