package br.com.bancinematic.command;

import br.com.bancinematic.BanCinematicPlugin;
import br.com.bancinematic.punishment.MuteManager;
import br.com.bancinematic.punishment.PunishmentResult;
import br.com.bancinematic.util.ExecutorName;
import org.bukkit.command.*;

import java.util.List;
import java.util.UUID;

public final class UnmuteCommand implements CommandExecutor, TabCompleter {
    private final BanCinematicPlugin plugin;
    private final MuteManager manager;

    public UnmuteCommand(BanCinematicPlugin plugin, MuteManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 1) {
            sender.sendMessage(plugin.messages().get("messages.usage-unmute"));
            return true;
        }

        String input = args[0];
        CommandUtil.query(plugin, sender, () -> CommandUtil.target(plugin, input, uuid -> {
            var mute = manager.get(uuid);
            return mute == null ? null : mute.name();
        }), target -> {
            if (target == null) {
                sender.sendMessage(plugin.messages().get("messages.not-muted"));
                return;
            }
            UUID uuid = target.uuid();
            String name = target.name();
            manager.unmute(uuid, name, ExecutorName.from(sender)).thenAccept(result -> plugin.runSync(() -> {
                if (result == PunishmentResult.NOT_ACTIVE) {
                    sender.sendMessage(plugin.messages().get("messages.not-muted"));
                } else if (result != PunishmentResult.SUCCESS) {
                    sender.sendMessage(plugin.messages().get("messages.storage-error"));
                } else {
                    sender.sendMessage(plugin.messages().get("messages.unmuted").replace("%player%", name));
                }
            }));
        });
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }
}
