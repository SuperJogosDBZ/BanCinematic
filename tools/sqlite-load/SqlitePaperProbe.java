import br.com.bancinematic.BanCinematicPlugin;
import br.com.bancinematic.listener.MuteJoinListener;
import br.com.bancinematic.punishment.*;
import br.com.bancinematic.storage.*;
import com.google.gson.Gson;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.java.JavaPlugin;
import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Test-only plugin: captures real Paper event timing without changing BanCinematic. */
public final class SqlitePaperProbe extends JavaPlugin implements Listener {
    private final Gson gson = new Gson();
    private final Map<UUID, Long> preloginStarts = new ConcurrentHashMap<>();
    private final Map<UUID, Long> joining = new HashMap<>();
    private final List<Long> prelogin = Collections.synchronizedList(new ArrayList<>());
    private final List<Long> ready = new ArrayList<>();
    private final List<Long> adminWait = Collections.synchronizedList(new ArrayList<>());
    private final List<Long> adminWork = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger allowed = new AtomicInteger(), rejected = new AtomicInteger(), failed = new AtomicInteger();
    private final AtomicInteger adminSubmitted = new AtomicInteger(), adminCompleted = new AtomicInteger();
    private final AtomicBoolean queueProbePending = new AtomicBoolean();
    private final AtomicLong lastQueueNs = new AtomicLong();
    private BanCinematicPlugin plugin;
    private DatabaseManager db;
    private BanStorage bans;
    private MuteStorage muteStorage;
    private MuteManager mutes;
    private PunishmentLogger history;
    private volatile String phase;
    private double rate, tokens;
    private int ticks;

