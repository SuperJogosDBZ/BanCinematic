package br.com.bancinematic.util;

import java.util.Objects;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class ExecutorName {
    public static final String CONSOLE = "CONSOLE";

    private ExecutorName() {}

    public static String from(CommandSender sender) {
        Objects.requireNonNull(sender, "O executor é obrigatório.");
        return sender instanceof Player ? sender.getName() : CONSOLE;
    }
}
