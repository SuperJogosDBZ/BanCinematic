package br.com.bancinematic.command;

import br.com.bancinematic.BanCinematicPlugin;
import br.com.bancinematic.punishment.MuteManager;
import br.com.bancinematic.punishment.PunishmentResult;
import br.com.bancinematic.util.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;


public final class MuteCommand implements CommandExecutor {
    private final BanCinematicPlugin plugin;
    private final MuteManager manager;

    public MuteCommand(BanCinematicPlugin plugin, MuteManager manager) {
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
        if (args.length < 2) {
            sender.sendMessage(plugin.messages().get("messages.usage-mute"));
            return true;
        }

        if (TimeUtil.parse(args[1]) != null) {
            sender.sendMessage(plugin.messages().get("messages.permanent-mute-time-hint"));
            return true;
        }

        String name = args[0];
        String reason = CommandUtil.joinReason(args, 1);
        if (!CommandUtil.validateReasonLength(plugin, sender, reason)) return true;

        java.util.UUID uuid = CommandUtil.resolveKnownPlayer(plugin, sender, name);
        if (uuid == null) return true;
        String sourceName = sender.getName();
        manager.mute(uuid, name, reason, sourceName).thenAccept(result -> plugin.runSync(() -> {
            if (result == PunishmentResult.ALREADY_ACTIVE) {
                sender.sendMessage(plugin.messages().get("messages.already-muted"));
                return;
            }
            if (result != PunishmentResult.SUCCESS) {
                sender.sendMessage(plugin.messages().get("messages.storage-error"));
                return;
            }

            manager.announceMute(name, reason, sourceName, null);
            plugin.sendStaffMessage(sender, plugin.messages().get("messages.muted")
                    .replace("%player%", name)
                    .replace("%reason%", reason));

            Player online = Bukkit.getPlayer(uuid);
            if (online != null) {
                var mute = manager.get(uuid);
                if (mute != null) online.sendMessage(manager.muteNotice(mute));
            }
        }));

        return true;
    }
}
