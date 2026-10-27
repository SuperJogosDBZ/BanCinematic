import br.com.bancinematic.BanCinematicPlugin;
import br.com.bancinematic.punishment.*;
import br.com.bancinematic.storage.*;
import com.google.gson.GsonBuilder;
import org.bukkit.configuration.file.YamlConfiguration;
import java.lang.management.ManagementFactory;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import java.util.logging.*;
import static org.mockito.Mockito.*;

/** Isolated load harness: real plugin storage/executor, mocked Paper container only. */
public final class SqliteLoadTest {
    record Sample(String kind, long waitNs, long workNs, boolean failed) {}
    record Login(String outcome, long totalNs) {}
    final Path directory;
    final boolean large;
    final int players, banned, muted, regularHistory, heavyHistory;
    final DatabaseExecutor worker = DatabaseExecutor.create();
    final DatabaseExecutor observed = mock(DatabaseExecutor.class);
    final ThreadLocal<String> kind = ThreadLocal.withInitial(() -> "other");
    final List<Sample> samples = Collections.synchronizedList(new ArrayList<>());
    final List<Login> logins = Collections.synchronizedList(new ArrayList<>());
    final AtomicInteger pending = new AtomicInteger(), peakPending = new AtomicInteger();
    final AtomicLong peakHeap = new AtomicLong();
    final ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor();
    final Map<String, Object> report = new LinkedHashMap<>();
    final List<Map<String, Object>> phases = new ArrayList<>();
    final BanCinematicPlugin plugin = mock(BanCinematicPlugin.class);
    final DatabaseManager db;
    final BanStorage bans;
    final MuteStorage mutes;
    final IdentityStorage identities;
    final PunishmentLogger history;
    final BanManager banManager;
    final MuteManager muteManager;
    long moderationSequence = 10_000_000;

    SqliteLoadTest(Path directory, boolean large) throws Exception {
        this(directory, large, null);
    }

    SqliteLoadTest(Path directory, boolean large, YamlConfiguration backendConfig) throws Exception {
        this.directory = directory;
        this.large = large;
        players = large ? 100_000 : 10_000;
        banned = large ? 20_000 : 1_000;
        muted = large ? 10_000 : 500;
        regularHistory = large ? 1_000_000 : 100_000;
        heavyHistory = large ? 100_000 : 10_000;
        Files.createDirectories(directory);
        if (Files.exists(directory.resolve("bancinematic.db"))) throw new IllegalArgumentException("Use a new output directory.");
        var config = backendConfig == null ? new YamlConfiguration() : backendConfig;
        if (backendConfig == null) config.set("database.type", "SQLITE");
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        var logger = Logger.getLogger("sqlite-load");
        logger.setLevel(Level.OFF);
        when(plugin.getLogger()).thenReturn(logger);
        when(plugin.databaseExecutor()).thenReturn(observed);
        when(observed.submit(any())).thenAnswer(call -> submit(kind.get(), call.getArgument(0)));
        when(observed.execute(any())).thenAnswer(call -> submit(kind.get(), () -> { call.<Runnable>getArgument(0).run(); return null; }));
        db = new DatabaseManager(plugin);
        db.initialize();
        bans = new BanStorage(plugin, db);
        mutes = new MuteStorage(plugin, db);
        identities = new IdentityStorage(plugin, db);
        history = new PunishmentLogger(plugin, db);
        banManager = new BanManager(plugin, bans, history, observed);
        muteManager = new MuteManager(plugin, mutes, history, observed);
        clock.scheduleAtFixedRate(() -> peakHeap.accumulateAndGet(heap(), Math::max), 0, 20, TimeUnit.MILLISECONDS);
    }

    static UUID id(long number) { return new UUID(0x0123456789abcdefL, number + 1); }
    static String name(long number) { return "Load" + String.format(Locale.ROOT, "%06d", number); }
    static long heap() { return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(); }
    static long retainedHeap() throws Exception { System.gc(); Thread.sleep(150); return heap(); }

