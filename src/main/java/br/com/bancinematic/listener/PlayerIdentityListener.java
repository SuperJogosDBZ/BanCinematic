package br.com.bancinematic.listener;

import br.com.bancinematic.BanCinematicPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public final class PlayerIdentityListener implements Listener {
    private final BanCinematicPlugin plugin;

    public PlayerIdentityListener(BanCinematicPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        var player = event.getPlayer();
        plugin.identities().recordAsync(plugin.databaseExecutor(), player.getUniqueId(), player.getName());
    }
}
