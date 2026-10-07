package br.com.bancinematic.command;

import br.com.bancinematic.BanCinematicPlugin;
import br.com.bancinematic.storage.PunishmentLogger;
import br.com.bancinematic.util.PlayerIdentity;
import br.com.bancinematic.util.InputLimits;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

public final class HistoryCommand implements CommandExecutor {
    private static final int ENTRIES_PER_PAGE = 8;
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
                    .withZone(ZoneId.systemDefault());

    private final BanCinematicPlugin plugin;
    private final PunishmentLogger logger;

    public HistoryCommand(BanCinematicPlugin plugin, PunishmentLogger logger) {
        this.plugin = plugin;
        this.logger = logger;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length < 1 || args.length > 2) {
            sender.sendMessage(message("usage"));
            return true;
        }

        int page = 1;
        if (args.length == 2) {
            try {
                page = Integer.parseInt(args[1]);
            } catch (NumberFormatException ignored) {
                sender.sendMessage(message("invalid-page"));
                return true;
            }
            if (page < 1 || page > InputLimits.MAX_HISTORY_PAGE) {
                sender.sendMessage(message("invalid-page"));
                return true;
            }
        }

        String player = args[0];
        java.util.UUID uuid = PlayerIdentity.resolveKnown(plugin, player);
        if (uuid == null) {
            sender.sendMessage(message("no-records", "player", player));
            return true;
        }
        long offset = (long) (page - 1) * ENTRIES_PER_PAGE;
        final int requestedPage = page;

        plugin.databaseExecutor()
                .submit(() -> logger.getHistoryPage(uuid, offset, ENTRIES_PER_PAGE))
                .exceptionally(error -> {
                    plugin.getLogger().severe("Falha inesperada ao consultar o histórico: " + error);
                    return new PunishmentLogger.HistoryPage(0, List.of());
                })
                .thenAccept(history -> plugin.runSync(
                        () -> showPage(sender, player, requestedPage, offset, history)));
        return true;
    }

    private void showPage(CommandSender sender, String player, int page, long offset,
                          PunishmentLogger.HistoryPage history) {
        if (history.totalEntries() == 0) {
            sender.sendMessage(message("no-records", "player", player));
            return;
        }

        long totalPages = (history.totalEntries() - 1) / ENTRIES_PER_PAGE + 1;
        if (page > totalPages) {
            sender.sendMessage(message("page-out-of-range", "total", Long.toString(totalPages)));
            return;
        }
        List<PunishmentLogger.HistoryEntry> entries = history.entries();
        if (entries.isEmpty()) {
            sender.sendMessage(message("no-records", "player", player));
            return;
        }

        sender.sendMessage(message("divider"));
        sender.sendMessage(message("title", "player", entries.get(0).player()));
        sender.sendMessage(message("page-info",
                "page", Integer.toString(page),
                "total-pages", Long.toString(totalPages),
                "total-entries", Long.toString(history.totalEntries())));
        sender.sendMessage("");

        for (int i = 0; i < entries.size(); i++) {
            PunishmentLogger.HistoryEntry entry = entries.get(i);
            boolean removal = isRemoval(entry.type());
            boolean durationVisible = !removal && !isKick(entry.type());

            sender.sendMessage(message("entry-header",
                    "number", Long.toString(offset + i + 1),
                    "type", formatType(entry.type()),
                    "date", formatDate(entry.date())));
            if (!removal) {
                String duration = entry.duration() == null || entry.duration().isBlank()
                        ? plugin.messages().get("messages.history.permanent")
                        : entry.duration();
                sender.sendMessage(message("reason", "reason", entry.reason()));
                sender.sendMessage(message(durationVisible ? "staff" : "staff-last", "staff", entry.source()));
                if (durationVisible) sender.sendMessage(message("duration", "duration", duration));
            } else {
                sender.sendMessage(message("staff-last", "staff", entry.source()));
            }
            if (i < entries.size() - 1) sender.sendMessage("");
        }

        sender.sendMessage("");
        sender.sendMessage(message("navigation", "player", player));
        if (page < totalPages) {
            sender.sendMessage(message("next-page", "page", Integer.toString(page + 1)));
        }
        sender.sendMessage(message("divider"));
    }

    private String formatType(String type) {
        String key = switch (type.toUpperCase(Locale.ROOT)) {
            case "BAN" -> "ban";
            case "TEMPBAN" -> "tempban";
            case "UNBAN" -> "unban";
            case "MUTE" -> "mute";
            case "TEMPMUTE" -> "tempmute";
            case "UNMUTE" -> "unmute";
            case "KICK" -> "kick";
            default -> null;
        };
        if (key == null) return type;

        String translated = plugin.getLanguageConfig().getString("messages.history.types." + key);
        return translated == null || translated.isBlank() ? type : translated;
    }

    private static boolean isRemoval(String type) {
        return "UNBAN".equalsIgnoreCase(type) || "UNMUTE".equalsIgnoreCase(type);
    }

    private static boolean isKick(String type) {
        return "KICK".equalsIgnoreCase(type);
    }

    private String message(String key, String... replacements) {
        String result = plugin.messages().raw("messages.history." + key);
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            result = result.replace("%" + replacements[i] + "%", replacements[i + 1]);
        }
        return ChatColor.translateAlternateColorCodes('&', result);
    }

    private String formatDate(String value) {
        try {
            return DATE_FORMAT.format(Instant.parse(value));
        } catch (DateTimeParseException ignored) {
            return value;
        }
    }

}