    <T> CompletableFuture<T> submit(String tag, Supplier<T> action) {
        long enqueue = System.nanoTime();
        peakPending.accumulateAndGet(pending.incrementAndGet(), Math::max);
        var future = worker.submit(() -> {
            long start = System.nanoTime();
            boolean failed = true;
            try { T result = action.get(); failed = Boolean.FALSE.equals(result) || result == PunishmentResult.STORAGE_ERROR; return result; }
            finally {
                samples.add(new Sample(tag, start - enqueue, System.nanoTime() - start, failed));
            }
        });
        future.whenComplete((result, error) -> pending.decrementAndGet());
        return future;
    }

    <T> T tagged(String tag, Supplier<T> action) {
        String before = kind.get(); kind.set(tag);
        try { return action.get(); } finally { kind.set(before); }
    }

    void seed() throws Exception {
        long start = System.nanoTime();
        System.out.println("SEED " + (large ? "large" : "small"));
        db.transaction(connection -> {
            try (var statement = connection.prepareStatement("INSERT INTO player_names VALUES (?, ?, ?, ?)")) {
                for (int i = 0; i <= players; i++) {
                    statement.setString(1, name(i).toLowerCase(Locale.ROOT));
                    statement.setString(2, id(i).toString());
                    statement.setString(3, name(i));
                    statement.setLong(4, 1_735_689_600_000L);
                    statement.addBatch();
                    if (i % 2000 == 1999) statement.executeBatch();
                }
                statement.executeBatch();
            }
            for (String table : List.of("ban_records", "mute_records")) {
                int count = table.equals("ban_records") ? banned : muted;
                try (var statement = connection.prepareStatement("INSERT INTO " + table + " VALUES (?, ?, 'seed reason', 'LOAD_STAFF', ?)")) {
                    for (int i = 0; i < count; i++) {
                        statement.setString(1, id(i).toString()); statement.setString(2, name(i));
                        if (i % 2 == 0) statement.setNull(3, Types.BIGINT);
                        else statement.setLong(3, Instant.now().plusSeconds(31_536_000).toEpochMilli());
                        statement.addBatch();
                        if (i % 2000 == 1999) statement.executeBatch();
                    }
                    statement.executeBatch();
                }
            }
            try (var statement = connection.prepareStatement("INSERT INTO punishment_history VALUES (?, ?, 'KICK', ?, 'seed reason', 'LOAD_STAFF', ?, NULL)")) {
                for (int i = 0; i < regularHistory + heavyHistory; i++) {
                    int subject = i < regularHistory ? i % players : players;
                    statement.setString(1, new UUID(0x1123456789abcdefL, i + 1).toString());
                    statement.setString(2, id(subject).toString()); statement.setString(3, name(subject));
                    statement.setString(4, "2025-01-01T00:00:00.000000000Z");
                    statement.addBatch();
                    if (i % 2000 == 1999) statement.executeBatch();
                    if (i % 100_000 == 99_999) System.out.println("seeded history " + (i + 1));
                }
                statement.executeBatch();
            }
            return null;
        });
        report.put("dataset", Map.of("players", players + 1, "activeBans", banned, "activeMutes", muted,
                "history", regularHistory + heavyHistory, "heavyPlayerHistory", heavyHistory,
                "seedSeconds", (System.nanoTime() - start) / 1e9, "databaseBytes", Files.size(directory.resolve("bancinematic.db"))));
        report.put("pragmas", db.execute(connection -> {
            var values = new LinkedHashMap<String, Object>();
            try (var statement = connection.createStatement()) {
                for (String setting : List.of("journal_mode", "synchronous", "busy_timeout", "wal_autocheckpoint", "page_size")) {
                    try (var result = statement.executeQuery("PRAGMA " + setting)) { result.next(); values.put(setting, result.getString(1)); }
                }
            }
            return values;
        }));
        report.put("plans", db.execute(connection -> {
            var plans = new LinkedHashMap<String, Object>();
            try (var statement = connection.createStatement()) {
                for (var query : Map.of(
                        "ban", "SELECT * FROM ban_records WHERE subject_uuid='" + id(1) + "' AND (expires_at IS NULL OR expires_at>0)",
                        "history", "SELECT * FROM punishment_history WHERE subject_uuid='" + id(players) + "' ORDER BY punished_at DESC,id DESC LIMIT 8 OFFSET " + (heavyHistory - 8),
                        "suggest", "SELECT DISTINCT name FROM ban_records WHERE name LIKE 'Load01%' ESCAPE '!' AND (expires_at IS NULL OR expires_at>0) ORDER BY name LIMIT 100").entrySet()) {
                    var rows = new ArrayList<String>();
                    try (var result = statement.executeQuery("EXPLAIN QUERY PLAN " + query.getValue())) { while (result.next()) rows.add(result.getString("detail")); }
                    plans.put(query.getKey(), rows);
                }
            }
            return plans;
        }));
    }

