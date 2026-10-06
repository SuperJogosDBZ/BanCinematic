package br.com.bancinematic.listener;

import br.com.bancinematic.punishment.MuteData;
import br.com.bancinematic.punishment.MuteManager;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

public final class ChatListener implements Listener {
    private final MuteManager muteManager;

    public ChatListener(MuteManager muteManager) {
        this.muteManager = muteManager;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        // Read-only UUID lookup. MuteStorage is safe for this asynchronous read.
        MuteData data = muteManager.get(
                event.getPlayer().getUniqueId()
        );

        if (data == null) return;

        event.setCancelled(true);

        Bukkit.getScheduler().runTask(muteManager.plugin(), () -> {
            if (event.getPlayer().isOnline()) {
                event.getPlayer().sendMessage(muteManager.mutedChatMessage(data));
            }
        });
    }
}
