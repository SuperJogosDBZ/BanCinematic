package br.com.bancinematic.cinematic;

import br.com.bancinematic.BanCinematicPlugin;
import br.com.bancinematic.punishment.BanManager;
import br.com.bancinematic.punishment.PunishmentResult;
import br.com.bancinematic.util.ExecutorName;
import br.com.bancinematic.util.TimeUtil;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Pose;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public final class BanCinematic implements Listener {
    private static final int DARKNESS_DURATION_TICKS = 30 * 20;
    private static final int IMPACT_DISPLAY_TICKS = 6;

    private final BanCinematicPlugin plugin;
    private final BanManager banManager;
    private final Map<UUID, CinematicSession> sessions = new HashMap<>();
    private final Set<UUID> pending = new HashSet<>();
    private final Map<UUID, Entity> activeMonsters = new HashMap<>();
    private final NamespacedKey playerStateKey;

        public BanCinematic(BanCinematicPlugin plugin, BanManager banManager) {
        this.plugin = plugin;
        this.banManager = banManager;
        this.playerStateKey = new NamespacedKey(plugin, "cinematic-player-state");
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public boolean isRunning(UUID playerId) {
        return sessions.containsKey(playerId) || pending.contains(playerId);
    }

    public void start(CommandSender source, Player target, String reason, Long duration) {
        UUID uuid = target.getUniqueId();

        if (isRunning(uuid)) {
            source.sendMessage(plugin.messages().get("messages.cinematic-already-running")
                    .replace("%player%", target.getName()));
            return;
        }
        pending.add(uuid);

        String name = target.getName();
        String sourceName = ExecutorName.from(source);
        CompletableFuture<PunishmentResult> saving = duration == null
                ? banManager.ban(uuid, name, reason, sourceName)
                : banManager.tempBan(uuid, name, reason, sourceName, duration);

        saving.thenAccept(result -> plugin.runSync(() ->
                afterBanSaved(source, sourceName, target, reason, duration, result)));
    }

    private void afterBanSaved(CommandSender source, String sourceName, Player target, String reason,
                               Long duration, PunishmentResult result) {
        UUID uuid = target.getUniqueId();
        try {
            continueAfterBanSaved(source, sourceName, target, reason, duration, result);
        } catch (RuntimeException error) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE,
                    "Falha ao iniciar a cinemática de " + target.getName()
                            + ". O ban está salvo; desfazendo a cena e desconectando o jogador.", error);
            CinematicSession broken = sessions.remove(uuid);
            if (broken != null) cleanup(broken);

            Player stillOnline = Bukkit.getPlayer(uuid);
            if (stillOnline != null) {
                try {
                    punishAndKick(source, sourceName, stillOnline, reason, duration);
                } catch (RuntimeException second) {
                    plugin.getLogger().log(java.util.logging.Level.SEVERE,
                            "Também não foi possível desconectar " + stillOnline.getName() + ".", second);
                }
            }
        } finally {
            pending.remove(uuid);
        }
    }

    private void continueAfterBanSaved(CommandSender source, String sourceName, Player target, String reason,
                                       Long duration, PunishmentResult result) {
        UUID uuid = target.getUniqueId();

        if (result == PunishmentResult.ALREADY_ACTIVE) {
            source.sendMessage(plugin.messages().get("messages.already-banned"));
            return;
        }
        if (result != PunishmentResult.SUCCESS) {
            source.sendMessage(plugin.messages().get("messages.storage-error"));
            return;
        }

        Player currentTarget = Bukkit.getPlayer(uuid);
        if (currentTarget == null) {
            banManager.announceBan(target.getName(), reason, sourceName, duration);
            banManager.sendBanConfirmation(source, target.getName(), reason, duration);
            return;
        }
        final Player cinematicTarget = currentTarget;

        plugin.sendStaffMessage(source, plugin.messages().get("messages.ban-started")
                .replace("%player%", cinematicTarget.getName()));

        CinematicSettings settings = CinematicSettings.from(plugin.getConfig());
        if (!settings.enabled()) {
            punishAndKick(source, sourceName, cinematicTarget, reason, duration);
            return;
        }

        Location origin = cinematicTarget.getLocation().clone();
        CinematicSession session = new CinematicSession(
                cinematicTarget, origin, source, sourceName, reason, duration, settings
        );
        sessions.put(uuid, session);

        saveState(session, cinematicTarget);
        cinematicTarget.setInvulnerable(true);
        cinematicTarget.setCollidable(false);
        cinematicTarget.setGameMode(GameMode.ADVENTURE);

        Entity monster = spawnMonster(origin, cinematicTarget, settings);
        session.monster(monster);
        if (monster != null) activeMonsters.put(monster.getUniqueId(), monster);

        if (settings.darkness()) {
            cinematicTarget.addPotionEffect(new PotionEffect(
                    PotionEffectType.DARKNESS,
                    DARKNESS_DURATION_TICKS,
                    0,
                    false,
                    false,
                    false
            ));
        }

        BukkitRunnable task = new BukkitRunnable() {
            @Override
            public void run() {
                if (!cinematicTarget.isOnline() || sessions.get(uuid) != session) {
                    cancel();
                    return;
                }
                tick(session, cinematicTarget);
            }
        };

        session.task(task);
        task.runTaskTimer(plugin, 1L, 1L);
    }

    private Entity spawnMonster(Location origin, Player target, CinematicSettings settings) {
        World world = origin.getWorld();
        if (world == null) return null;

        try {
            EntityType type = EntityType.valueOf(settings.monsterType().toUpperCase(Locale.ROOT));

            double distance = settings.monsterDistance();
            double yaw = Math.toRadians(origin.getYaw());

            Location spot = ground(origin.clone().add(
                    -Math.sin(yaw) * distance,
                    0,
                    Math.cos(yaw) * distance
            ));

            spot.add(
                    0,
                    settings.monsterHeightOffset(),
                    0
            );

            Class<? extends Entity> entityClass = type.getEntityClass();
            if (entityClass == null) {
                throw new IllegalArgumentException(type + " não pode ser criado");
            }

            java.util.function.Consumer<Entity> setup = entity -> {
                entity.setPersistent(false);
                if (entity instanceof LivingEntity living) {
                    living.setAI(false);
                    living.setInvulnerable(true);
                    living.setCollidable(false);
                    living.setRemoveWhenFarAway(false);

                    AttributeInstance scale = living.getAttribute(Attribute.SCALE);
                    if (scale != null) {
                        scale.setBaseValue(settings.monsterScale());
                    }
                }

                if (entity.getType() == EntityType.WARDEN) {
                    entity.setPose(Pose.EMERGING, true);
                }
            };

            Entity monster = world.spawn(spot, entityClass, setup);
            face(monster, target);

            if (world.getDifficulty() == Difficulty.PEACEFUL
                    && monster instanceof org.bukkit.entity.Enemy) {
                plugin.getLogger().warning(
                        "O mundo " + world.getName()
                                + " está em pacífico: o jogo pode remover "
                                + type + " durante a cena."
                );
            }

            if (settings.sounds()) {
                world.playSound(
                        spot,
                        Sound.ENTITY_WARDEN_EMERGE,
                        1.5f,
                        1f
                );
            }

            return monster;
        } catch (Exception exception) {
            plugin.getLogger().warning(
                    "Não foi possível criar o monstro: "
                            + exception.getMessage()
            );
            return null;
        }
    }

    private void tick(CinematicSession session, Player target) {
        session.tick(session.tick() + 1);
        session.phaseTicks(session.phaseTicks() + 1);

        CinematicSettings settings = session.settings();
        Entity monster = session.monster();
        boolean sounds = settings.sounds();
        boolean alive = monster != null && monster.isValid();

        int maxTicks = (int) Math.max(
                100L,
                Math.min(Integer.MAX_VALUE, settings.maxSeconds() * 20L)
        );

        holdPlayer(session, target, alive ? monster : null);

        if (sounds && session.tick() % 32 == 0) {
            target.playSound(
                    target.getLocation(),
                    Sound.ENTITY_WARDEN_HEARTBEAT,
                    1f,
                    1f
            );
        }

        if (settings.particles()
                && session.tick() % 8 == 0
                && alive) {
            monster.getWorld().spawnParticle(
                    Particle.SCULK_SOUL,
                    monster.getLocation().add(0, 1, 0),
                    3,
                    .6,
                    .8,
                    .6,
                    .01
            );
        }

        switch (session.phase()) {
            case EMERGING -> {
                if (!alive) {
                    session.phase(CinematicSession.Phase.WALKING);
                } else {
                    face(monster, target);

                    int emergeTicks = settings.emergeTicks();

                    if (session.phaseTicks() >= emergeTicks) {
                        if (monster.getType() == EntityType.WARDEN) {
                            monster.setPose(Pose.STANDING, true);
                        }
                        session.phase(CinematicSession.Phase.STARE);
                    }
                }
            }

            case STARE -> {
                if (!alive) {
                    startWindup(session, target);
                    break;
                }

                face(monster, target);
                int stareTicks = Math.max(0, settings.stareTicks());
                if (session.phaseTicks() >= stareTicks) {
                    session.phase(CinematicSession.Phase.WALKING);
                }
            }

            case WALKING -> {
                if (!alive
                        || session.tick() >= maxTicks) {
                    startWindup(session, target);
                } else if (step(session, monster, target)) {
                    startWindup(session, target);
                } else if (sounds && session.tick() % 10 == 0) {
                    monster.getWorld().playSound(
                            monster.getLocation(),
                            Sound.ENTITY_WARDEN_STEP,
                            1.0f,
                            .72f + (float) Math.min(1.0, session.phaseTicks() / 30.0) * .12f
                    );
                }
            }

            case WINDUP -> {
                if (alive) {
                    face(monster, target);
                }

                int windupTicks = Math.max(1, settings.windupTicks());
                if (session.phaseTicks() >= windupTicks) {
                    startAttack(session, target);
                }
            }

            case ATTACKING -> {
                int attackTicks = Math.max(1, settings.attackAnimationTicks());
                if (session.phaseTicks() >= attackTicks) {
                    hit(session, target);
                }
            }

            case IMPACT -> {
                if (session.phaseTicks() >= IMPACT_DISPLAY_TICKS) {
                    finishHit(session, target);
                }
            }

        }
    }

    private void holdPlayer(
            CinematicSession session,
            Player target,
            Entity monster
    ) {
        Location origin = session.origin();
        Location current = target.getLocation();

        if (current.getWorld() != origin.getWorld()
                || current.distanceSquared(origin) > 0.01) {
            Location lock = origin.clone();
            lock.setYaw(session.cameraYaw());
            lock.setPitch(session.cameraPitch());
            target.teleport(lock);
        }

        if (session.phase() == CinematicSession.Phase.IMPACT) {
            applyImpactCameraJolt(session, target);
            return;
        }

        if (monster != null) {
            CinematicSettings settings = session.settings();
            int turnDelay = Math.max(0, settings.cameraTurnDelayTicks());
            if (session.tick() > turnDelay) {
                Location eye = origin.clone().add(0, target.getEyeHeight(), 0);
                Location aim = monster instanceof LivingEntity living
                        ? living.getEyeLocation()
                        : monster.getLocation();
                lookAt(eye, aim);

                double yawStep = Math.max(0.1, settings.cameraYawMaxStep());
                double pitchStep = Math.max(0.1, settings.cameraPitchMaxStep());
                session.cameraYaw(smoothAngle(
                        session.cameraYaw(), eye.getYaw(), yawStep));
                session.cameraPitch(smoothLinear(
                        session.cameraPitch(), eye.getPitch(), pitchStep));
            }
        }

        Location latest = target.getLocation();
        if (Math.abs(wrapAngle(latest.getYaw() - session.cameraYaw())) > 0.05
                || Math.abs(latest.getPitch() - session.cameraPitch()) > 0.05) {
            target.setRotation(session.cameraYaw(), session.cameraPitch());
        }
    }

    private void applyImpactCameraJolt(CinematicSession session, Player target) {
        float yawOffset;
        float pitchOffset;
        switch (session.phaseTicks()) {
            case 1 -> { yawOffset = 8.0f; pitchOffset = 5.0f; }
            case 2 -> { yawOffset = -4.5f; pitchOffset = 3.0f; }
            case 3 -> { yawOffset = 1.5f; pitchOffset = 0.75f; }
            case 4 -> { yawOffset = -0.5f; pitchOffset = 0.25f; }
            case 5 -> { yawOffset = 0.2f; pitchOffset = 0.05f; }
            default -> { yawOffset = 0.0f; pitchOffset = 0.0f; }
        }

        float yaw = session.impactCameraYaw() + yawOffset;
        float pitch = Math.max(-90.0f, Math.min(
                90.0f,
                session.impactCameraPitch() + pitchOffset
        ));
        target.setRotation(yaw, pitch);
    }

    private float smoothAngle(float current, float target, double maxStep) {
        double difference = wrapAngle(target - current);
        if (Math.abs(difference) < 0.15) return target;

        double step = Math.copySign(
                Math.min(Math.abs(difference) * 0.18, maxStep),
                difference
        );
        return (float) (current + step);
    }

    private float smoothLinear(float current, float target, double maxStep) {
        double difference = target - current;
        if (Math.abs(difference) < 0.15) return target;

        double step = Math.copySign(
                Math.min(Math.abs(difference) * 0.18, maxStep),
                difference
        );
        return (float) (current + step);
    }

    private double wrapAngle(double angle) {
        angle %= 360.0;
        if (angle >= 180.0) angle -= 360.0;
        if (angle < -180.0) angle += 360.0;
        return angle;
    }

    private void startWindup(CinematicSession session, Player target) {
        if (session.phase() == CinematicSession.Phase.WINDUP) return;

        Entity monster = session.monster();
        if (monster != null && monster.isValid()) {
            face(monster, target);
            if (session.settings().sounds()) {
                monster.getWorld().playSound(
                        monster.getLocation(),
                        Sound.ENTITY_WARDEN_ROAR,
                        1.2f,
                        .78f
                );
            }
        }
        session.phase(CinematicSession.Phase.WINDUP);
    }

    private boolean step(CinematicSession session, Entity monster, Player target) {
        Location from = monster.getLocation();

        Vector toPlayer = target.getLocation()
                .toVector()
                .subtract(from.toVector());

        toPlayer.setY(0);

        double distance = toPlayer.length();

        CinematicSettings settings = session.settings();
        double reach = settings.monsterAttackDistance();

        if (distance <= reach) {
            face(monster, target);
            return true;
        }

        double remaining = distance - reach;
        double maxSpeed = settings.monsterApproachSpeed();
        double acceleration = 0.4 + 0.6 * Math.min(1.0, session.phaseTicks() / 12.0);
        double slowdown = 0.35 + 0.65 * Math.min(1.0, remaining / 1.8);
        double speed = Math.min(remaining, maxSpeed * acceleration * slowdown);

        Location next = ground(from.clone().add(
                toPlayer.normalize().multiply(speed)));

        lookAt(next, target.getEyeLocation());
        next.setPitch(0);
        monster.teleport(next);

        if (monster instanceof LivingEntity living) {
            living.setBodyYaw(next.getYaw());
        }

        return false;
    }

    private void startAttack(CinematicSession session, Player target) {
        if (session.phase() != CinematicSession.Phase.WINDUP) return;

        Entity monster = session.monster();
        if (monster != null && monster.isValid()) {
            face(monster, target);
            if (monster.getType() == EntityType.WARDEN) {
                monster.playEffect(EntityEffect.WARDEN_ATTACK);
            }
        }

        session.phase(CinematicSession.Phase.ATTACKING);
    }

    private void hit(CinematicSession session, Player target) {
        if (session.phase() != CinematicSession.Phase.ATTACKING) return;

        Entity monster = session.monster();
        Location impactLocation = target.getLocation();
        World impactWorld = impactLocation.getWorld();

        if (monster != null && monster.isValid()) {
            if (session.settings().sounds()) {
                monster.getWorld().playSound(
                        impactLocation,
                        Sound.ENTITY_WARDEN_ATTACK_IMPACT,
                        1.5f,
                        .8f
                );
            }
        }

        if (session.settings().sounds()
                && impactWorld != null) {
            target.playSound(
                    impactLocation,
                    Sound.ENTITY_PLAYER_HURT,
                    1.1f,
                    1.0f
            );
        }

        target.playHurtAnimation(0f);
        target.sendHurtAnimation(0f);

        if (session.settings().particles()
                && impactWorld != null) {
            Location impactCenter = impactLocation.clone().add(
                    0,
                    Math.max(0.5, target.getEyeHeight() * 0.55),
                    0
            );
            impactWorld.spawnParticle(
                    Particle.DAMAGE_INDICATOR,
                    impactCenter,
                    7,
                    .25,
                    .3,
                    .25,
                    .05
            );
            impactWorld.spawnParticle(
                    Particle.CRIT,
                    impactCenter,
                    10,
                    .3,
                    .35,
                    .3,
                    .08
            );
        }

        session.impactCamera(session.cameraYaw(), session.cameraPitch());
        session.phase(CinematicSession.Phase.IMPACT);
    }

    private void finishHit(CinematicSession session, Player target) {
        if (!beginFinish(session)) return;

        cleanupPlayer(session);

        punishAndKick(
                session.source(),
                session.sourceName(),
                target,
                session.reason(),
                session.duration()
        );

        dissolveMonster(session);
    }

    private void dissolveMonster(CinematicSession session) {
        Entity monster = session.monster();
        if (monster == null) return;
        if (!monster.isValid()) {
            activeMonsters.remove(monster.getUniqueId());
            return;
        }

        final World world = monster.getWorld();
        final Location center = monster.getLocation().clone().add(0, 1.2, 0);
        final CinematicSettings settings = session.settings();
        final int duration = Math.max(30, settings.dissolveTicks());
        final boolean particles = settings.particles();
        final boolean sounds = settings.sounds();

        activeMonsters.put(monster.getUniqueId(), monster);
        new BukkitRunnable() {
            int tick;

            @Override
            public void run() {
                if (!monster.isValid()) {
                    activeMonsters.remove(monster.getUniqueId());
                    cancel();
                    return;
                }

                tick++;
                double progress = Math.min(1.0, tick / (double) duration);

                if (particles) {
                    int count = 6 + (int) Math.round(progress * 14);
                    world.spawnParticle(
                            Particle.SCULK_SOUL, center, count,
                            0.9, 1.1, 0.9, 0.025
                    );
                    world.spawnParticle(
                            Particle.REVERSE_PORTAL, center,
                            4 + (int) Math.round(progress * 8),
                            0.7, 0.9, 0.7, 0.02
                    );
                }

                if (sounds && tick % 6 == 0) {
                    world.playSound(
                            monster.getLocation(),
                            Sound.ENTITY_WARDEN_HEARTBEAT,
                            0.8f,
                            0.65f + (float) progress * 0.45f
                    );
                }

                if (monster instanceof LivingEntity living) {
                    AttributeInstance scale = living.getAttribute(Attribute.SCALE);
                    if (scale != null) {
                        double original = settings.monsterScale();
                        scale.setBaseValue(Math.max(0.05, original * (1.0 - progress)));
                    }
                }

                if (progress >= 1.0) {
                    if (sounds) {
                        world.playSound(
                                monster.getLocation(),
                                Sound.ENTITY_WARDEN_DEATH,
                                1.0f,
                                0.8f
                        );
                    }
                    monster.remove();
                    activeMonsters.remove(monster.getUniqueId());
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private void cleanupPlayer(CinematicSession session) {
        BukkitRunnable task = session.task();
        if (task != null) task.cancel();

        Player player = session.player();
        if (player.isOnline()) {
            player.setInvulnerable(session.previousInvulnerable());
            player.setCollidable(session.previousCollidable());
            player.removePotionEffect(PotionEffectType.DARKNESS);
            player.setGameMode(session.previousGameMode());
            player.getPersistentDataContainer().remove(playerStateKey);
        }
    }

    private void saveState(CinematicSession session, Player player) {
        player.getPersistentDataContainer().set(
                playerStateKey,
                PersistentDataType.STRING,
                session.previousGameMode().name()
                        + ";" + session.previousInvulnerable()
                        + ";" + session.previousCollidable()
        );
    }

    private void restoreSavedState(Player player) {
        PersistentDataContainer data = player.getPersistentDataContainer();
        String saved = data.get(playerStateKey, PersistentDataType.STRING);
        if (saved == null) return;
        data.remove(playerStateKey);

        String[] parts = saved.split(";");
        try {
            player.setGameMode(GameMode.valueOf(parts[0]));
        } catch (IllegalArgumentException ignored) {
        }
        if (parts.length > 1) player.setInvulnerable(Boolean.parseBoolean(parts[1]));
        if (parts.length > 2) player.setCollidable(Boolean.parseBoolean(parts[2]));
        player.removePotionEffect(PotionEffectType.DARKNESS);
    }


    private boolean beginFinish(CinematicSession session) {
        return sessions.remove(session.playerId(), session);
    }

    private void punishAndKick(
            CommandSender source,
            String sourceName,
            Player target,
            String reason,
            Long duration
    ) {
        String path = duration == null
                ? "messages.ban-screen"
                : "messages.tempban-screen";

        String finalText = plugin.messages().screen(
                path,
                reason,
                sourceName,
                target.getName(),
                duration == null ? "" : TimeUtil.format(duration, plugin.messages()::get)
        );

        Runnable announceAndConfirm = () -> {
            if (target.isOnline()) {
                String failedKick = plugin.messages().raw(
                        "messages.ban-kick-failed",
                        "&cO ban foi salvo, mas o jogador continua conectado."
                ).replace("%player%", target.getName());
                plugin.sendStaffMessage(source, plugin.messages().component(failedKick));
                return;
            }
            banManager.announceBan(target.getName(), reason, sourceName, duration);
            banManager.sendBanConfirmation(source, target.getName(), reason, duration);
        };

        if (target.isOnline()) {
            target.kick(plugin.messages().component(finalText));
            Bukkit.getScheduler().runTaskLater(plugin, announceAndConfirm, 1L);
        } else {
            announceAndConfirm.run();
        }
    }

    private void cleanup(CinematicSession session) {
        Entity monster = session.monster();
        if (monster != null) {
            activeMonsters.remove(monster.getUniqueId());
            if (monster.isValid()) monster.remove();
        }
        cleanupPlayer(session);
    }

    private boolean isCinematicPlayer(Player player) {
        return player != null && sessions.containsKey(player.getUniqueId());
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!isCinematicPlayer(player)) return;

        event.setCancelled(true);
        String message = plugin.messages().raw(
                "messages.cinematic-command-blocked",
                "&cVocê não pode usar comandos durante a punição."
        );
        player.sendMessage(plugin.messages().component(message));
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (isCinematicPlayer(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (isCinematicPlayer(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler
    public void onHeldItem(PlayerItemHeldEvent event) {
        if (isCinematicPlayer(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler
    public void onSwapHand(PlayerSwapHandItemsEvent event) {
        if (isCinematicPlayer(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player
                && isCinematicPlayer(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        restoreSavedState(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        CinematicSession session =
                sessions.get(event.getPlayer().getUniqueId());

        if (session == null) return;

        if (beginFinish(session)) {
            cleanup(session);
        }
    }

    public void shutdown() {
        for (CinematicSession session :
                sessions.values().toArray(new CinematicSession[0])) {
            cleanup(session);
        }
        sessions.clear();
        pending.clear();

        for (Entity monster : activeMonsters.values().toArray(new Entity[0])) {
            if (monster.isValid()) monster.remove();
        }
        activeMonsters.clear();
    }

    private Location ground(Location location) {
        World world = location.getWorld();

        if (world == null) return location;

        int baseY = location.getBlockY();

        for (int dy = 2; dy >= -6; dy--) {
            Block feet = world.getBlockAt(
                    location.getBlockX(),
                    baseY + dy,
                    location.getBlockZ()
            );

            if (feet.isPassable()
                    && feet.getRelative(0, -1, 0).getType().isSolid()) {
                Location found = location.clone();
                found.setY(baseY + dy);
                return found;
            }
        }

        return location;
    }

    private void face(Entity entity, Player target) {
        Location from = entity.getLocation();
        Location aim = target.getEyeLocation();

        lookAt(from, aim);

        entity.teleport(from);

        if (entity instanceof LivingEntity living) {
            living.setBodyYaw(from.getYaw());
        }
    }

    private void lookAt(Location from, Location target) {
        Vector delta = target.toVector().subtract(from.toVector());

        double horizontal = Math.sqrt(
                delta.getX() * delta.getX()
                        + delta.getZ() * delta.getZ()
        );

        from.setYaw((float) Math.toDegrees(
                Math.atan2(-delta.getX(), delta.getZ())
        ));

        from.setPitch((float) Math.toDegrees(
                -Math.atan2(delta.getY(), horizontal)
        ));
    }
}
