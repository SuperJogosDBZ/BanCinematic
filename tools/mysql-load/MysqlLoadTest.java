import br.com.bancinematic.punishment.*;
import br.com.bancinematic.storage.*;
import com.google.gson.GsonBuilder;
import org.bukkit.configuration.file.YamlConfiguration;
import java.nio.file.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Moderate remote-MySQL workload only: no locks, query floods or extreme bursts. */
public final class MysqlLoadTest {
    private static final List<String> TABLES = List.of("ban_records", "mute_records", "player_names", "punishment_history");
    final SqliteLoadTest test;
    final Path output;
    final String prefix;

    MysqlLoadTest(Path output) throws Exception {
        this.output = output;
        Files.createDirectories(output);
        Path state = output.resolve("state.json");
        if (Files.exists(state)) throw new IllegalArgumentException("Use a new output directory");
        prefix = "bc_lm_2710_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8) + "_";
        try (var connection = MysqlInspect.connect(); var statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN (?,?,?,?)")) {
            int parameter = 1; for (String table : TABLES) statement.setString(parameter++, prefix + table);
            try (var rows = statement.executeQuery()) { rows.next(); if (rows.getInt(1) != 0) throw new IllegalStateException("Prefix already exists"); }
        }
        Files.writeString(state, new GsonBuilder().setPrettyPrinting().create().toJson(Map.of("prefix", prefix, "ownedTables", TABLES.stream().map(table -> prefix + table).toList())));
        var config = new YamlConfiguration();
        config.set("database.type", "MYSQL"); config.set("database.table-prefix", prefix);
        config.set("database.mysql.host", System.getenv("BANCINEMATIC_TEST_MYSQL_HOST"));
        config.set("database.mysql.database", System.getenv("BANCINEMATIC_TEST_MYSQL_DATABASE"));
        config.set("database.mysql.username", System.getenv("BANCINEMATIC_TEST_MYSQL_USERNAME"));
        config.set("database.mysql.password", System.getenv("BANCINEMATIC_TEST_MYSQL_PASSWORD"));
        test = new SqliteLoadTest(output, false, config);
    }

    void seed() throws Exception {
        long start = System.nanoTime();
        System.out.println("SEED moderate MySQL");
        try (var connection = MysqlInspect.connect(true)) {
            connection.setAutoCommit(false);
            try {
                try (var statement = connection.prepareStatement(test.db.sql("INSERT INTO player_names VALUES (?,?,?,?)"))) {
                    for (int i = 0; i <= test.players; i++) {
                        statement.setString(1, SqliteLoadTest.name(i).toLowerCase(Locale.ROOT));
                        statement.setString(2, SqliteLoadTest.id(i).toString()); statement.setString(3, SqliteLoadTest.name(i));
                        statement.setLong(4, 1_735_689_600_000L); statement.addBatch();
                        if (i % 1000 == 999) { statement.executeBatch(); statement.clearBatch(); }
                    }
                    statement.executeBatch();
                }
                for (String table : List.of("ban_records", "mute_records")) {
                    int count = table.equals("ban_records") ? test.banned : test.muted;
                    try (var statement = connection.prepareStatement(test.db.sql("INSERT INTO " + table + " VALUES (?,?,'seed reason','LOAD_STAFF',?)"))) {
                        for (int i = 0; i < count; i++) {
                            statement.setString(1, SqliteLoadTest.id(i).toString()); statement.setString(2, SqliteLoadTest.name(i));
                            if (i % 2 == 0) statement.setNull(3, Types.BIGINT);
                            else statement.setLong(3, Instant.now().plusSeconds(31_536_000).toEpochMilli());
                            statement.addBatch();
                        }
                        statement.executeBatch();
                    }
                }
                try (var statement = connection.prepareStatement(test.db.sql(
                        "INSERT INTO punishment_history VALUES (?,?,'KICK',?,'seed reason','LOAD_STAFF','2025-01-01T00:00:00.000000000Z',NULL)"))) {
                    for (int i = 0; i < test.regularHistory + test.heavyHistory; i++) {
                        int subject = i < test.regularHistory ? i % test.players : test.players;
                        statement.setString(1, new UUID(0x1123456789abcdefL, i + 1).toString());
                        statement.setString(2, SqliteLoadTest.id(subject).toString()); statement.setString(3, SqliteLoadTest.name(subject));
                        statement.addBatch();
                        if (i % 1000 == 999) { statement.executeBatch(); statement.clearBatch(); }
                        if (i % 10_000 == 9_999) System.out.println("seeded history " + (i + 1));
                    }
                    statement.executeBatch();
                }
                // Fixtures for the real Paper phases, using offline-mode UUIDs.
                for (int i = 0; i < 40; i++) {
                    String name = String.format(Locale.ROOT, "SqlLoad%04d", i);
                    UUID id = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    try (var statement = connection.prepareStatement(test.db.sql("INSERT INTO player_names VALUES (?,?,?,?)"))) {
                        statement.setString(1, name.toLowerCase(Locale.ROOT)); statement.setString(2, id.toString());
                        statement.setString(3, name); statement.setLong(4, 1_735_689_600_000L); statement.executeUpdate();
                    }
                    if (i % 10 <= 2) {
                        String table = i % 10 == 0 ? "ban_records" : "mute_records";
                        try (var statement = connection.prepareStatement(test.db.sql("INSERT INTO " + table + " VALUES (?,?,'seed bot punishment','LOAD_STAFF',NULL)"))) {
                            statement.setString(1, id.toString()); statement.setString(2, name); statement.executeUpdate();
                        }
                    }
                }
                connection.commit();
            } catch (Exception error) { connection.rollback(); throw error; }
            try (var statement = connection.createStatement()) {
                for (String table : TABLES) try (var rows = statement.executeQuery("ANALYZE TABLE " + prefix + table)) { while (rows.next()) {} }
            }
        }
        test.report.put("dataset", Map.of("identities", test.players + 1 + 40, "bans", test.banned + 4,
                "mutes", test.muted + 8, "history", test.regularHistory + test.heavyHistory,
                "seedSeconds", (System.nanoTime() - start) / 1e9));
        System.out.println("SEED_COMPLETE");
    }

