package br.com.bancinematic.util;

import br.com.bancinematic.BanCinematicPlugin;

import java.util.UUID;

public final class PlayerIdentity {
    private PlayerIdentity() {}

    public static UUID resolveKnown(BanCinematicPlugin plugin, String name) {
        if (name == null || name.isBlank()) return null;
        return plugin.identities().resolve(name);
    }
}
