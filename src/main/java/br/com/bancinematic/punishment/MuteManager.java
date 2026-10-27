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
    private final java.util.concurrent.ConcurrentMap<UUID, OnlineMute> online = new java.util.concurrent.ConcurrentHashMap<>();

    public MuteManager(BanCinematicPlugin plugin, MuteStorage storage, PunishmentLogger logger,
                       DatabaseExecutor executor) {
        this.plugin = plugin;
        this.storage = storage;
        this.logger = logger;
        this.executor = executor;
    }

    public BanCinematicPlugin plugin() { return plugin; }

    public MuteData get(UUID uuid) { return storage.get(uuid); }
    public java.util.List<String> suggestNames(String prefix) { return storage.suggestNames(prefix); }

    public boolean isLoaded(UUID uuid) {
        OnlineMute state = online.get(uuid);
        return state != null && state.ready();
    }

    public MuteData getOnline(UUID uuid) {
        OnlineMute state = online.get(uuid);
        MuteData mute = state == null ? null : state.data();
        return mute != null && !mute.expired() ? mute : null;
    }

    public CompletableFuture<MuteData> loadOnline(UUID uuid) {
        OnlineMute loading = new OnlineMute(false, null);
        online.put(uuid, loading);
        return executor.submit(() -> storage.get(uuid)).thenApply(data -> {
            online.computeIfPresent(uuid, (id, current) -> current == loading ? new OnlineMute(true, data) : current);
            return data;
        });
    }

    public void updateOnline(UUID uuid, MuteData data) {
        online.computeIfPresent(uuid, (id, current) -> new OnlineMute(true, data));
    }

    public void updateOnlineFromSync(UUID uuid, MuteData data, long expectedRevision) {
        online.computeIfPresent(uuid, (id, current) -> storage.revision() == expectedRevision
                ? new OnlineMute(true, data) : current);
    }

    public void forgetOnline(UUID uuid) { online.remove(uuid); }

    private record OnlineMute(boolean ready, MuteData data) {}

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
                PunishmentType.TEMPMUTE, seconds));
    }

    public CompletableFuture<PunishmentResult> unmute(UUID uuid, String name, String source) {
        return run(() -> {
            PunishmentResult result = storage.removeAndLog(uuid, name, "Removido", source, PunishmentType.UNMUTE, logger);
            if (result == PunishmentResult.SUCCESS || result == PunishmentResult.NOT_ACTIVE) updateOnline(uuid, null);
            return result;
        });
    }

    private PunishmentResult save(MuteData data, PunishmentType type, Long durationSeconds) {
        PunishmentResult result = storage.putAndLog(data, type, durationSeconds, logger);
        if (result == PunishmentResult.SUCCESS) updateOnline(data.uniqueId(), data);
        return result;
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
