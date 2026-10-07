package br.com.bancinematic.storage;

import br.com.bancinematic.BanCinematicPlugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class IdentityStorage {
    private final BanCinematicPlugin plugin;
    private final DatabaseManager database;
    private final Map<String, UUID> names = new ConcurrentHashMap<>();

    public IdentityStorage(BanCinematicPlugin plugin, DatabaseManager database) {
        this.plugin = plugin;
        this.database = database;
        load();
    }

    public synchronized void load() {
        names.clear();
        try (Connection connection = database.connect();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT normalized_name, player_uuid FROM player_names");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                UUID uuid = parseUuid(result.getString("player_uuid"));
                if (uuid != null) names.put(result.getString("normalized_name"), uuid);
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Não foi possível carregar as identidades de jogadores.", exception);
        }
    }

    public UUID resolve(String name) {
        if (name == null || name.isBlank()) return null;
        return names.get(normalize(name));
    }

    public CompletableFuture<Boolean> recordAsync(DatabaseExecutor executor, UUID uuid, String name) {
        names.put(normalize(name), uuid);
        return executor.submit(() -> record(uuid, name)).exceptionally(error -> {
            plugin.getLogger().severe("Falha inesperada ao salvar a identidade de " + name + ": " + error);
            return false;
        });
    }

    public synchronized boolean record(UUID uuid, String name) {
        String normalized = normalize(name);
        long now = Instant.now().toEpochMilli();
        try (Connection connection = database.connect()) {
            connection.setAutoCommit(false);
            try {
                updateOrInsertName(connection, uuid, name, normalized, now);
                connection.commit();
            } catch (SQLException exception) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackException) {
                    exception.addSuppressed(rollbackException);
                }
                throw exception;
            }
        } catch (SQLException exception) {
            plugin.getLogger().severe("Não foi possível salvar a identidade de " + name + ": "
                    + exception.getMessage());
            return false;
        }
        names.put(normalized, uuid);
        return true;
    }

    private static void updateOrInsertName(Connection connection, UUID uuid, String name,
                                           String normalized, long now) throws SQLException {
        int updated;
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE player_names SET player_uuid = ?, name = ?, last_seen = ? WHERE normalized_name = ?")) {
            statement.setString(1, uuid.toString());
            statement.setString(2, name);
            statement.setLong(3, now);
            statement.setString(4, normalized);
            updated = statement.executeUpdate();
        }
        if (updated == 0) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO player_names (normalized_name, player_uuid, name, last_seen) VALUES (?, ?, ?, ?)")) {
                statement.setString(1, normalized);
                statement.setString(2, uuid.toString());
                statement.setString(3, name);
                statement.setLong(4, now);
                statement.executeUpdate();
            }
        }
    }

    private static UUID parseUuid(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String normalize(String name) { return name.toLowerCase(Locale.ROOT); }
}
