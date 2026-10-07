package br.com.bancinematic.listener;

import br.com.bancinematic.BanCinematicPlugin;
import br.com.bancinematic.punishment.BanData;
import br.com.bancinematic.punishment.BanManager;
import br.com.bancinematic.util.TimeUtil;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;

public final class BanLoginListener implements Listener {
    private final BanManager banManager;
    private final BanCinematicPlugin plugin;

    public BanLoginListener(BanCinematicPlugin plugin, BanManager banManager) {
        this.plugin = plugin;
        this.banManager = banManager;
    }

    @EventHandler
    public void onLogin(PlayerLoginEvent event) {
        BanData ban = banManager.get(event.getPlayer().getUniqueId());
        if (ban == null) return;

        String path = ban.permanent() ? "messages.ban-screen" : "messages.tempban-screen";
        String remaining = ban.permanent()
                ? ""
                : TimeUtil.formatRemaining(ban.expires(), plugin.messages()::get);
        String message = plugin.messages().screen(path, ban.reason(), ban.source(), event.getPlayer().getName(), remaining);

        event.disallow(PlayerLoginEvent.Result.KICK_BANNED, plugin.messages().component(message));
    }
}
