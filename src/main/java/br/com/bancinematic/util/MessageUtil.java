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

    /**
     * Texto bruto (sem converter cores). Se a chave não existir no arquivo do servidor, usa o
     * padrão embutido no JAR; assim, mensagens novas funcionam em quem atualiza o plugin sem
     * apagar o language_*.yml. Nunca devolve null.
     *
     * <p>Atenção: {@code getString(path, padrao)} ignora os padrões do JAR. Por isso a leitura
     * é feita com {@code getString(path)}.
     */
    public String raw(String path) {
        String value = plugin.getLanguageConfig().getString(path);
        return value == null ? "" : value;
    }

    /** Como {@link #raw(String)}, mas usa {@code fallback} se o texto estiver ausente ou vazio. */
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
