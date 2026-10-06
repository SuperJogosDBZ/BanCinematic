package br.com.bancinematic.punishment;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/** Immutable identity captured when an administrative command starts. */
public record ExecutorIdentity(UUID uniqueId, String name) {
    public static final String CONSOLE_NAME = "CONSOLE";

    public ExecutorIdentity {
        Objects.requireNonNull(name, "O nome do executor é obrigatório.");
        if (name.isBlank()) throw new IllegalArgumentException("O nome do executor não pode ser vazio.");
    }

    /** Captures player identity immediately; all non-player senders are recorded as the console. */
    public static ExecutorIdentity from(CommandSender sender) {
        Objects.requireNonNull(sender, "O executor é obrigatório.");
        if (sender instanceof Player player) return new ExecutorIdentity(player.getUniqueId(), player.getName());
        return console();
    }

    public static ExecutorIdentity console() {
        return new ExecutorIdentity(null, CONSOLE_NAME);
    }
}
