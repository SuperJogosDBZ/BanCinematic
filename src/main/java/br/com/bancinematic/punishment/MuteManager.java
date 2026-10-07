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

    public CompletableFuture<PunishmentResult> mute(UUID uuid, String name, String reason, String source) {
        return run(() -> save(new MuteData(uuid, name, reason, source, null),
                PunishmentType.MUTE, null));
    }

    public CompletableFuture<PunishmentResult> tempMute(UUID uuid, String name, String reason,
                                                        String source, long seconds) {
        return run(() -> save(new MuteData(uuid, name, reason, source, Instant.now().plusSeconds(seconds)),
                PunishmentType.TEMPMUTE, TimeUtil.format(seconds, plugin.messages()::get)));
    }

    public CompletableFuture<PunishmentResult> unmute(UUID uuid, String name, String source) {
        return run(() -> {
            MuteData previous = storage.get(uuid);
            if (previous == null) return PunishmentResult.NOT_ACTIVE;
            if (!storage.remove(uuid)) return PunishmentResult.STORAGE_ERROR;
            if (!logger.log(PunishmentType.UNMUTE, uuid, name, "Removido", source, null)) {
                storage.put(previous);
                return PunishmentResult.STORAGE_ERROR;
            }
            return PunishmentResult.SUCCESS;
        });
    }

    private PunishmentResult save(MuteData data, PunishmentType type, String duration) {
        UUID uuid = data.uniqueId();
        if (storage.get(uuid) != null) return PunishmentResult.ALREADY_ACTIVE;
        if (!storage.put(data)) return PunishmentResult.STORAGE_ERROR;
        if (!logger.log(type, uuid, data.name(), data.reason(), data.source(), duration)) {
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

    public void announceMute(String name, String reason, String source, Long durationSeconds) {
        if (durationSeconds == null) {
            plugin.broadcastPunishment("messages.broadcast.mute", name, reason, source, "");
        } else {
            plugin.broadcastPunishment("messages.broadcast.tempmute", name, reason, source,
                    TimeUtil.format(durationSeconds, plugin.messages()::get));
        }
    }
}
