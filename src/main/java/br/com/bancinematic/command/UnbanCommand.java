package br.com.bancinematic.command;

import br.com.bancinematic.BanCinematicPlugin;
import br.com.bancinematic.cinematic.BanCinematic;
import br.com.bancinematic.punishment.BanManager;
import br.com.bancinematic.punishment.PunishmentResult;
import br.com.bancinematic.util.ExecutorName;
import org.bukkit.command.*;

import java.util.List;
import java.util.UUID;

public final class UnbanCommand implements CommandExecutor, TabCompleter {
    private final BanCinematicPlugin plugin;
    private final BanManager manager;
    private final BanCinematic cinematic;

    public UnbanCommand(BanCinematicPlugin plugin, BanManager manager, BanCinematic cinematic) {
        this.plugin = plugin;
        this.manager = manager;
        this.cinematic = cinematic;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 1) {
            sender.sendMessage(plugin.messages().get("messages.usage-unban"));
            return true;
        }

        String input = args[0];
        CommandUtil.query(plugin, sender, () -> CommandUtil.target(plugin, input, uuid -> {
            var ban = manager.get(uuid);
            return ban == null ? null : ban.name();
        }), target -> {
            if (target == null) {
                sender.sendMessage(plugin.messages().get("messages.already-unbanned"));
                return;
            }
            UUID uuid = target.uuid();
            String name = target.name();
            if (cinematic.isRunning(uuid)) {
                sender.sendMessage(plugin.messages().get("messages.unban-blocked-during-ban")
                        .replace("%player%", name));
                return;
            }
    
            manager.unban(uuid, name, ExecutorName.from(sender)).thenAccept(result -> plugin.runSync(() -> {
                if (result == PunishmentResult.NOT_ACTIVE) {
                    sender.sendMessage(plugin.messages().get("messages.already-unbanned"));
                } else if (result != PunishmentResult.SUCCESS) {
                    sender.sendMessage(plugin.messages().get("messages.storage-error"));
                } else {
                    sender.sendMessage(plugin.messages().get("messages.unbanned").replace("%player%", name));
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