    void micro() throws Exception {
        test.begin(); long start = System.nanoTime();
        for (int i = 0; i < 8; i++) {
            test.submit("ban_hit", () -> test.bans.get(SqliteLoadTest.id(1))).get();
            test.submit("ban_miss", () -> test.bans.get(SqliteLoadTest.id(test.players - 1))).get();
            test.submit("history_first", () -> test.history.getHistoryPage(SqliteLoadTest.id(test.players - 1), 0, 8)).get();
            test.submit("check", () -> { test.identities.get(SqliteLoadTest.name(8000)); test.bans.get(SqliteLoadTest.id(8000)); test.mutes.get(SqliteLoadTest.id(8000)); return null; }).get();
            test.submit("suggest_prefix", () -> test.bans.suggestNames("Load00")).get();
        }
        test.finish("micro_serial", start, List.of());
    }

    void paced(String label, int seconds, double joinsPerSecond, double staffPerSecond) throws Exception {
        test.begin(); long start = System.nanoTime(); var requests = new ArrayList<CompletableFuture<?>>();
        int joins = 0, staff = 0;
        for (int tick = 0; tick < seconds * 20; tick++) {
            int wantedJoins = (int)Math.floor((tick + 1) * joinsPerSecond / 20 + 1e-9);
            int wantedStaff = (int)Math.floor((tick + 1) * staffPerSecond / 20 + 1e-9);
            while (joins < wantedJoins) requests.add(test.login(joins++));
            while (staff < wantedStaff) requests.add(test.staff(staff++, false));
            long due = start + (tick + 1) * 50_000_000L;
            while (System.nanoTime() < due) java.util.concurrent.locks.LockSupport.parkNanos(due - System.nanoTime());
        }
        test.finish(label, start, requests);
    }

    void run() throws Exception {
        seed();
        test.report.put("backend", "MySQL connector, remote MariaDB"); test.report.put("prefix", prefix);
        test.report.put("heapBeforeTrafficBytes", SqliteLoadTest.retainedHeap());
        micro();
        paced("steady_0_5joins_0_25staff_per_second", 20, .5, .25);
        paced("steady_1join_0_5staff_per_second", 20, 1, .5);
        var previous = test.phases.get(test.phases.size() - 1);
        var loginStats = (Map<?, ?>) ((Map<?, ?>)previous.get("sqlOperations")).get("prelogin");
        var total = (Map<?, ?>)loginStats.get("totalMs");
        double p95 = ((Number)total.get("p95")).doubleValue();
        if (p95 < 3000 && !((Map<?, ?>)previous.get("loginOutcomes")).containsKey("timeout")) {
            test.burst("moderate_burst_10joins_2staff", 10, 2, false);
        } else test.report.put("burstSkipped", "Queue already delayed at sustained load; no larger ramp requested.");
        test.report.put("phases", test.phases); test.report.put("completed", true);
        Files.writeString(output.resolve("results.json"), new GsonBuilder().setPrettyPrinting().create().toJson(test.report));
        System.out.println("MYSQL_COMPLETE " + output);
    }

    public static void main(String[] args) throws Exception {
        var load = new MysqlLoadTest(Path.of(args[0]));
        try { load.run(); }
        finally { load.test.clock.shutdownNow(); load.test.worker.close(); load.test.db.close(); }
    }
}
