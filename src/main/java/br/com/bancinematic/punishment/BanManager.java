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

/**
 * Ban rules. Changes ({@link #ban}, {@link #tempBan}, {@link #unban}) run on the database
 * thread and complete a future; chain {@code BanCinematicPlugin#runSync} before touching
 * the Bukkit API with the result. Reads are memory-only and safe on any thread.
 */
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

    public CompletableFuture<PunishmentResult> ban(UUID uuid, String name, String reason, ExecutorIdentity executor) {
        return run(() -> save(new BanData(uuid, name, reason, executor.name(), null),
                PunishmentType.BAN, executor, null));
    }

    public CompletableFuture<PunishmentResult> tempBan(UUID uuid, String name, String reason,
                                                       ExecutorIdentity executor, long seconds) {
        return run(() -> save(new BanData(uuid, name, reason, executor.name(), Instant.now().plusSeconds(seconds)),
                PunishmentType.TEMPBAN, executor, TimeUtil.format(seconds, plugin.messages()::get)));
    }

    public CompletableFuture<PunishmentResult> unban(UUID uuid, String name, ExecutorIdentity executor) {
        return run(() -> {
            BanData previous = storage.get(uuid);
            if (previous == null) return PunishmentResult.NOT_ACTIVE;
            if (!storage.remove(uuid)) return PunishmentResult.STORAGE_ERROR;
            if (!logger.log(PunishmentType.UNBAN, uuid, name, "Removido", executor.name(), executor, null)) {
                storage.put(previous);
                return PunishmentResult.STORAGE_ERROR;
            }
            return PunishmentResult.SUCCESS;
        });
    }

    /** Runs on the database thread, so the existence check and the write cannot interleave. */
    private PunishmentResult save(BanData data, PunishmentType type, ExecutorIdentity executor, String duration) {
        UUID uuid = data.uniqueId();
        if (storage.get(uuid) != null) return PunishmentResult.ALREADY_ACTIVE;
        if (!storage.put(data)) return PunishmentResult.STORAGE_ERROR;
        if (!logger.log(type, uuid, data.name(), data.reason(), data.source(), executor, duration)) {
            storage.remove(uuid);
            return PunishmentResult.STORAGE_ERROR;
        }
        return PunishmentResult.SUCCESS;
    }

    private CompletableFuture<PunishmentResult> run(java.util.function.Supplier<PunishmentResult> task) {
        return executor.submit(task).exceptionally(error -> {
            plugin.getLogger().severe("Falha inesperada ao gravar o banimento: " + error);
            return PunishmentResult.STORAGE_ERROR;
        });
    }

    /** Sends the public ban announcement after the target has been kicked. */
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

    /** Confirms a successfully saved ban to the moderator who issued it. */
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
