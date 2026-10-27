package br.com.bancinematic.storage;

import br.com.bancinematic.BanCinematicPlugin;
import br.com.bancinematic.punishment.BanData;
import br.com.bancinematic.punishment.PunishmentResult;
import br.com.bancinematic.punishment.PunishmentType;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public final class BanStorage {
    private final BanCinematicPlugin plugin;
    private final DatabaseManager database;
    private volatile long revision;

    public long revision() { return revision; }
    public BanStorage(BanCinematicPlugin plugin, DatabaseManager database) {
        this.plugin = plugin;
        this.database = database;
    }

    /** Consulta SQL; chamar somente fora da thread principal. */
    public BanData get(UUID uuid) {
        if (uuid == null) return null;
        return find(java.util.List.of(uuid)).get(uuid);
    }


    public Map<UUID, BanData> find(java.util.Collection<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        var keys = java.util.List.copyOf(ids);
        try {
            return database.execute(connection -> {
                Map<UUID, BanData> found = new java.util.HashMap<>();
                for (int offset = 0; offset < keys.size(); offset += 100) {
                    var batch = keys.subList(offset, Math.min(keys.size(), offset + 100));
                    String placeholders = String.join(",", java.util.Collections.nCopies(batch.size(), "?"));
                    try (PreparedStatement statement = connection.prepareStatement(database.sql(
                            "SELECT subject_uuid, name, reason, source, expires_at FROM ban_records"
                                    + " WHERE subject_uuid IN (" + placeholders + ")"
                                    + " AND (expires_at IS NULL OR expires_at > ?)"))) {
                        int index = 1;
                        for (UUID id : batch) statement.setString(index++, id.toString());
                        statement.setLong(index, Instant.now().toEpochMilli());
                        try (ResultSet result = statement.executeQuery()) {
                            while (result.next()) {
                                UUID id = UUID.fromString(result.getString("subject_uuid"));
                                long expiry = result.getLong("expires_at");
                                Instant expires = result.wasNull() ? null : Instant.ofEpochMilli(expiry);
                                found.put(id, new BanData(id, result.getString("name"), result.getString("reason"),
                                        result.getString("source"), expires));
                            }
                        }
                    }
                }
                return found;
            });
        } catch (SQLException error) {
            throw new IllegalStateException("Não foi possível consultar ban_records.", error);
        }
    }

    public java.util.List<String> suggestNames(String prefix) {
        if (prefix.length() > 64) return java.util.List.of();
        String escaped = prefix.replace("!", "!!").replace("%", "!%").replace("_", "!_");
        try {
            return database.execute(connection -> {
                var names = new java.util.ArrayList<String>();
                try (PreparedStatement statement = connection.prepareStatement(database.sql(
                        "SELECT DISTINCT name FROM ban_records WHERE name LIKE ? ESCAPE '!'"
                                + " AND (expires_at IS NULL OR expires_at > ?) ORDER BY name LIMIT 100"))) {
                    statement.setString(1, escaped + "%");
                    statement.setLong(2, Instant.now().toEpochMilli());
                    try (ResultSet result = statement.executeQuery()) {
                        while (result.next()) names.add(result.getString(1));
                    }
                }
                names.sort(String.CASE_INSENSITIVE_ORDER);
                return names;
            });
        } catch (SQLException error) {
            throw new IllegalStateException("Não foi possível sugerir nomes de ban_records.", error);
        }
    }

    public synchronized boolean put(BanData data) {
        return save(data, null, null, null) == PunishmentResult.SUCCESS;
    }

    public synchronized PunishmentResult putAndLog(BanData data, PunishmentType type, Long durationSeconds,
                                                    PunishmentLogger history) {
        return save(data, type, durationSeconds, history);
    }

    private PunishmentResult save(BanData data, PunishmentType type, Long durationSeconds, PunishmentLogger history) {
        try {
            PunishmentResult result = database.transaction(connection -> {
                try (PreparedStatement expired = connection.prepareStatement(database.sql(
                        "DELETE FROM ban_records WHERE subject_uuid = ? AND expires_at IS NOT NULL AND expires_at <= ?"))) {
                    expired.setString(1, data.uniqueId().toString());
                    expired.setLong(2, Instant.now().toEpochMilli());
                    expired.executeUpdate();
                }
                try {
                    insert(connection, data);
                } catch (SQLException error) {
                    if (!DatabaseManager.isDuplicateKey(error)) throw error;
                    connection.rollback();
                    return PunishmentResult.ALREADY_ACTIVE;
                }
                if (history != null) history.log(connection, type, data.uniqueId(), data.name(), data.reason(), data.source(), durationSeconds);
                return PunishmentResult.SUCCESS;
            });
            if (result == PunishmentResult.SUCCESS) revision++;
            return result;
        } catch (SQLException error) {
            plugin.getLogger().severe("Não foi possível salvar ban e histórico: " + error.getMessage());
            return PunishmentResult.STORAGE_ERROR;
        }
    }

    public synchronized PunishmentResult removeAndLog(UUID uuid, String player, String reason, String source,
                                                       PunishmentType type, PunishmentLogger history) {
        if (uuid == null) return PunishmentResult.NOT_ACTIVE;
        try {
            PunishmentResult result = database.transaction(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(database.sql(
                        "DELETE FROM ban_records WHERE subject_uuid = ? AND (expires_at IS NULL OR expires_at > ?)"))) {
                    statement.setString(1, uuid.toString());
                    statement.setLong(2, Instant.now().toEpochMilli());
                    if (statement.executeUpdate() == 0) return PunishmentResult.NOT_ACTIVE;
                }
                history.log(connection, type, uuid, player, reason, source, null);
                return PunishmentResult.SUCCESS;
            });
            revision++;
            return result;
        } catch (SQLException error) {
            plugin.getLogger().severe("Não foi possível remover ban e salvar histórico: " + error.getMessage());
            return PunishmentResult.STORAGE_ERROR;
        }
    }

    public synchronized void cleanupExpired() {
        try {
            database.execute(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(database.sql(
                        "DELETE FROM ban_records WHERE expires_at IS NOT NULL AND expires_at <= ?"))) {
                    statement.setLong(1, Instant.now().toEpochMilli());
                    statement.executeUpdate();
                }
                return null;
            });
        } catch (SQLException error) {
            plugin.getLogger().warning("Não foi possível limpar bans expirados: " + error.getMessage());
        }
    }

    private void insert(Connection connection, BanData data) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(database.sql(
                "INSERT INTO ban_records (subject_uuid, name, reason, source, expires_at) VALUES (?, ?, ?, ?, ?)"))) {
            statement.setString(1, data.uniqueId().toString());
            statement.setString(2, data.name());
            statement.setString(3, data.reason());
            statement.setString(4, data.source());
            if (data.expires() == null) statement.setNull(5, java.sql.Types.BIGINT);
            else statement.setLong(5, data.expires().toEpochMilli());
            statement.executeUpdate();
        }
    }
}
