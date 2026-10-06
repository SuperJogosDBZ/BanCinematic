package br.com.bancinematic;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import br.com.bancinematic.util.MessageUtil;

import br.com.bancinematic.cinematic.BanCinematic;
import br.com.bancinematic.command.*;
import br.com.bancinematic.listener.BanLoginListener;
import br.com.bancinematic.listener.ChatListener;
import br.com.bancinematic.listener.MuteJoinListener;
import br.com.bancinematic.listener.PlayerIdentityListener;
import br.com.bancinematic.punishment.BanManager;
import br.com.bancinematic.punishment.MuteManager;
import br.com.bancinematic.storage.*;
import org.bukkit.plugin.java.JavaPlugin;
import net.kyori.adventure.text.Component;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class BanCinematicPlugin extends JavaPlugin {
    /** Shared message formatter; it reads the current language configuration on every lookup. */
    private final MessageUtil messages = new MessageUtil(this);
    private BanStorage banStorage;
    private MuteStorage muteStorage;
    private PunishmentLogger punishmentLogger;
    private DatabaseManager database;
    private DatabaseExecutor databaseExecutor;
    private IdentityStorage identityStorage;
    private BanManager banManager;
    private MuteManager muteManager;
    private BanCinematic cinematic;
    private FileConfiguration languageConfig;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        ensureLanguageFiles();

        getConfig().options().copyDefaults(true);
        saveConfig();
        loadLanguageConfig();

        databaseExecutor = DatabaseExecutor.create();
        try {
            database = new DatabaseManager(this);
            database.initialize();
            banStorage = new BanStorage(this, database);
            muteStorage = new MuteStorage(this, database);
            punishmentLogger = new PunishmentLogger(this, database);
            identityStorage = new IdentityStorage(this, database);
        } catch (Exception exception) {
            getLogger().severe("O BanCinematic não pôde inicializar o banco de dados: " + exception.getMessage());
            getLogger().log(java.util.logging.Level.SEVERE, "Detalhes do erro", exception);
            databaseExecutor.close();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        banManager = new BanManager(this, banStorage, punishmentLogger, databaseExecutor);
        muteManager = new MuteManager(this, muteStorage, punishmentLogger, databaseExecutor);

        cinematic = new BanCinematic(this, banManager);
        getServer().getPluginManager().registerEvents(new ChatListener(muteManager), this);
        getServer().getPluginManager().registerEvents(new MuteJoinListener(muteManager), this);
        getServer().getPluginManager().registerEvents(new BanLoginListener(this, banManager), this);
        getServer().getPluginManager().registerEvents(new PlayerIdentityListener(this), this);

        register("ban", new BanCommand(this, banManager, cinematic));
        register("tempban", new TempBanCommand(this, banManager, cinematic));
        register("unban", new UnbanCommand(this, banManager, cinematic));
        register("mute", new MuteCommand(this, muteManager));
        register("tempmute", new TempMuteCommand(this, muteManager));
        register("unmute", new UnmuteCommand(this, muteManager));
        register("kick", new KickCommand(this, punishmentLogger));
        register("history", new HistoryCommand(this, punishmentLogger));

        // The timer only enqueues work; the deletes themselves run on the database thread.
        getServer().getScheduler().runTaskTimer(this, () -> {
            databaseExecutor.execute(muteStorage::cleanupExpired);
            databaseExecutor.execute(banStorage::cleanupExpired);
        }, 20L * 60L, 20L * 60L);

        getLogger().info("BanCinematic " + getDescription().getVersion() + " habilitado.");
    }

    public MessageUtil messages() {
        return messages;
    }

    public IdentityStorage identities() {
        return identityStorage;
    }

    /** Single thread that owns every database operation. */
    public DatabaseExecutor databaseExecutor() {
        return databaseExecutor;
    }

    /**
     * Runs the task on the server thread. Safe to call from the database thread; does
     * nothing if the plugin has been disabled in the meantime, because there is no longer
     * a server thread to hand the work to.
     */
    public void runSync(Runnable task) {
        if (Bukkit.isPrimaryThread()) {
            task.run();
            return;
        }
        if (!isEnabled()) return;
        try {
            getServer().getScheduler().runTask(this, task);
        } catch (org.bukkit.plugin.IllegalPluginAccessException ignored) {
            // The plugin was disabled between the check and the call.
        }
    }

    public FileConfiguration getLanguageConfig() {
        return languageConfig;
    }

    public void broadcastPunishment(String path, String player, String reason, String source, String time) {
        String message = messages().raw(path);
        if (message == null || message.isBlank()) return;
        message = message
                .replace("%player%", player)
                .replace("%reason%", reason)
                .replace("%source%", source)
                .replace("%time%", time == null ? "" : time);
        var component = messages().component(message);
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            onlinePlayer.sendMessage(component);
        }
    }

    /** Envia mensagens de status à equipe no jogo, sem ecoá-las no console. */
    public void sendStaffMessage(CommandSender recipient, String message) {
        if (recipient instanceof ConsoleCommandSender) return;
        recipient.sendMessage(message);
    }

    /** Envia componentes de status à equipe no jogo, sem ecoá-los no console. */
    public void sendStaffMessage(CommandSender recipient, Component message) {
        if (recipient instanceof ConsoleCommandSender) return;
        recipient.sendMessage(message);
    }

    private void ensureLanguageFiles() {
        File portugueseFile = new File(getDataFolder(), "language_br.yml");
        File englishFile = new File(getDataFolder(), "language_en.yml");
        if (!portugueseFile.exists()) saveResource("language_br.yml", false);
        if (!englishFile.exists()) saveResource("language_en.yml", false);

    }

    private void loadLanguageConfig() {
        String language = getConfig().getString("language", "br").trim().toLowerCase(Locale.ROOT);
        String fileName;
        if (language.equals("en")) {
            fileName = "language_en.yml";
        } else if (language.equals("br")) {
            fileName = "language_br.yml";
        } else {
            getLogger().warning("Idioma inválido em config.yml: '" + language + "'. Usando 'br'.");
            getConfig().set("language", "br");
            saveConfig();
            fileName = "language_br.yml";
        }

        File file = new File(getDataFolder(), fileName);
        languageConfig = YamlConfiguration.loadConfiguration(file);
        try (InputStream resource = getResource(fileName)) {
            if (resource != null) {
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(resource, StandardCharsets.UTF_8));
                languageConfig.setDefaults(defaults);
            }
        } catch (IOException exception) {
            getLogger().warning("Não foi possível carregar os padrões de " + fileName + ": " + exception.getMessage());
        }
    }

    private void register(String name, org.bukkit.command.CommandExecutor executor) {
        var command = getCommand(name);
        if (command != null) {
            command.setExecutor(executor);
            if (executor instanceof org.bukkit.command.TabCompleter completer) {
                command.setTabCompleter(completer);
            }
        }
    }

    @Override
    public void onDisable() {
        if (cinematic != null) cinematic.shutdown();
        // Lets pending punishments reach the database before the server stops.
        if (databaseExecutor != null) databaseExecutor.close();
    }
}
