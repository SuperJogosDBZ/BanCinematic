package br.com.bancinematic.listener;

import br.com.bancinematic.punishment.MuteData;
import br.com.bancinematic.punishment.MuteManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/** Reminds returning players about an active mute and its remaining time. */
public final class MuteJoinListener implements Listener {
    private final MuteManager muteManager;

    public MuteJoinListener(MuteManager muteManager) {
        this.muteManager = muteManager;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        MuteData mute = muteManager.get(event.getPlayer().getUniqueId());
        if (mute == null) return;
        event.getPlayer().sendMessage(muteManager.muteReminder(mute));
    }
}
