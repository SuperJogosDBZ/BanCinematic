package br.com.bancinematic.command;

import br.com.bancinematic.BanCinematicPlugin;
import br.com.bancinematic.storage.IdentityStorage;
import br.com.bancinematic.util.InputLimits;
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

    static <T> void query(BanCinematicPlugin plugin, CommandSender sender,
                          java.util.function.Supplier<T> query, java.util.function.Consumer<T> action) {
        plugin.databaseExecutor().submit(query).whenComplete((result, error) -> plugin.runSync(() -> {
            if (error != null) {
                plugin.getLogger().warning("Falha ao consultar dados do comando: " + error);
                sender.sendMessage(plugin.messages().get("messages.storage-error"));
            } else action.accept(result);
        }));
    }

    static void resolveKnownPlayer(BanCinematicPlugin plugin, CommandSender sender, String name,
                                   java.util.function.Consumer<IdentityStorage.Identity> action) {
        query(plugin, sender, () -> plugin.identities().get(name), identity -> {
            if (identity == null) sender.sendMessage(plugin.messages().get("messages.player-never-joined"));
            else action.accept(identity);
        });
    }

    static Target target(BanCinematicPlugin plugin, String input,
                         java.util.function.Function<UUID, String> storedName) {
        UUID explicit = parseUuid(input);
        if (explicit == null) {
            var identity = plugin.identities().get(input);
            return identity == null ? null : new Target(identity.uuid(), identity.name());
        }
        String name = storedName.apply(explicit);
        return new Target(explicit, name == null || name.isBlank() ? input : name);
    }

    record Target(UUID uuid, String name) {}

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
    static UUID parseUuid(String input) {
        String compact = input.replace("-", "");
        if (!input.matches("[0-9a-fA-F]{32}")
                && !input.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            return null;
        }
        return UUID.fromString(compact.substring(0, 8) + "-" + compact.substring(8, 12) + "-"
                + compact.substring(12, 16) + "-" + compact.substring(16, 20) + "-" + compact.substring(20));
    }
}
