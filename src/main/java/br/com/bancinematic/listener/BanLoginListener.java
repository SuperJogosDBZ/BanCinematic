package br.com.bancinematic.listener;

import br.com.bancinematic.BanCinematicPlugin;
import br.com.bancinematic.punishment.PunishmentData;
import br.com.bancinematic.punishment.BanManager;
import br.com.bancinematic.util.TimeUtil;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import java.util.concurrent.TimeUnit;

public final class BanLoginListener implements Listener {
    private final java.util.function.Supplier<BanManager> manager;
    private final BanCinematicPlugin plugin;
    private final int timeout;

    public BanLoginListener(BanCinematicPlugin plugin, java.util.function.Supplier<BanManager> manager) {
        this.plugin = plugin;
        this.manager = manager;
        timeout = Math.max(1, Math.min(60, plugin.getConfig().getInt("database.login-timeout-seconds", 15)));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) return;
        BanManager banManager = manager.get();
        if (banManager == null) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    plugin.messages().component(plugin.messages().get("messages.login-storage-error")));
            return;
        }
        var lookup = plugin.databaseExecutor().submit(() -> banManager.get(event.getUniqueId()));
        try {
            PunishmentData ban = lookup.get(timeout, TimeUnit.SECONDS);
            if (ban == null) return;
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, screen(plugin, ban, event.getName()));
        } catch (Exception error) {
            lookup.cancel(false);
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            plugin.getLogger().warning("Entrada bloqueada: não foi possível verificar o ban de " + event.getUniqueId());
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    plugin.messages().component(plugin.messages().get("messages.login-storage-error")));
        }
    }

    static net.kyori.adventure.text.Component screen(BanCinematicPlugin plugin, PunishmentData ban, String name) {
        String remaining = ban.permanent() ? "" : TimeUtil.formatRemaining(ban.expires(), plugin.messages()::get);
        return plugin.messages().component(plugin.messages().screen(
                ban.permanent() ? "messages.ban-screen" : "messages.tempban-screen",
                ban.reason(), ban.source(), name, remaining));
    }
}
