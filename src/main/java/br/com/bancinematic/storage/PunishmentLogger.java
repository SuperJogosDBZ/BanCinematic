package br.com.bancinematic.storage;

import br.com.bancinematic.BanCinematicPlugin;
import br.com.bancinematic.punishment.PunishmentType;
import br.com.bancinematic.punishment.ExecutorIdentity;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Persistent punishment history stored in SQL. */
public final class PunishmentLogger {
    private static final DateTimeFormatter HISTORY_DATE_FORMAT =
            new DateTimeFormatterBuilder().appendInstant(9).toFormatter();

    private final BanCinematicPlugin plugin;
    private final DatabaseManager database;

    public PunishmentLogger(BanCinematicPlugin plugin, DatabaseManager database) {
        this.plugin = plugin;
        this.database = database;
    }

    public boolean log(PunishmentType type, UUID playerId, String player,
                       String reason, String source, ExecutorIdentity executor, String duration) {
        Objects.requireNonNull(playerId, "A UUID do jogador é obrigatória no histórico.");
        Objects.requireNonNull(executor, "O executor é obrigatório no histórico.");
        Instant punishedAt = Instant.now();
        try (Connection connection = database.connect();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO punishment_history "
                             + "(id, subject_uuid, punishment_type, player, reason, source, executor_uuid, executor_name, punished_at, duration)"
                             + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            statement.setString(1, UUID.randomUUID().toString());
            statement.setString(2, playerId.toString());
            statement.setString(3, type.name());
            statement.setString(4, player);
            statement.setString(5, reason);
            statement.setString(6, source);
            if (executor.uniqueId() == null) statement.setNull(7, java.sql.Types.VARCHAR);
            else statement.setString(7, executor.uniqueId().toString());
            statement.setString(8, executor.name());
            statement.setString(9, HISTORY_DATE_FORMAT.format(punishedAt));
            statement.setString(10, duration);
            statement.executeUpdate();
            return true;
        } catch (SQLException exception) {
            plugin.getLogger().severe("Não foi possível salvar o histórico de punições: " + exception.getMessage());
            return false;
        }
    }

    public HistoryPage getHistoryPage(UUID playerId, long offset, int limit) {
        Objects.requireNonNull(playerId, "A UUID do jogador é obrigatória para consultar o histórico.");
        if (offset < 0) throw new IllegalArgumentException("O deslocamento do histórico não pode ser negativo.");
        if (limit < 1) throw new IllegalArgumentException("O tamanho da página deve ser positivo.");

        String countSql = "SELECT COUNT(*) FROM punishment_history WHERE subject_uuid = ?";
        String pageSql = "SELECT id, subject_uuid, punishment_type, player, reason, source, executor_uuid, executor_name, punished_at, duration"
                + " FROM punishment_history WHERE subject_uuid = ?"
                + " ORDER BY punished_at DESC, id DESC LIMIT ? OFFSET ?";
        List<HistoryEntry> entries = new ArrayList<>();
        try (Connection connection = database.connect()) {
            long totalEntries;
            try (PreparedStatement count = connection.prepareStatement(countSql)) {
                count.setString(1, playerId.toString());
                try (ResultSet result = count.executeQuery()) {
                    totalEntries = result.next() ? result.getLong(1) : 0;
                }
            }
            if (offset >= totalEntries) return new HistoryPage(totalEntries, List.of());

            try (PreparedStatement statement = connection.prepareStatement(pageSql)) {
                statement.setString(1, playerId.toString());
                statement.setInt(2, limit);
                statement.setLong(3, offset);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        UUID uuid = parseUuid(result.getString("subject_uuid"));
                        if (uuid == null) continue;
                        entries.add(new HistoryEntry(
                                result.getString("id"),
                                uuid,
                                result.getString("punishment_type"),
                                result.getString("player"),
                                result.getString("reason"),
                                result.getString("source"),
                                parseUuid(result.getString("executor_uuid")),
                                result.getString("executor_name"),
                                result.getString("punished_at"),
                                result.getString("duration")
                        ));
                    }
                }
            }
            return new HistoryPage(totalEntries, entries);
        } catch (SQLException exception) {
            plugin.getLogger().severe("Não foi possível consultar o histórico de punições: " + exception.getMessage());
            return new HistoryPage(0, List.of());
        }
    }

    private static UUID parseUuid(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public record HistoryPage(long totalEntries, List<HistoryEntry> entries) {
        public HistoryPage {
            if (totalEntries < 0) throw new IllegalArgumentException("O total de registros não pode ser negativo.");
            entries = List.copyOf(entries);
        }
    }

    public record HistoryEntry(
            String id,
            UUID uniqueId,
            String type,
            String player,
            String reason,
            String source,
            UUID executorUniqueId,
            String executorName,
            String date,
            String duration
    ) {
        public HistoryEntry {
            Objects.requireNonNull(uniqueId, "A UUID é obrigatória em cada registro de histórico.");
            Objects.requireNonNull(executorName, "O nome do executor é obrigatório em cada registro de histórico.");
        }
    }
}
