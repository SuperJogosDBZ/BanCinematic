package br.com.bancinematic.storage;

import br.com.bancinematic.BanCinematicPlugin;
import br.com.bancinematic.punishment.MuteData;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Active mutes backed by SQL and mirrored in memory for fast chat checks.
 * Reads only touch memory and never block; writes do database I/O and must run on
 * the database thread (see {@link DatabaseExecutor}).
 */
public final class MuteStorage {
    private final BanCinematicPlugin plugin;
    private final DatabaseManager database;
    private final Map<UUID, MuteData> mutes = new ConcurrentHashMap<>();

    public MuteStorage(BanCinematicPlugin plugin, DatabaseManager database) {
        this.plugin = plugin;
        this.database = database;
        load();
    }

    private synchronized void load() {
        mutes.clear();
        int ignoredWithoutUuid = 0;
        try (Connection connection = database.connect();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT subject_uuid, name, reason, source, expires_at FROM mute_records");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                UUID uuid = parseUuid(result.getString("subject_uuid"));
                if (uuid == null) {
                    ignoredWithoutUuid++;
                    continue;
                }
                long expiry = result.getLong("expires_at");
                Instant expires = result.wasNull() ? null : Instant.ofEpochMilli(expiry);
                mutes.put(uuid, new MuteData(uuid, result.getString("name"), result.getString("reason"),
                        result.getString("source"), expires));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Não foi possível carregar mutes do banco de dados.", exception);
        }
        if (ignoredWithoutUuid > 0) {
            plugin.getLogger().warning(ignoredWithoutUuid
                    + " registro(s) antigo(s) de mute sem UUID foram ignorados.");
        }
    }

    public MuteData get(UUID uuid) {
        if (uuid == null) return null;
        MuteData data = mutes.get(uuid);
        return data != null && data.expired() ? null : data;
    }

    public boolean isMuted(UUID uuid) { return get(uuid) != null; }

    public synchronized boolean put(MuteData data) {
        UUID uuid = data.uniqueId();
        try (Connection connection = database.connect()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement delete = connection.prepareStatement(
                        "DELETE FROM mute_records WHERE subject_uuid = ?")) {
                    delete.setString(1, uuid.toString());
                    delete.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO mute_records (subject_uuid, name, reason, source, expires_at)"
                                + " VALUES (?, ?, ?, ?, ?)")) {
                    statement.setString(1, uuid.toString());
                    statement.setString(2, data.name());
                    statement.setString(3, data.reason());
                    statement.setString(4, data.source());
                    if (data.expires() == null) statement.setNull(5, java.sql.Types.BIGINT);
                    else statement.setLong(5, data.expires().toEpochMilli());
                    statement.executeUpdate();
                }
                connection.commit();
            } catch (SQLException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            plugin.getLogger().severe("Não foi possível salvar o mute no banco; alteração cancelada: "
                    + exception.getMessage());
            return false;
        }
        mutes.put(uuid, data);
        return true;
    }

    public synchronized boolean remove(UUID uuid) {
        if (uuid == null) return false;
        try (Connection connection = database.connect();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM mute_records WHERE subject_uuid = ?")) {
            statement.setString(1, uuid.toString());
            statement.executeUpdate();
        } catch (SQLException exception) {
            plugin.getLogger().severe("Não foi possível remover o mute do banco: " + exception.getMessage());
            return false;
        }
        mutes.remove(uuid);
        return true;
    }

    public synchronized void cleanupExpired() {
        long now = Instant.now().toEpochMilli();
        try (Connection connection = database.connect();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM mute_records WHERE expires_at IS NOT NULL AND expires_at <= ?")) {
            statement.setLong(1, now);
            statement.executeUpdate();
            mutes.entrySet().removeIf(entry -> entry.getValue().expired());
        } catch (SQLException exception) {
            plugin.getLogger().severe("Não foi possível limpar mutes expirados: " + exception.getMessage());
        }
    }

    private static void rollback(Connection connection, SQLException original) {
        try {
            connection.rollback();
        } catch (SQLException exception) {
            original.addSuppressed(exception);
        }
    }

    private static UUID parseUuid(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }
}
