package br.com.bancinematic.listener;

import br.com.bancinematic.BanCinematicPlugin;
import br.com.bancinematic.punishment.MuteManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.entity.Player;

public final class MuteJoinListener implements Listener {
    private final BanCinematicPlugin plugin;
    private final MuteManager mutes;

    public MuteJoinListener(BanCinematicPlugin plugin, MuteManager mutes) {
        this.plugin = plugin;
        this.mutes = mutes;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        var player = event.getPlayer();
        var uuid = player.getUniqueId();
        mutes.loadForJoin(uuid).whenComplete((punishments, error) -> plugin.runSync(() -> {
            if (!player.isOnline()) return;
            if (error != null) {
                fail(player, error);
            } else if (punishments.ban() != null) {
                player.kick(BanLoginListener.screen(plugin, punishments.ban(), player.getName()));
            } else {
                var mute = mutes.getOnline(uuid);
                if (mute != null) player.sendMessage(mutes.muteReminder(mute));
            }
        }));
    }

    private void fail(Player player, Throwable error) {
        plugin.getLogger().warning("Falha ao preparar punições do jogador " + player.getUniqueId() + ": " + error);
        player.kick(plugin.messages().component(plugin.messages().get("messages.login-storage-error")));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) { mutes.forgetOnline(event.getPlayer().getUniqueId()); }
}
