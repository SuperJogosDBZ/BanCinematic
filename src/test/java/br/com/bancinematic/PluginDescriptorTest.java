package br.com.bancinematic;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginDescriptorTest {
    @Test
    void paperDescriptorDeclaresRuntimeLibrariesAndAllCommands() throws Exception {
        try (InputStream stream = getClass().getResourceAsStream("/plugin.yml")) {
            assertTrue(stream != null, "plugin.yml deve estar no artefato.");
            YamlConfiguration descriptor = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));

            assertEquals("1.21.11", descriptor.getString("api-version"));
            assertTrue(descriptor.getStringList("libraries").stream().anyMatch(library -> library.startsWith("org.xerial:sqlite-jdbc:")));
            assertTrue(descriptor.getStringList("libraries").stream().anyMatch(library -> library.startsWith("com.mysql:mysql-connector-j:")));
            assertEquals(Set.of("ban", "tempban", "unban", "mute", "tempmute", "unmute", "kick", "history", "check"),
                    descriptor.getConfigurationSection("commands").getKeys(false));
            assertEquals("bancinematic.check", descriptor.getString("commands.check.permission"));
            assertEquals("op", descriptor.getString("permissions.bancinematic.check.default"));
        }

        try (InputStream stream = getClass().getResourceAsStream("/config.yml")) {
            assertTrue(stream != null, "config.yml deve estar no artefato.");
            YamlConfiguration config = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
            assertFalse(config.contains("messages"), "As mensagens devem ficar fora do config.yml.");
        }

        YamlConfiguration portuguese = loadYaml("/language_br.yml");
        YamlConfiguration english = loadYaml("/language_en.yml");
        assertEquals(portuguese.getConfigurationSection("messages").getKeys(true),
                english.getConfigurationSection("messages").getKeys(true),
                "Os dois idiomas devem oferecer as mesmas chaves de mensagem.");
    }

    private YamlConfiguration loadYaml(String resourcePath) throws Exception {
        try (InputStream stream = getClass().getResourceAsStream(resourcePath)) {
            assertTrue(stream != null, resourcePath + " deve estar no artefato.");
            return YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
    }
}
