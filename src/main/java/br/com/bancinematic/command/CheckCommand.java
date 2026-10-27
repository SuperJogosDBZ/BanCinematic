package br.com.bancinematic.command;

import br.com.bancinematic.BanCinematicPlugin;
import br.com.bancinematic.punishment.BanManager;
import br.com.bancinematic.punishment.MuteManager;
import br.com.bancinematic.util.TimeUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public final class CheckCommand implements CommandExecutor {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    private final BanCinematicPlugin plugin;
    private final BanManager bans;
    private final MuteManager mutes;

    public CheckCommand(BanCinematicPlugin plugin, BanManager bans, MuteManager mutes) {
        this.plugin = plugin;
        this.bans = bans;
        this.mutes = mutes;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 1) {
            send(sender, "usage");
            return true;
        }
        CommandUtil.query(plugin, sender, () -> {
            var identity = plugin.identities().get(args[0]);
            return identity == null ? null : new Result(identity, bans.get(identity.uuid()), mutes.get(identity.uuid()));
        }, result -> {
            if (result == null) {
                sender.sendMessage(plugin.messages().get("messages.player-never-joined"));
                return;
            }
            var identity = result.identity();
            send(sender, "title", "%player%", identity.name());
            send(sender, "uuid", "%uuid%", identity.uuid().toString());
            send(sender, "last-seen", "%date%", DATE.format(identity.lastSeen()));
            var ban = result.ban();
            if (ban == null) send(sender, "no-ban");
            else punishment(sender, "ban", ban.reason(), ban.source(), ban.expires());
            var mute = result.mute();
            if (mute == null) send(sender, "no-mute");
            else punishment(sender, "mute", mute.reason(), mute.source(), mute.expires());
        });
        return true;
    }

    private record Result(br.com.bancinematic.storage.IdentityStorage.Identity identity,
                          br.com.bancinematic.punishment.BanData ban, br.com.bancinematic.punishment.MuteData mute) {}

    private void punishment(CommandSender sender, String key, String reason, String source, Instant expires) {
        send(sender, key, "%reason%", reason, "%source%", source, "%time%",
                TimeUtil.formatRemaining(expires, plugin.messages()::get));
    }

    private void send(CommandSender sender, String key, String... replacements) {
        String text = plugin.messages().get("messages.check." + key);
        for (int i = 0; i < replacements.length; i += 2) {
            text = text.replace(replacements[i], replacements[i + 1]);
        }
        sender.sendMessage(text);
    }
}
