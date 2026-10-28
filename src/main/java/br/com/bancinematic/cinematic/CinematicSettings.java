package br.com.bancinematic.cinematic;

import org.bukkit.configuration.file.FileConfiguration;

public record CinematicSettings(
        boolean enabled,
        boolean darkness,
        boolean sounds,
        boolean particles,
        boolean lightningOnHit,
        org.bukkit.Particle dissolveParticle,
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
    private static final int DEFAULT_MAX_SECONDS = 15;

    public static CinematicSettings from(FileConfiguration config) {
        return new CinematicSettings(
                config.getBoolean("cinematic.enabled", true),
                config.getBoolean("cinematic.darkness", true),
                config.getBoolean("cinematic.sounds", true),
                config.getBoolean("cinematic.particles", true),
                config.getBoolean("cinematic.lightning-on-hit", true),
                dissolveParticle(config),
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
                config.getDouble("cinematic.monster.distance", 11.0),
                config.getDouble("cinematic.monster.height-offset", 0.0),
                config.getDouble("cinematic.monster.approach-speed", 0.16),
                config.getDouble("cinematic.monster.attack-distance", 3.0)
        );
    }
    private static org.bukkit.Particle dissolveParticle(FileConfiguration config) {
        String name = config.getString("cinematic.dissolve-particle", "SCULK_SOUL");
        try {
            var particle = org.bukkit.Particle.valueOf(name.trim().toUpperCase(java.util.Locale.ROOT));
            return particle.getDataType() == Void.class ? particle : org.bukkit.Particle.SCULK_SOUL;
        } catch (IllegalArgumentException exception) {
            return org.bukkit.Particle.SCULK_SOUL;
        }
    }

}
