package br.com.bancinematic.punishment;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record BanData(
        UUID uniqueId,
        String name,
        String reason,
        String source,
        Instant expires
) {
    public BanData {
        Objects.requireNonNull(uniqueId, "A UUID do jogador é obrigatória para um banimento.");
    }

    public boolean permanent() { return expires == null; }
    public boolean expired() { return expires != null && !Instant.now().isBefore(expires); }
}
