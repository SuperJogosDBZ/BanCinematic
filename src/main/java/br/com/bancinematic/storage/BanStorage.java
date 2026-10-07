package br.com.bancinematic.storage;

import br.com.bancinematic.BanCinematicPlugin;
import br.com.bancinematic.punishment.BanData;
import br.com.bancinematic.punishment.PunishmentType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BanStorage {
    private final BanCinematicPlugin plugin;
    private final DatabaseManager database;
    private final Map<UUID, BanData> bans = new ConcurrentHashMap<>();

    public BanStorage(BanCinematicPlugin plugin, DatabaseManager database) {
        this.plugin = plugin;
        this.database = database;
        load();
    }

    private synchronized void load() {
        bans.clear();
        int ignoredWithoutUuid = 0;
        try (Connection connection = database.connect();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT subject_uuid, name, reason, source, expires_at FROM ban_records");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                UUID uuid = parseUuid(result.getString("subject_uuid"));
                if (uuid == null) {
                    ignoredWithoutUuid++;
                    continue;
                }
                long expiry = result.getLong("expires_at");
                Instant expires = result.wasNull() ? null : Instant.ofEpochMilli(expiry);
                bans.put(uuid, new BanData(uuid, result.getString("name"), result.getString("reason"),
                        result.getString("source"), expires));
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("Não foi possível carregar bans do banco de dados.", exception);
        }
        if (ignoredWithoutUuid > 0) {
            plugin.getLogger().warning(ignoredWithoutUuid
                    + " registro(s) antigo(s) de ban sem UUID foram ignorados.");
        }
    }

    public BanData get(UUID uuid) {
        if (uuid == null) return null;
        BanData data = bans.get(uuid);
        return data != null && data.expired() ? null : data;
    }

    public boolean isBanned(UUID uuid) { return get(uuid) != null; }

    public synchronized boolean put(BanData data) {
        UUID uuid = data.uniqueId();
        try (Connection connection = database.connect()) {
            connection.setAutoCommit(false);
            try {
                replace(connection, data);
                connection.commit();
            } catch (SQLException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            plugin.getLogger().severe("Não foi possível salvar o ban no banco; alteração cancelada: "
                    + exception.getMessage());
            return false;
        }
        bans.put(uuid, data);
        return true;
    }

    public synchronized boolean putAndLog(BanData data, PunishmentType type, String duration,
                                          PunishmentLogger history) {
        UUID uuid = data.uniqueId();
        try (Connection connection = database.connect()) {
            connection.setAutoCommit(false);
            try {
                replace(connection, data);
                history.log(connection, type, uuid, data.name(), data.reason(), data.source(), duration);
                connection.commit();
            } catch (SQLException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            plugin.getLogger().severe("Não foi possível salvar o ban e seu histórico: "
                    + exception.getMessage());
            return false;
        }
        bans.put(uuid, data);
        return true;
    }

    public synchronized boolean remove(UUID uuid) {
        if (uuid == null) return false;
        try (Connection connection = database.connect()) {
            delete(connection, uuid);
        } catch (SQLException exception) {
            plugin.getLogger().severe("Não foi possível remover o ban do banco: " + exception.getMessage());
            return false;
        }
        bans.remove(uuid);
        return true;
    }

    public synchronized boolean removeAndLog(UUID uuid, String player, String reason, String source,
                                             PunishmentType type, PunishmentLogger history) {
        if (uuid == null) return false;
        try (Connection connection = database.connect()) {
            connection.setAutoCommit(false);
            try {
                delete(connection, uuid);
                history.log(connection, type, uuid, player, reason, source, null);
                connection.commit();
            } catch (SQLException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            plugin.getLogger().severe("Não foi possível remover o ban e salvar seu histórico: "
                    + exception.getMessage());
            return false;
        }
        bans.remove(uuid);
        return true;
    }

    public synchronized void cleanupExpired() {
        long now = Instant.now().toEpochMilli();
        try (Connection connection = database.connect();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM ban_records WHERE expires_at IS NOT NULL AND expires_at <= ?")) {
            statement.setLong(1, now);
            statement.executeUpdate();
            bans.entrySet().removeIf(entry -> entry.getValue().expired());
        } catch (SQLException exception) {
            plugin.getLogger().severe("Não foi possível limpar bans expirados: " + exception.getMessage());
        }
    }

    private static void rollback(Connection connection, SQLException original) {
        try {
            connection.rollback();
        } catch (SQLException exception) {
            original.addSuppressed(exception);
        }
    }

    private static void replace(Connection connection, BanData data) throws SQLException {
        UUID uuid = data.uniqueId();
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM ban_records WHERE subject_uuid = ?")) {
            delete.setString(1, uuid.toString());
            delete.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO ban_records (subject_uuid, name, reason, source, expires_at)"
                        + " VALUES (?, ?, ?, ?, ?)")) {
            statement.setString(1, uuid.toString());
            statement.setString(2, data.name());
            statement.setString(3, data.reason());
            statement.setString(4, data.source());
            if (data.expires() == null) statement.setNull(5, java.sql.Types.BIGINT);
            else statement.setLong(5, data.expires().toEpochMilli());
            statement.executeUpdate();
        }
    }

    private static void delete(Connection connection, UUID uuid) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM ban_records WHERE subject_uuid = ?")) {
            statement.setString(1, uuid.toString());
            statement.executeUpdate();
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
