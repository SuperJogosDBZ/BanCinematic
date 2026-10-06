package br.com.bancinematic.command;

import br.com.bancinematic.BanCinematicPlugin;
import br.com.bancinematic.cinematic.BanCinematic;
import br.com.bancinematic.punishment.BanManager;
import br.com.bancinematic.punishment.PunishmentResult;
import br.com.bancinematic.util.PlayerIdentity;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.List;

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

        String name = args[0];
        java.util.UUID uuid = PlayerIdentity.resolveKnown(plugin, name);
        if (uuid == null) {
            sender.sendMessage(plugin.messages().get("messages.already-unbanned"));
            return true;
        }

        if (cinematic.isRunning(uuid)) {
            sender.sendMessage(plugin.messages().get("messages.unban-blocked-during-ban")
                    .replace("%player%", name));
            return true;
        }

        manager.unban(uuid, name, sender.getName()).thenAccept(result -> plugin.runSync(() -> {
            if (result == PunishmentResult.NOT_ACTIVE) {
                sender.sendMessage(plugin.messages().get("messages.already-unbanned"));
            } else if (result != PunishmentResult.SUCCESS) {
                sender.sendMessage(plugin.messages().get("messages.storage-error"));
            } else {
                sender.sendMessage(plugin.messages().get("messages.unbanned").replace("%player%", name));
            }
        }));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) return List.of();
        return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(name -> name.toLowerCase().startsWith(args[0].toLowerCase()))
                .sorted().toList();
    }
}
