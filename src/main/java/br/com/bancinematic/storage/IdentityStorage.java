package br.com.bancinematic.storage;

import br.com.bancinematic.BanCinematicPlugin;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class IdentityStorage {
    private final BanCinematicPlugin plugin;
    private final DatabaseManager database;

    public IdentityStorage(BanCinematicPlugin plugin, DatabaseManager database) {
        this.plugin = plugin;
        this.database = database;
    }

    public UUID resolve(String name) {
        Identity identity = get(name);
        return identity == null ? null : identity.uuid();
    }

    public CompletableFuture<Boolean> recordAsync(DatabaseExecutor executor, UUID uuid, String name) {
        Identity identity = new Identity(uuid, name, Instant.now());
        return executor.submit(() -> recordAt(identity)).exceptionally(error -> {
            plugin.getLogger().severe("Falha inesperada ao salvar a identidade de " + name + ": " + error);
            return false;
        });
    }

    public boolean record(UUID uuid, String name) {
        return recordAt(new Identity(uuid, name, Instant.now()));
    }

    private boolean recordAt(Identity identity) {
        try {
            database.execute(connection -> {
                String sql = "INSERT INTO player_names (normalized_name, player_uuid, name, last_seen) VALUES (?, ?, ?, ?) "
                        + (database.isSqlite()
                        ? "ON CONFLICT(normalized_name) DO UPDATE SET player_uuid=excluded.player_uuid, name=excluded.name, last_seen=excluded.last_seen"
                        : "ON DUPLICATE KEY UPDATE player_uuid=?, name=?, last_seen=?");
                try (PreparedStatement statement = connection.prepareStatement(database.sql(sql))) {
                    statement.setString(1, normalize(identity.name()));
                    statement.setString(2, identity.uuid().toString());
                    statement.setString(3, identity.name());
                    statement.setLong(4, identity.lastSeen().toEpochMilli());
                    if (!database.isSqlite()) {
                        statement.setString(5, identity.uuid().toString());
                        statement.setString(6, identity.name());
                        statement.setLong(7, identity.lastSeen().toEpochMilli());
                    }
                    statement.executeUpdate();
                }
                return null;
            });
            return true;
        } catch (SQLException error) {
            plugin.getLogger().severe("Não foi possível salvar a identidade de " + identity.name() + ": " + error.getMessage());
            return false;
        }
    }

    public Identity get(String name) {
        if (name == null || name.isBlank() || name.length() > 64) return null;
        String key = normalize(name);
        try {
            return database.execute(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(database.sql(
                        "SELECT player_uuid, name, last_seen FROM player_names WHERE normalized_name = ?"))) {
                    statement.setString(1, key);
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next()) return null;
                        return new Identity(UUID.fromString(result.getString("player_uuid")),
                                result.getString("name"), Instant.ofEpochMilli(result.getLong("last_seen")));
                    }
                }
            });
        } catch (SQLException error) {
            throw new IllegalStateException("Não foi possível consultar a identidade do jogador.", error);
        }
    }

    public record Identity(UUID uuid, String name, Instant lastSeen) {}
    private static String normalize(String name) { return name.toLowerCase(Locale.ROOT); }
}
