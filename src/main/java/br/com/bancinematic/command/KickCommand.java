package br.com.bancinematic.command;

import br.com.bancinematic.BanCinematicPlugin;
import br.com.bancinematic.punishment.PunishmentType;
import br.com.bancinematic.storage.PunishmentLogger;
import br.com.bancinematic.util.ExecutorName;
import br.com.bancinematic.util.InputLimits;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.UUID;

public final class KickCommand implements CommandExecutor {
    private final BanCinematicPlugin plugin;
    private final PunishmentLogger logger;

    public KickCommand(
            BanCinematicPlugin plugin,
            PunishmentLogger logger
    ) {
        this.plugin = plugin;
        this.logger = logger;
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        if (args.length < 2) {
            sender.sendMessage(plugin.messages().get("messages.usage-kick"));
            return true;
        }

        Player target = Bukkit.getPlayerExact(args[0]);

        if (target == null) {
            sender.sendMessage(plugin.messages().get("messages.player-not-found"));
            return true;
        }

        String reason = String.join(
                " ",
                Arrays.copyOfRange(args, 1, args.length)
        );

        if (reason.length() > InputLimits.MAX_REASON_LENGTH) {
            sender.sendMessage(plugin.messages().get("messages.reason-too-long")
                    .replace("%max%", Integer.toString(InputLimits.MAX_REASON_LENGTH)));
            return true;
        }

        UUID uuid = target.getUniqueId();
        String targetName = target.getName();
        String sourceName = ExecutorName.from(sender);

        plugin.databaseExecutor()
                .submit(() -> logger.log(PunishmentType.KICK, uuid, targetName, reason, sourceName, null))
                .exceptionally(error -> {
                    plugin.getLogger().severe("Falha inesperada ao gravar o kick: " + error);
                    return false;
                })
                .thenAccept(saved -> plugin.runSync(() -> {
                    if (!saved) {
                        sender.sendMessage(plugin.messages().get("messages.storage-error"));
                        return;
                    }
                    Player currentTarget = Bukkit.getPlayer(uuid);
                    if (currentTarget == null) {
                        sender.sendMessage(plugin.messages().get("messages.player-not-found"));
                        return;
                    }

                    String currentTargetName = currentTarget.getName();

                    String finalMessage = plugin.messages().screen(
                            "messages.kick-screen",
                            reason,
                            sourceName,
                            currentTargetName,
                            ""
                    );

                    plugin.broadcastPunishment("messages.broadcast.kick", currentTargetName, reason, sourceName, "");
                    currentTarget.kick(plugin.messages().component(finalMessage));
                    String confirmation = plugin.messages().get("messages.confirmations.kick")
                            .replace("%player%", currentTargetName)
                            .replace("%reason%", reason);
                    sender.sendMessage(plugin.messages().component(confirmation));
                }));

        return true;
    }
}
