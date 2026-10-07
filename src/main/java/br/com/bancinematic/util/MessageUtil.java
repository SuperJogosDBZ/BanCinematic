package br.com.bancinematic.util;

import br.com.bancinematic.BanCinematicPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.ChatColor;

import java.util.List;

public final class MessageUtil {
    private final BanCinematicPlugin plugin;
    private final LegacyComponentSerializer serializer = LegacyComponentSerializer.legacyAmpersand();

    public MessageUtil(BanCinematicPlugin plugin) { this.plugin = plugin; }

    public String raw(String path) {
        String value = plugin.getLanguageConfig().getString(path);
        return value == null ? "" : value;
    }

    public String raw(String path, String fallback) {
        String value = raw(path);
        return value.isBlank() ? fallback : value;
    }

    public String get(String path) {
        return ChatColor.translateAlternateColorCodes('&', raw(path));
    }

    public Component component(String text) { return serializer.deserialize(text); }

    public String screen(String path, String reason, String source, String player, String time) {
        String title = raw(path + ".title");
        StringBuilder builder = new StringBuilder(title);
        List<String> lines = plugin.getLanguageConfig().getStringList(path + ".lines");
        for (String line : lines) builder.append("\n").append(line);
        return builder.toString()
                .replace("%reason%", reason == null ? "" : reason)
                .replace("%source%", source == null ? "" : source)
                .replace("%player%", player == null ? "" : player)
                .replace("%time%", time == null ? "" : time);
    }
}
