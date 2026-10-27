package br.com.bancinematic.listener;

import br.com.bancinematic.BanCinematicPlugin;
import br.com.bancinematic.punishment.BanManager;
import br.com.bancinematic.punishment.MuteManager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.entity.Player;

public final class MuteJoinListener implements Listener {
    private final BanCinematicPlugin plugin;
    private final BanManager bans;
    private final MuteManager mutes;

    public MuteJoinListener(BanCinematicPlugin plugin, BanManager bans, MuteManager mutes) {
        this.plugin = plugin;
        this.bans = bans;
        this.mutes = mutes;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        var player = event.getPlayer();
        var uuid = player.getUniqueId();
        // Recheca bans aplicados entre o pré-login e a entrada no mundo.
        plugin.databaseExecutor().submit(() -> bans.get(uuid)).whenComplete((ban, error) -> plugin.runSync(() -> {
            if (!player.isOnline()) return;
            if (error != null) {
                fail(player, error);
            } else if (ban != null) {
                player.kick(BanLoginListener.screen(plugin, ban, player.getName()));
            } else {
                mutes.loadOnline(uuid).whenComplete((mute, muteError) -> plugin.runSync(() -> {
                    if (!player.isOnline()) return;
                    if (muteError != null) fail(player, muteError);
                    else if (mute != null) player.sendMessage(mutes.muteReminder(mute));
                }));
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
