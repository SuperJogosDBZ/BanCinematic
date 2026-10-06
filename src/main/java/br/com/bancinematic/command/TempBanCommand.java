package br.com.bancinematic.command;

import br.com.bancinematic.BanCinematicPlugin;
import br.com.bancinematic.cinematic.BanCinematic;
import br.com.bancinematic.punishment.BanManager;
import br.com.bancinematic.punishment.PunishmentResult;
import br.com.bancinematic.util.TimeUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.List;

public final class TempBanCommand implements CommandExecutor, TabCompleter {
    private final BanCinematicPlugin plugin;
    private final BanManager manager;
    private final BanCinematic cinematic;

    public TempBanCommand(
            BanCinematicPlugin plugin,
            BanManager manager,
            BanCinematic cinematic
    ) {
        this.plugin = plugin;
        this.manager = manager;
        this.cinematic = cinematic;
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        if (args.length < 3) {
            sender.sendMessage(plugin.messages().get("messages.usage-tempban"));
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
            sender.sendMessage(plugin.messages().get("messages.usage-tempban"));
            return true;
        }

        if (!CommandUtil.validateReasonLength(plugin, sender, reason)) return true;

        java.util.UUID uuid = CommandUtil.resolveKnownPlayer(plugin, sender, name);
        if (uuid == null) return true;
        Player target = Bukkit.getPlayer(uuid);

        if (target != null && cinematic.isRunning(target.getUniqueId())) {
            sender.sendMessage(plugin.messages().get("messages.cinematic-already-running")
                    .replace("%player%", target.getName()));
            return true;
        }

        if (target != null) {
            cinematic.start(sender, target, reason, seconds);
            return true;
        }

        String sourceName = sender.getName();
        manager.tempBan(uuid, name, reason, sourceName, seconds).thenAccept(result -> plugin.runSync(() -> {
            if (result == PunishmentResult.ALREADY_ACTIVE) {
                sender.sendMessage(plugin.messages().get("messages.already-banned"));
            } else if (result != PunishmentResult.SUCCESS) {
                sender.sendMessage(plugin.messages().get("messages.storage-error"));
            } else {
                manager.announceBan(name, reason, sourceName, seconds);
                manager.sendBanConfirmation(sender, name, reason, seconds);
            }
        }));

        return true;
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender,
            Command command,
            String alias,
            String[] args
    ) {
        if (args.length != 1) return List.of();

        return Bukkit.getOnlinePlayers()
                .stream()
                .map(Player::getName)
                .filter(name -> name.toLowerCase()
                        .startsWith(args[0].toLowerCase()))
                .sorted()
                .toList();
    }

}