    CompletableFuture<Void> login(int sequence) {
        boolean shouldBan = sequence % 10 == 0;
        int subject = shouldBan ? sequence % banned : banned + sequence % (players - banned);
        UUID uuid = id(subject);
        long start = System.nanoTime();
        var query = submit("prelogin", () -> bans.get(uuid));
        var expired = new AtomicBoolean();
        var timeout = clock.schedule(() -> { if (!query.isDone()) { expired.set(true); query.cancel(false); } }, 15, TimeUnit.SECONDS);
        query.whenComplete((ignored, error) -> timeout.cancel(false));
        return query.thenCompose(ban -> {
            if ((ban != null) != shouldBan) throw new AssertionError("Incorrect ban outcome");
            if (ban != null) return CompletableFuture.completedFuture("banned");
            return submit("join_recheck", () -> { if (bans.get(uuid) != null) throw new AssertionError("Unexpected ban"); return null; })
                    .thenCompose(ignored -> tagged("mute_load", () -> muteManager.loadOnline(uuid)))
                    .thenCompose(ignored -> tagged("identity_write", () -> identities.recordAsync(observed, uuid, name(subject))))
                    .thenApply(saved -> { if (!saved) throw new AssertionError("Identity write failed"); return "allowed"; });
        }).handle((outcome, error) -> {
            muteManager.forgetOnline(uuid);
            logins.add(new Login(error == null ? outcome : expired.get() ? "timeout" : "error", System.nanoTime() - start));
            if (error != null && !expired.get()) System.out.println("LOGIN_ERROR " + error);
            return null;
        });
    }

    CompletableFuture<?> staff(int sequence, boolean heavy) {
        if (heavy) return submit("history_deep", () -> history.getHistoryPage(id(players), heavyHistory - 8, 8));
        int subject = banned + sequence % (players - banned);
        return switch (sequence % 10) {
            case 0, 1, 2, 3, 4 -> submit("history_first", () -> history.getHistoryPage(id(subject), 0, 8));
            case 5, 6 -> submit("check", () -> { var identity = identities.get(name(subject)); bans.get(identity.uuid()); mutes.get(identity.uuid()); return null; });
            case 7, 8 -> submit("suggest", () -> bans.suggestNames(sequence % 20 == 7 ? "" : "Load01"));
            default -> {
                UUID target = id(++moderationSequence);
                yield tagged("ban_write", () -> banManager.ban(target, "Moderated", "load test", "LOAD_STAFF"))
                        .thenCompose(result -> {
                            if (result != PunishmentResult.SUCCESS) throw new AssertionError("Ban failed: " + result);
                            return tagged("unban_write", () -> banManager.unban(target, "Moderated", "LOAD_STAFF"));
                        }).thenAccept(result -> { if (result != PunishmentResult.SUCCESS) throw new AssertionError("Unban failed: " + result); });
            }
        };
    }

    void begin() throws Exception {
        worker.execute(() -> {}).get(60, TimeUnit.SECONDS);
        samples.clear(); logins.clear(); peakPending.set(0); peakHeap.set(retainedHeap());
    }

