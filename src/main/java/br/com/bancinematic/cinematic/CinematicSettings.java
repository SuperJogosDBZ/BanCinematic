package br.com.bancinematic.cinematic;

import org.bukkit.configuration.file.FileConfiguration;

/** Immutable snapshot of cinematic options, captured when a punishment scene starts. */
public record CinematicSettings(
        boolean enabled,
        boolean darkness,
        boolean sounds,
        boolean particles,
        int emergeTicks,
        int stareTicks,
        int windupTicks,
        int attackAnimationTicks,
        int cameraTurnDelayTicks,
        double cameraYawMaxStep,
        double cameraPitchMaxStep,
        int dissolveTicks,
        int maxSeconds,
        String monsterType,
        double monsterScale,
        double monsterDistance,
        double monsterHeightOffset,
        double monsterApproachSpeed,
        double monsterAttackDistance
) {
    /** Valor usado quando cinematic.max-seconds não existe no config.yml. Não é um limite. */
    private static final int DEFAULT_MAX_SECONDS = 15;

    public static CinematicSettings from(FileConfiguration config) {
        return new CinematicSettings(
                config.getBoolean("cinematic.enabled", true),
                config.getBoolean("cinematic.darkness", true),
                config.getBoolean("cinematic.sounds", true),
                config.getBoolean("cinematic.particles", true),
                config.getInt("cinematic.emerge-ticks", 50),
                config.getInt("cinematic.stare-ticks", 8),
                config.getInt("cinematic.windup-ticks", 12),
                config.getInt("cinematic.attack-animation-ticks", 10),
                config.getInt("cinematic.camera-turn-delay-ticks", 6),
                config.getDouble("cinematic.camera-yaw-max-step", 6.5),
                config.getDouble("cinematic.camera-pitch-max-step", 3.5),
                config.getInt("cinematic.dissolve-ticks", 30),
                Math.max(1, config.getInt("cinematic.max-seconds", DEFAULT_MAX_SECONDS)),
                config.getString("cinematic.monster.type", "WARDEN"),
                config.getDouble("cinematic.monster.scale", 3.0),
                config.getDouble("cinematic.monster.distance", 9.0),
                config.getDouble("cinematic.monster.height-offset", 0.0),
                config.getDouble("cinematic.monster.approach-speed", 0.16),
                config.getDouble("cinematic.monster.attack-distance", 2.5)
        );
    }
}
