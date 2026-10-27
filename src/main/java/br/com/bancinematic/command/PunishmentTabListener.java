package br.com.bancinematic.command;

import br.com.bancinematic.BanCinematicPlugin;
import br.com.bancinematic.punishment.BanManager;
import br.com.bancinematic.punishment.MuteManager;
import com.destroystokyo.paper.event.server.AsyncTabCompleteEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

public final class PunishmentTabListener implements Listener {
    private final BanCinematicPlugin plugin;
    private final BanManager bans;
    private final MuteManager mutes;
    private final Map<String, String> labels = new HashMap<>();

    public PunishmentTabListener(BanCinematicPlugin plugin, BanManager bans, MuteManager mutes) {
        this.plugin = plugin;
        this.bans = bans;
        this.mutes = mutes;
        for (String name : List.of("unban", "unmute")) {
            var command = plugin.getCommand(name);
            if (command == null) continue;
            labels.put(name, name);
            labels.put("bancinematic:" + name, name);
            for (String alias : command.getAliases()) labels.put(alias.toLowerCase(Locale.ROOT), name);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onComplete(AsyncTabCompleteEvent event) {
        if (!event.isCommand() || event.isHandled()) return;
        String buffer = event.getBuffer();
        if (buffer.startsWith("/")) buffer = buffer.substring(1);
        String[] parts = buffer.split(" ", -1);
        String name = labels.get(parts[0].toLowerCase(Locale.ROOT));
        if (name == null) return;
        event.setHandled(true);
        event.setCompletions(List.of());
        if (parts.length != 2 || !event.isAsynchronous()
                || !event.getSender().hasPermission("bancinematic." + name)
                || parts[1].length() > 64) return;
        var result = plugin.databaseExecutor().submit(() -> name.equals("unban")
                ? bans.suggestNames(parts[1]) : mutes.suggestNames(parts[1]));
        try {
            event.setCompletions(result.get(3, TimeUnit.SECONDS));
        } catch (Exception error) {
            result.cancel(false);
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            // A falta de sugestões não deve gerar mensagens nem expor dados de armazenamento.
        }
    }
}