    @Override public void onEnable() {
        try {
            plugin = (BanCinematicPlugin) Bukkit.getPluginManager().getPlugin("BanCinematic");
            var field = BanCinematicPlugin.class.getDeclaredField("database"); field.setAccessible(true);
            db = (DatabaseManager) field.get(plugin);
            for (var registration : PlayerJoinEvent.getHandlerList().getRegisteredListeners()) {
                if (registration.getPlugin() == plugin && registration.getListener() instanceof MuteJoinListener listener) {
                    var muteField = MuteJoinListener.class.getDeclaredField("mutes"); muteField.setAccessible(true);
                    mutes = (MuteManager) muteField.get(listener);
                }
            }
            if (mutes == null) throw new IllegalStateException("Mute manager not found");
            bans = new BanStorage(plugin, db); muteStorage = new MuteStorage(plugin, db);
            history = new PunishmentLogger(plugin, db);
            getServer().getPluginManager().registerEvents(this, this);
            getCommand("sqliteload").setExecutor(this);
            getServer().getScheduler().runTaskTimer(this, this::tick, 1L, 1L);
            getLogger().info("LOAD_PROBE_READY");
        } catch (Exception error) { throw new RuntimeException(error); }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void startLogin(AsyncPlayerPreLoginEvent event) {
        if (phase != null && event.getName().startsWith("SqlLoad")) preloginStarts.put(event.getUniqueId(), System.nanoTime());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void endLogin(AsyncPlayerPreLoginEvent event) {
        Long start = preloginStarts.remove(event.getUniqueId());
        if (start == null) return;
        prelogin.add(System.nanoTime() - start);
        switch (event.getLoginResult()) {
            case ALLOWED -> allowed.incrementAndGet();
            case KICK_BANNED -> rejected.incrementAndGet();
            default -> failed.incrementAndGet();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void joined(PlayerJoinEvent event) {
        if (phase != null && event.getPlayer().getName().startsWith("SqlLoad")) joining.put(event.getPlayer().getUniqueId(), System.nanoTime());
    }

    @EventHandler public void quit(PlayerQuitEvent event) { joining.remove(event.getPlayer().getUniqueId()); }

    private void tick() {
        if (phase == null) return;
        var iterator = joining.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (mutes.isLoaded(entry.getKey())) { ready.add(System.nanoTime() - entry.getValue()); iterator.remove(); }
        }
        tokens += rate;
        while (tokens >= 20) { tokens -= 20; admin(); }
        if (++ticks % 20 != 0) return;
        if (queueProbePending.compareAndSet(false, true)) {
            long start = System.nanoTime();
            plugin.databaseExecutor().execute(() -> lastQueueNs.set(System.nanoTime() - start))
                    .whenComplete((ignored, error) -> queueProbePending.set(false));
        }
        var sample = new LinkedHashMap<String, Object>();
        sample.put("phase", phase); sample.put("epochMs", System.currentTimeMillis());
        sample.put("players", Bukkit.getOnlinePlayers().size());
        sample.put("ready", Bukkit.getOnlinePlayers().stream().filter(player -> mutes.isLoaded(player.getUniqueId())).count());
        sample.put("averageTickMs", Bukkit.getAverageTickTime()); sample.put("tps1m", Bukkit.getTPS()[0]);
        sample.put("queueMsLastCompletedProbe", lastQueueNs.get() / 1e6);
        sample.put("heapBytes", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
        sample.put("identityCache", plugin.identities().cachedCount());
        sample.put("adminSubmitted", adminSubmitted.get()); sample.put("adminCompleted", adminCompleted.get());
        getLogger().info("LOAD_SAMPLE " + gson.toJson(sample));
    }

    private void admin() {
        int sequence = adminSubmitted.getAndIncrement();
        long enqueue = System.nanoTime();
        plugin.databaseExecutor().execute(() -> {
            long start = System.nanoTime();
            adminWait.add(start - enqueue);
            try {
                int playerBase = Integer.getInteger("loadtest.playerBase", 80_000);
                UUID target = id(playerBase + sequence % 1000);
                switch (sequence % 10) {
                    case 0, 1, 2, 3, 4 -> history.getHistoryPage(target, 0, 8);
                    case 5, 6 -> { plugin.identities().get(name(playerBase + sequence % 1000)); bans.get(target); muteStorage.get(target); }
                    default -> bans.suggestNames(sequence % 2 == 0 ? "" : "Load01");
                }
            } finally { adminWork.add(System.nanoTime() - start); adminCompleted.incrementAndGet(); }
        }).exceptionally(error -> { getLogger().warning("LOAD_ADMIN_ERROR " + error); return null; });
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equals("pause")) { rate = 0; return true; }
        if (args.length == 3 && args[0].equals("start")) {
            if (adminSubmitted.get() != adminCompleted.get()) throw new IllegalStateException("Previous traffic still pending");
            prelogin.clear(); ready.clear(); adminWait.clear(); adminWork.clear(); joining.clear();
            allowed.set(0); rejected.set(0); failed.set(0); adminSubmitted.set(0); adminCompleted.set(0);
            phase = args[1]; rate = Double.parseDouble(args[2]); tokens = 0; ticks = 0;
            getLogger().info("LOAD_STARTED " + phase); return true;
        }
        if (args.length == 1 && args[0].equals("stop")) {
            rate = 0;
            var summary = new LinkedHashMap<String, Object>();
            summary.put("phase", phase); summary.put("allowedPrelogins", allowed.get());
            summary.put("bannedPrelogins", rejected.get()); summary.put("otherRejectedPrelogins", failed.get());
            summary.put("preloginMs", stats(prelogin)); summary.put("joinToMuteReadyMs", stats(ready));
            summary.put("adminWaitMs", stats(adminWait)); summary.put("adminWorkMs", stats(adminWork));
            summary.put("adminSubmitted", adminSubmitted.get()); summary.put("adminCompleted", adminCompleted.get());
            getLogger().info("LOAD_SUMMARY " + gson.toJson(summary)); phase = null; return true;
        }
        return false;
    }

    private static UUID id(long number) { return new UUID(0x0123456789abcdefL, number + 1); }
    private static String name(long number) { return "Load" + String.format(Locale.ROOT, "%06d", number); }
    private static double percentile(long[] values, double p) { return values[Math.max(0, (int)Math.ceil(values.length * p) - 1)] / 1e6; }

    private static Map<String, Object> stats(List<Long> list) {
        synchronized (list) { long[] values = list.stream().mapToLong(Long::longValue).sorted().toArray();
            if (values.length == 0) return Map.of("count", 0);
            return Map.of("count", values.length, "p50", percentile(values, .5), "p95", percentile(values, .95),
                    "p99", percentile(values, .99), "max", values[values.length - 1] / 1e6); }
    }
}
