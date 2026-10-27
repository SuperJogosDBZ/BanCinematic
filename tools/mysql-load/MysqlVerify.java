import java.nio.file.*;
import java.sql.*;
import java.util.*;
import com.google.gson.*;

/** Verification and cleanup are restricted to the exact four tables owned by this run. */
public final class MysqlVerify {
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        var state = JsonParser.parseString(Files.readString(directory.resolve("state.json"))).getAsJsonObject();
        String prefix = state.get("prefix").getAsString();
        if (!prefix.matches("bc_lm_2710_[0-9a-f]{8}_")) throw new IllegalArgumentException("Not a load-test prefix");
        List<String> tables = List.of("ban_records", "mute_records", "player_names", "punishment_history");
        var owned = new HashSet<String>(); for (var value : state.getAsJsonArray("ownedTables")) owned.add(value.getAsString());
        if (!owned.equals(new HashSet<>(tables.stream().map(table -> prefix + table).toList()))) throw new IllegalArgumentException("Ownership mismatch");
        try (var connection = MysqlInspect.connect()) {
            if (args.length > 1 && args[1].equals("cleanup")) {
                try (var statement = connection.createStatement()) {
                    for (String table : tables) { statement.executeUpdate("DROP TABLE IF EXISTS " + prefix + table); System.out.println("Removed " + prefix + table); }
                }
                try (var statement = connection.prepareStatement("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN (?,?,?,?)")) {
                    for (int i = 0; i < tables.size(); i++) statement.setString(i + 1, prefix + tables.get(i));
                    try (var rows = statement.executeQuery()) { rows.next(); if (rows.getInt(1) != 0) throw new IllegalStateException("Cleanup incomplete"); }
                }
                Files.writeString(directory.resolve("cleanup.json"), "{\"removedOwnedTables\":4,\"remainingOwnedTables\":0}");
                return;
            }
            var result = new LinkedHashMap<String, Object>();
            var counts = new LinkedHashMap<String, Long>(); var checks = new LinkedHashMap<String, Object>();
            try (var statement = connection.createStatement()) {
                for (String table : tables) {
                    try (var rows = statement.executeQuery("SELECT COUNT(*) FROM " + prefix + table)) { rows.next(); counts.put(table, rows.getLong(1)); }
                    var messages = new ArrayList<String>();
                    try (var rows = statement.executeQuery("CHECK TABLE " + prefix + table + " QUICK")) {
                        while (rows.next()) messages.add(rows.getString("Msg_type") + ":" + rows.getString("Msg_text"));
                    }
                    checks.put(table, messages);
                }
                try (var rows = statement.executeQuery("SELECT COUNT(*) FROM " + prefix + "ban_records WHERE name='Moderated'")) {
                    rows.next(); result.put("activeTestModerations", rows.getLong(1));
                }
                var audit = new LinkedHashMap<String, Long>();
                try (var rows = statement.executeQuery("SELECT punishment_type,COUNT(*) FROM " + prefix + "punishment_history WHERE reason IN ('load test','Removido') GROUP BY punishment_type")) {
                    while (rows.next()) audit.put(rows.getString(1), rows.getLong(2));
                }
                result.put("moderationAudit", audit);
            }
            try (var statement = connection.prepareStatement("SELECT COALESCE(SUM(data_length+index_length),0) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN (?,?,?,?)")) {
                for (int i = 0; i < tables.size(); i++) statement.setString(i + 1, prefix + tables.get(i));
                try (var rows = statement.executeQuery()) { rows.next(); result.put("estimatedTableBytes", rows.getLong(1)); }
            }
            result.put("rows", counts); result.put("checks", checks);
            String json = new GsonBuilder().setPrettyPrinting().create().toJson(result);
            Files.writeString(directory.resolve("verification.json"), json); System.out.println(json);
        }
    }
}