    void finish(String name, long start, List<CompletableFuture<?>> requests) throws Exception {
        long enqueued = System.nanoTime();
        CompletableFuture.allOf(requests.toArray(CompletableFuture[]::new)).get(90, TimeUnit.SECONDS);
        worker.execute(() -> {}).get(60, TimeUnit.SECONDS);
        var phase = new LinkedHashMap<String, Object>();
        phase.put("name", name); phase.put("offeredSeconds", (enqueued - start) / 1e9);
        phase.put("totalSeconds", (System.nanoTime() - start) / 1e9);
        phase.put("requests", requests.size()); phase.put("peakOutstandingSqlTasks", peakPending.get());
        phase.put("peakHeapBytes", peakHeap.get()); phase.put("retainedHeapBytes", retainedHeap());
        phase.put("identityCacheEntries", identities.cachedCount());
        var outcomes = new TreeMap<String, Long>();
        for (var login : logins) outcomes.merge(login.outcome(), 1L, Long::sum);
        phase.put("loginOutcomes", outcomes);
        phase.put("loginFullPipelineMs", stats(logins.stream().mapToLong(Login::totalNs).toArray()));
        var groups = new TreeMap<String, List<Sample>>();
        for (var sample : samples) groups.computeIfAbsent(sample.kind(), ignored -> new ArrayList<>()).add(sample);
        var metrics = new TreeMap<String, Object>();
        groups.forEach((tag, rows) -> metrics.put(tag, Map.of("count", rows.size(), "failed", rows.stream().filter(Sample::failed).count(),
                "waitMs", stats(rows.stream().mapToLong(Sample::waitNs).toArray()),
                "workMs", stats(rows.stream().mapToLong(Sample::workNs).toArray()),
                "totalMs", stats(rows.stream().mapToLong(row -> row.waitNs() + row.workNs()).toArray()))));
        phase.put("sqlOperations", metrics);
        phases.add(phase);
        Files.writeString(directory.resolve(name + ".json"), new GsonBuilder().setPrettyPrinting().create().toJson(phase));
        System.out.println("PHASE " + name + " " + phase.get("loginOutcomes") + " tasks=" + samples.size() + " seconds=" + phase.get("totalSeconds"));
    }

    static Map<String, Object> stats(long[] values) {
        Arrays.sort(values);
        if (values.length == 0) return Map.of("count", 0);
        return Map.of("count", values.length, "p50", percentile(values, .5), "p95", percentile(values, .95),
                "p99", percentile(values, .99), "max", values[values.length - 1] / 1e6);
    }
    static double percentile(long[] values, double percentile) { return values[Math.max(0, (int)Math.ceil(values.length * percentile) - 1)] / 1e6; }

    void micro() throws Exception {
        begin(); long start = System.nanoTime(); var requests = new ArrayList<CompletableFuture<?>>();
        for (int i = 0; i < 100; i++) {
            submit("ban_hit", () -> bans.get(id(1))).get();
            submit("ban_miss", () -> bans.get(id(players - 1))).get();
            submit("history_first", () -> history.getHistoryPage(id(players - 1), 0, 8)).get();
            submit("history_deep", () -> history.getHistoryPage(id(players), heavyHistory - 8, 8)).get();
            submit("suggest_empty", () -> bans.suggestNames("")).get();
            submit("suggest_prefix", () -> bans.suggestNames("Load01")).get();
        }
        finish("micro_serial", start, requests);
    }

    void identityWalk() throws Exception {
        begin(); long start = System.nanoTime();
        for (int i = 0; i < 5000; i++) {
            final int subject = i;
            var identity = submit("identity_read", () -> identities.get(name(subject))).get();
            if (!identity.uuid().equals(id(subject))) throw new AssertionError("Incorrect identity");
        }
        if (identities.cachedCount() != 1024) throw new AssertionError("Identity cache exceeded or missed its bound");
        finish("identity_cache_walk_5000", start, List.of());
    }

