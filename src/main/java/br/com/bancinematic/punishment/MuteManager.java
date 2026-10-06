package br.com.bancinematic.punishment;

import br.com.bancinematic.BanCinematicPlugin;
import br.com.bancinematic.storage.DatabaseExecutor;
import br.com.bancinematic.storage.MuteStorage;
import br.com.bancinematic.storage.PunishmentLogger;
import br.com.bancinematic.util.TimeUtil;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Mute rules. Changes run on the database thread and complete a future; chain
 * {@code BanCinematicPlugin#runSync} before touching the Bukkit API with the result.
 * Reads are memory-only and safe on any thread.
 */
public final class MuteManager {
    private final BanCinematicPlugin plugin;
    private final MuteStorage storage;
    private final PunishmentLogger logger;
    private final DatabaseExecutor executor;

    public MuteManager(BanCinematicPlugin plugin, MuteStorage storage, PunishmentLogger logger,
                       DatabaseExecutor executor) {
        this.plugin = plugin;
        this.storage = storage;
        this.logger = logger;
        this.executor = executor;
    }

    public BanCinematicPlugin plugin() { return plugin; }

    public boolean isMuted(UUID uuid) { return storage.isMuted(uuid); }
    public MuteData get(UUID uuid) { return storage.get(uuid); }

    public String muteNotice(MuteData data) {
        String path = data.expires() == null
                ? "messages.mute-notice"
                : "messages.tempmute-notice";
        return renderMessage(path, data);
    }

    public String muteReminder(MuteData data) {
        String path = data.expires() == null
                ? "messages.mute-reminder-permanent"
                : "messages.mute-reminder";
        return renderMessage(path, data);
    }

    public String mutedChatMessage(MuteData data) {
        String path = data.expires() == null
                ? "messages.muted-chat-permanent"
                : "messages.muted-chat";
        return renderMessage(path, data);
    }

    private String renderMessage(String path, MuteData data) {
        return plugin.messages().get(path)
                .replace("%player%", data.name() == null ? "" : data.name())
                .replace("%reason%", data.reason() == null ? "" : data.reason())
                .replace("%time%", TimeUtil.formatRemaining(data.expires(), plugin.messages()::get));
    }

    public CompletableFuture<PunishmentResult> mute(UUID uuid, String name, String reason, ExecutorIdentity executor) {
        return run(() -> save(new MuteData(uuid, name, reason, executor.name(), null),
                PunishmentType.MUTE, executor, null));
    }

    public CompletableFuture<PunishmentResult> tempMute(UUID uuid, String name, String reason,
                                                        ExecutorIdentity executor, long seconds) {
        return run(() -> save(new MuteData(uuid, name, reason, executor.name(), Instant.now().plusSeconds(seconds)),
                PunishmentType.TEMPMUTE, executor, TimeUtil.format(seconds, plugin.messages()::get)));
    }

    public CompletableFuture<PunishmentResult> unmute(UUID uuid, String name, ExecutorIdentity executor) {
        return run(() -> {
            MuteData previous = storage.get(uuid);
            if (previous == null) return PunishmentResult.NOT_ACTIVE;
            if (!storage.remove(uuid)) return PunishmentResult.STORAGE_ERROR;
            if (!logger.log(PunishmentType.UNMUTE, uuid, name, "Removido", executor.name(), executor, null)) {
                storage.put(previous);
                return PunishmentResult.STORAGE_ERROR;
            }
            return PunishmentResult.SUCCESS;
        });
    }

    /** Runs on the database thread, so the existence check and the write cannot interleave. */
    private PunishmentResult save(MuteData data, PunishmentType type, ExecutorIdentity executor, String duration) {
        UUID uuid = data.uniqueId();
        if (storage.get(uuid) != null) return PunishmentResult.ALREADY_ACTIVE;
        if (!storage.put(data)) return PunishmentResult.STORAGE_ERROR;
        if (!logger.log(type, uuid, data.name(), data.reason(), data.source(), executor, duration)) {
            storage.remove(uuid);
            return PunishmentResult.STORAGE_ERROR;
        }
        return PunishmentResult.SUCCESS;
    }

    private CompletableFuture<PunishmentResult> run(Supplier<PunishmentResult> task) {
        return executor.submit(task).exceptionally(error -> {
            plugin.getLogger().severe("Falha inesperada ao gravar o mute: " + error);
            return PunishmentResult.STORAGE_ERROR;
        });
    }

    /** Public announcement; call it on the server thread after a successful change. */
    public void announceMute(String name, String reason, String source, Long durationSeconds) {
        if (durationSeconds == null) {
            plugin.broadcastPunishment("messages.broadcast.mute", name, reason, source, "");
        } else {
            plugin.broadcastPunishment("messages.broadcast.tempmute", name, reason, source,
                    TimeUtil.format(durationSeconds, plugin.messages()::get));
        }
    }
}
