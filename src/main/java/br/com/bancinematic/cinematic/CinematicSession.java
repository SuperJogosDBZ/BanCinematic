package br.com.bancinematic.cinematic;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.UUID;

/** Estado completo de uma cinematic em execução. */
public final class CinematicSession {
    public enum Phase { EMERGING, STARE, WALKING, WINDUP, ATTACKING, IMPACT }
    private final UUID playerId;
    private final Location origin;
    private final CommandSender source;
    private final String reason;
    private final Long duration;
    private final Player player;
    private final CinematicSettings settings;

    private final GameMode previousGameMode;
    private final boolean previousInvulnerable;
    private final boolean previousCollidable;

    private Entity monster;
    private BukkitRunnable task;

    private Phase phase = Phase.EMERGING;
    private int tick;
    private int phaseTicks;
    private float cameraYaw;
    private float cameraPitch;
    private float impactCameraYaw;
    private float impactCameraPitch;

    public CinematicSession(
            Player player,
            Location origin,
            CommandSender source,
            String reason,
            Long duration,
            CinematicSettings settings
    ) {
        this.player = player;
        this.playerId = player.getUniqueId();
        this.origin = origin.clone();
        this.source = source;
        this.reason = reason;
        this.duration = duration;
        this.settings = settings;
        this.previousGameMode = player.getGameMode();
        this.previousInvulnerable = player.isInvulnerable();
        this.previousCollidable = player.isCollidable();
        this.cameraYaw = origin.getYaw();
        this.cameraPitch = origin.getPitch();
    }

    public UUID playerId() { return playerId; }
    public Location origin() { return origin; }
    public CommandSender source() { return source; }
    public String reason() { return reason; }
    public Long duration() { return duration; }
    public Player player() { return player; }
    public CinematicSettings settings() { return settings; }

    public Entity monster() { return monster; }
    public void monster(Entity monster) { this.monster = monster; }

    public BukkitRunnable task() { return task; }
    public void task(BukkitRunnable task) { this.task = task; }

    public GameMode previousGameMode() { return previousGameMode; }
    public boolean previousInvulnerable() { return previousInvulnerable; }
    public boolean previousCollidable() { return previousCollidable; }

    public int tick() { return tick; }
    public void tick(int tick) { this.tick = tick; }

    public int phaseTicks() { return phaseTicks; }
    public void phaseTicks(int phaseTicks) { this.phaseTicks = phaseTicks; }

    public float cameraYaw() { return cameraYaw; }
    public void cameraYaw(float cameraYaw) { this.cameraYaw = cameraYaw; }

    public float cameraPitch() { return cameraPitch; }
    public void cameraPitch(float cameraPitch) { this.cameraPitch = cameraPitch; }

    public float impactCameraYaw() { return impactCameraYaw; }
    public float impactCameraPitch() { return impactCameraPitch; }
    public void impactCamera(float yaw, float pitch) {
        this.impactCameraYaw = yaw;
        this.impactCameraPitch = pitch;
    }


    public Phase phase() { return phase; }
    public void phase(Phase phase) {
        this.phase = phase;
        this.phaseTicks = 0;
    }
}
