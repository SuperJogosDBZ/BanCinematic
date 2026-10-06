package br.com.bancinematic.util;

import br.com.bancinematic.BanCinematicPlugin;

import java.util.UUID;

/** Resolves UUIDs only from identities recorded by this plugin. */
public final class PlayerIdentity {
    private PlayerIdentity() {}

    /**
     * Returns null when the plugin has not recorded this nickname. Online status
     * does not change identity resolution; joins populate the plugin's registry.
     */
    public static UUID resolveKnown(BanCinematicPlugin plugin, String name) {
        if (name == null || name.isBlank()) return null;
        return plugin.identities().resolve(name);
    }
}
