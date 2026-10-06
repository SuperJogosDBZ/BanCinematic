package br.com.bancinematic.command;

import br.com.bancinematic.BanCinematicPlugin;
import br.com.bancinematic.punishment.MuteManager;
import br.com.bancinematic.punishment.PunishmentResult;
import br.com.bancinematic.util.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;


public final class TempMuteCommand implements CommandExecutor {
    private final BanCinematicPlugin plugin;
    private final MuteManager manager;

    public TempMuteCommand(BanCinematicPlugin plugin, MuteManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        if (args.length < 3) {
            sender.sendMessage(plugin.messages().get("messages.usage-tempmute"));
            return true;
        }

        int durationIndex = CommandUtil.findDurationIndex(args);
        if (durationIndex < 0) {
            sender.sendMessage(plugin.messages().get("messages.invalid-time"));
            return true;
        }
        Long seconds = TimeUtil.parse(args[durationIndex]);

        String name = args[0];

        String reason = CommandUtil.joinReasonExcept(args, 1, durationIndex);
        if (reason.isBlank()) {
            sender.sendMessage(plugin.messages().get("messages.usage-tempmute"));
            return true;
        }

        if (!CommandUtil.validateReasonLength(plugin, sender, reason)) return true;

        java.util.UUID uuid = CommandUtil.resolveKnownPlayer(plugin, sender, name);
        if (uuid == null) return true;
        String sourceName = sender.getName();
        manager.tempMute(uuid, name, reason, sourceName, seconds).thenAccept(result -> plugin.runSync(() -> {
            if (result == PunishmentResult.ALREADY_ACTIVE) {
                sender.sendMessage(plugin.messages().get("messages.already-muted"));
                return;
            }
            if (result != PunishmentResult.SUCCESS) {
                sender.sendMessage(plugin.messages().get("messages.storage-error"));
                return;
            }

            manager.announceMute(name, reason, sourceName, seconds);
            plugin.sendStaffMessage(sender, plugin.messages().get("messages.temp-muted")
                    .replace("%player%", name)
                    .replace("%reason%", reason)
                    .replace("%time%", TimeUtil.format(seconds, plugin.messages()::get)));

            Player online = Bukkit.getPlayer(uuid);
            if (online != null) {
                var mute = manager.get(uuid);
                if (mute != null) online.sendMessage(manager.muteNotice(mute));
            }
        }));

        return true;
    }

}
