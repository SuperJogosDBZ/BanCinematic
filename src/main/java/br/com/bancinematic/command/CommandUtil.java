package br.com.bancinematic.command;

import br.com.bancinematic.BanCinematicPlugin;
import br.com.bancinematic.util.InputLimits;
import br.com.bancinematic.util.PlayerIdentity;
import org.bukkit.command.CommandSender;

import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Collectors;

final class CommandUtil {
    private CommandUtil() {
    }

    static String joinReason(String[] args, int start) {
        return String.join(" ", java.util.Arrays.copyOfRange(args, start, args.length));
    }

    static String joinReasonExcept(String[] args, int start, int excludedIndex) {
        return IntStream.range(start, args.length)
                .filter(index -> index != excludedIndex)
                .mapToObj(index -> args[index])
                .filter(part -> !part.isBlank())
                .collect(Collectors.joining(" "));
    }

    static UUID resolveKnownPlayer(BanCinematicPlugin plugin, CommandSender sender, String name) {
        UUID uuid = PlayerIdentity.resolveKnown(plugin, name);
        if (uuid == null) {
            sender.sendMessage(plugin.messages().get("messages.player-never-joined"));
        }
        return uuid;
    }

    static boolean validateReasonLength(BanCinematicPlugin plugin, CommandSender sender, String reason) {
        if (reason.length() <= InputLimits.MAX_REASON_LENGTH) return true;
        sender.sendMessage(plugin.messages().get("messages.reason-too-long")
                .replace("%max%", Integer.toString(InputLimits.MAX_REASON_LENGTH)));
        return false;
    }

    static int findDurationIndex(String[] args) {
        if (br.com.bancinematic.util.TimeUtil.parse(args[1]) != null) return 1;
        int lastIndex = args.length - 1;
        return br.com.bancinematic.util.TimeUtil.parse(args[lastIndex]) != null ? lastIndex : -1;
    }
}
