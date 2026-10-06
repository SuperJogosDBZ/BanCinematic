package br.com.bancinematic.listener;

import br.com.bancinematic.BanCinematicPlugin;
import br.com.bancinematic.punishment.BanData;
import br.com.bancinematic.punishment.BanManager;
import br.com.bancinematic.util.MessageUtil;
import br.com.bancinematic.util.TimeUtil;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;

public final class BanLoginListener implements Listener {
    private final BanManager banManager;
    private final MessageUtil messages;

    public BanLoginListener(BanCinematicPlugin plugin, BanManager banManager) {
        this.banManager = banManager;
        this.messages = new MessageUtil(plugin);
    }

    @EventHandler
    public void onLogin(PlayerLoginEvent event) {
        BanData ban = banManager.get(event.getPlayer().getUniqueId());
        if (ban == null) return;

        String path = ban.permanent() ? "messages.ban-screen" : "messages.tempban-screen";
        String remaining = ban.permanent()
                ? ""
                : TimeUtil.formatRemaining(ban.expires(), messages::get);
        String message = messages.screen(path, ban.reason(), ban.source(), event.getPlayer().getName(), remaining);

        event.disallow(PlayerLoginEvent.Result.KICK_BANNED, messages.component(message));
    }
}
