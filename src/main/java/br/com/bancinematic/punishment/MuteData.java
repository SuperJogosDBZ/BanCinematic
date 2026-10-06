package br.com.bancinematic.punishment;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record MuteData(
        UUID uniqueId,
        String name,
        String reason,
        String source,
        Instant expires
) {
    public MuteData {
        Objects.requireNonNull(uniqueId, "A UUID do jogador é obrigatória para um mute.");
    }

    public boolean expired() { return expires != null && !Instant.now().isBefore(expires); }
}
