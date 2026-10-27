import java.nio.file.*;
import java.sql.*;
import java.util.*;
import com.google.gson.GsonBuilder;

/** Read-only calibration of the authorized database; never prints credentials. */
public final class MysqlInspect {
    public static Connection connect() throws Exception {
        return connect(false);
    }

    public static Connection connect(boolean bulkSeed) throws Exception {
        Class.forName("com.mysql.cj.jdbc.Driver");
        var properties = new Properties();
        properties.setProperty("user", System.getenv("BANCINEMATIC_TEST_MYSQL_USERNAME"));
        properties.setProperty("password", System.getenv("BANCINEMATIC_TEST_MYSQL_PASSWORD"));
        if (bulkSeed) properties.setProperty("rewriteBatchedStatements", "true");
        String host = System.getenv("BANCINEMATIC_TEST_MYSQL_HOST");
        String database = System.getenv("BANCINEMATIC_TEST_MYSQL_DATABASE");
        return DriverManager.getConnection("jdbc:mysql://" + host + ":3306/" + database
                + "?useSSL=false&serverTimezone=UTC&characterEncoding=UTF-8&connectTimeout=5000&socketTimeout=10000", properties);
    }

    public static void main(String[] args) throws Exception {
        var result = new LinkedHashMap<String, Object>();
        try (var connection = connect()) {
            result.put("serverVersion", connection.getMetaData().getDatabaseProductVersion());
            try (var statement = connection.createStatement(); var rows = statement.executeQuery(
                    "SELECT COALESCE(SUM(data_length+index_length),0),COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()")) {
                rows.next(); result.put("existingDatabaseBytes", rows.getLong(1)); result.put("existingTables", rows.getInt(2));
            }
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT @@max_allowed_packet,@@max_user_connections")) {
                rows.next(); result.put("maxAllowedPacket", rows.getLong(1)); result.put("maxUserConnections", rows.getLong(2));
            }
            var ping = new ArrayList<Double>(); var select = new ArrayList<Double>();
            for (int i = 0; i < 12; i++) {
                long start = System.nanoTime(); if (!connection.isValid(2)) throw new IllegalStateException("Connection invalid");
                ping.add((System.nanoTime()-start)/1e6); start = System.nanoTime();
                try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT 1")) { rows.next(); }
                select.add((System.nanoTime()-start)/1e6);
            }
            result.put("connectionValidationMs", ping); result.put("selectOneMs", select);
        }
        String json = new GsonBuilder().setPrettyPrinting().create().toJson(result);
        Files.writeString(Path.of(args[0]), json); System.out.println(json);
    }
}
