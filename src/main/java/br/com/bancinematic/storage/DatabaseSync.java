package br.com.bancinematic.storage;

import br.com.bancinematic.BanCinematicPlugin;
import br.com.bancinematic.cinematic.BanCinematic;
import br.com.bancinematic.util.TimeUtil;
import org.bukkit.Bukkit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class DatabaseSync implements Runnable {
    private final BanCinematicPlugin plugin;
    private final PunishmentStorage bans;
    private final PunishmentStorage mutes;
    private final BanCinematic cinematic;
    private final br.com.bancinematic.punishment.MuteManager muteManager;
    private final AtomicBoolean pending = new AtomicBoolean();

    public DatabaseSync(BanCinematicPlugin plugin, PunishmentStorage bans, PunishmentStorage mutes,
                        br.com.bancinematic.punishment.MuteManager muteManager, BanCinematic cinematic) {
        this.plugin = plugin;
        this.bans = bans;
        this.mutes = mutes;
        this.cinematic = cinematic;
        this.muteManager = muteManager;
    }

    public void start() {
        long seconds = plugin.getConfig().getLong("database.sync-interval-seconds", 0);
        if (seconds < 0 || seconds > Long.MAX_VALUE / 20) {
            plugin.getLogger().warning("database.sync-interval-seconds inválido; sincronização desativada.");
            return;
        }
        if (seconds == 0) return;
        plugin.getServer().getScheduler().runTaskTimer(plugin, this, seconds * 20, seconds * 20);
    }

    @Override
    public void run() {
        if (!pending.compareAndSet(false, true)) return;
        var players = java.util.List.copyOf(Bukkit.getOnlinePlayers());
        var ids = players.stream().map(org.bukkit.entity.Player::getUniqueId).toList();
        plugin.databaseExecutor().execute(() -> {
            long banRevision = bans.revision(), muteRevision = mutes.revision();
            var active = bans.getActivePunishments(ids);
            plugin.runSync(() -> {
                for (var player : players) {
                    if (!player.isOnline()) continue;
                    var uuid = player.getUniqueId();
                    var punishments = active.get(uuid);
                    if (mutes.revision() == muteRevision) muteManager.updateOnlineFromSync(uuid,
                            punishments == null ? null : punishments.mute(), muteRevision);
                    var ban = punishments == null ? null : punishments.ban();
                    if (ban == null || ban.expired() || bans.revision() != banRevision || cinematic.isRunning(uuid)) continue;
                    String text = plugin.messages().screen(ban.permanent() ? "messages.ban-screen" : "messages.tempban-screen",
                            ban.reason(), ban.source(), player.getName(), ban.permanent() ? ""
                                    : TimeUtil.formatRemaining(ban.expires(), plugin.messages()::get));
                    player.kick(plugin.messages().component(text));
                }
            });
        }).whenComplete((ignored, error) -> {
            pending.set(false);
            if (error != null) plugin.getLogger().warning("Falha inesperada na sincronização: " + error);
        });
    }

}
