package br.com.bancinematic.command;

import br.com.bancinematic.BanCinematicPlugin;
import br.com.bancinematic.punishment.MuteManager;
import br.com.bancinematic.punishment.PunishmentResult;
import br.com.bancinematic.punishment.ExecutorIdentity;
import br.com.bancinematic.util.PlayerIdentity;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.List;

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

        String name = args[0];
        java.util.UUID uuid = PlayerIdentity.resolveKnown(plugin, name);
        if (uuid == null) {
            sender.sendMessage(plugin.messages().get("messages.not-muted"));
            return true;
        }

        manager.unmute(uuid, name, ExecutorIdentity.from(sender)).thenAccept(result -> plugin.runSync(() -> {
            if (result == PunishmentResult.NOT_ACTIVE) {
                sender.sendMessage(plugin.messages().get("messages.not-muted"));
            } else if (result != PunishmentResult.SUCCESS) {
                sender.sendMessage(plugin.messages().get("messages.storage-error"));
            } else {
                sender.sendMessage(plugin.messages().get("messages.unmuted").replace("%player%", name));
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
