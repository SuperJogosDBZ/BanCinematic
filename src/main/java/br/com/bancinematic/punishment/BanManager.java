package br.com.bancinematic.punishment;

import br.com.bancinematic.BanCinematicPlugin;
import br.com.bancinematic.storage.BanStorage;
import br.com.bancinematic.storage.DatabaseExecutor;
import br.com.bancinematic.storage.PunishmentLogger;
import br.com.bancinematic.util.TimeUtil;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.command.CommandSender;

public final class BanManager {
    private final BanCinematicPlugin plugin;
    private final BanStorage storage;
    private final PunishmentLogger logger;
    private final DatabaseExecutor executor;

    public BanManager(BanCinematicPlugin plugin, BanStorage storage, PunishmentLogger logger,
                      DatabaseExecutor executor) {
        this.plugin = plugin;
        this.storage = storage;
        this.logger = logger;
        this.executor = executor;
    }

    public boolean isBanned(UUID uuid) { return storage.isBanned(uuid); }
    public BanData get(UUID uuid) { return storage.get(uuid); }

    public CompletableFuture<PunishmentResult> ban(UUID uuid, String name, String reason, String source) {
        return run(() -> save(new BanData(uuid, name, reason, source, null),
                PunishmentType.BAN, null));
    }

    public CompletableFuture<PunishmentResult> tempBan(UUID uuid, String name, String reason,
                                                       String source, long seconds) {
        return run(() -> save(new BanData(uuid, name, reason, source, Instant.now().plusSeconds(seconds)),
                PunishmentType.TEMPBAN, TimeUtil.format(seconds, plugin.messages()::get)));
    }

    public CompletableFuture<PunishmentResult> unban(UUID uuid, String name, String source) {
        return run(() -> {
            BanData previous = storage.get(uuid);
            if (previous == null) return PunishmentResult.NOT_ACTIVE;
            return storage.removeAndLog(uuid, name, "Removido", source,
                    PunishmentType.UNBAN, logger)
                    ? PunishmentResult.SUCCESS
                    : PunishmentResult.STORAGE_ERROR;
        });
    }

    private PunishmentResult save(BanData data, PunishmentType type, String duration) {
        UUID uuid = data.uniqueId();
        if (storage.get(uuid) != null) return PunishmentResult.ALREADY_ACTIVE;
        return storage.putAndLog(data, type, duration, logger)
                ? PunishmentResult.SUCCESS
                : PunishmentResult.STORAGE_ERROR;
    }

    private CompletableFuture<PunishmentResult> run(java.util.function.Supplier<PunishmentResult> task) {
        return executor.submit(task).exceptionally(error -> {
            plugin.getLogger().severe("Falha inesperada ao gravar o banimento: " + error);
            return PunishmentResult.STORAGE_ERROR;
        });
    }

    public void announceBan(String name, String reason, String source, Long durationSeconds) {
        if (durationSeconds == null) {
            plugin.broadcastPunishment("messages.broadcast.ban", name, reason, source, "");
        } else {
            plugin.broadcastPunishment(
                    "messages.broadcast.tempban",
                    name,
                    reason,
                    source,
                    TimeUtil.format(durationSeconds, plugin.messages()::get)
            );
        }
    }

    public void sendBanConfirmation(
            CommandSender sender,
            String name,
            String reason,
            Long durationSeconds
    ) {
        String path = durationSeconds == null
                ? "messages.confirmations.ban"
                : "messages.confirmations.tempban";
        String message = plugin.messages().raw(path);
        if (message == null || message.isBlank()) return;

        plugin.sendStaffMessage(sender, plugin.messages().component(message
                .replace("%player%", name)
                .replace("%reason%", reason)
                .replace("%source%", sender.getName())
                .replace("%time%", durationSeconds == null
                        ? ""
                        : TimeUtil.format(durationSeconds, plugin.messages()::get))));
    }
}