    void steady(String label, int seconds, int joinsPerSecond, int staffPerSecond) throws Exception {
        begin(); long start = System.nanoTime(); var requests = new ArrayList<CompletableFuture<?>>();
        int ticksPerSecond = 100;
        for (int tick = 0; tick < seconds * ticksPerSecond; tick++) {
            if (tick % (ticksPerSecond / joinsPerSecond) == 0) requests.add(login(tick / (ticksPerSecond / joinsPerSecond)));
            if (tick % (ticksPerSecond / staffPerSecond) == 0) requests.add(staff(tick / (ticksPerSecond / staffPerSecond), false));
            long due = start + (tick + 1) * 10_000_000L;
            while (System.nanoTime() < due) java.util.concurrent.locks.LockSupport.parkNanos(due - System.nanoTime());
        }
        finish(label, start, requests);
    }

    void burst(String label, int joins, int admin, boolean heavy) throws Exception {
        begin(); long start = System.nanoTime(); var requests = new ArrayList<CompletableFuture<?>>();
        for (int i = 0; i < admin; i++) requests.add(staff(i, heavy));
        for (int i = 0; i < joins; i++) requests.add(login(i));
        finish(label, start, requests);
    }

    void lockedWriter() throws Exception {
        begin(); long start = System.nanoTime(); var requests = new ArrayList<CompletableFuture<?>>();
        try (Connection lock = db.connect(); Statement statement = lock.createStatement()) {
            statement.execute("BEGIN IMMEDIATE");
            var release = clock.schedule(() -> { try { statement.execute("ROLLBACK"); } catch (SQLException error) { throw new RuntimeException(error); } }, 18, TimeUnit.SECONDS);
            try {
                for (int i = 0; i < 4; i++) requests.add(submit("locked_write", () -> history.log(PunishmentType.KICK, id(players - 1), "Load", "lock", "LOAD_STAFF", null)));
                for (int i = 0; i < 30; i++) requests.add(login(i));
                finish("external_writer_lock_18s", start, requests);
                release.get(10, TimeUnit.SECONDS);
            } finally { if (!release.isDone()) { release.cancel(false); statement.execute("ROLLBACK"); } }
        }
        report.put("integrityAfterContention", db.execute(connection -> {
            try (var statement = connection.createStatement(); var result = statement.executeQuery("PRAGMA integrity_check")) { result.next(); return result.getString(1); }
        }));
    }

    void run() throws Exception {
        report.put("java", System.getProperty("java.version")); report.put("processorsVisibleToJvm", Runtime.getRuntime().availableProcessors());
        report.put("maxHeapBytes", Runtime.getRuntime().maxMemory()); report.put("sqliteVersion", org.sqlite.SQLiteJDBCLoader.getVersion());
        seed();
        report.put("heapBeforeTrafficBytes", retainedHeap());
        for (int i = 0; i < 50; i++) { bans.get(id(i)); history.getHistoryPage(id(players - 1), 0, 8); }
        identityWalk();
        micro();
        steady("steady_5joins_2staff_per_second", 20, 5, 2);
        steady("steady_25joins_10staff_per_second", 20, 25, 10);
        burst("burst_100joins_25staff", 100, 25, false);
        burst("burst_500joins_100staff", 500, 100, false);
        burst("flood_500deep_history_then_100joins", 100, 500, true);
        if (large) lockedWriter();
        report.put("phases", phases);
        report.put("finalDatabaseBytes", Files.size(directory.resolve("bancinematic.db")));
        report.put("completed", true);
        Files.writeString(directory.resolve("results.json"), new GsonBuilder().setPrettyPrinting().create().toJson(report));
        System.out.println("COMPLETE " + directory);
    }

    public static void main(String[] args) throws Exception {
        var test = new SqliteLoadTest(Path.of(args[0]), args[1].equals("large"));
        try { test.run(); }
        finally { test.clock.shutdownNow(); test.worker.close(); test.db.close(); }
    }
}
