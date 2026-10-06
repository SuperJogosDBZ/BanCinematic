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

            assertEquals("2.0", descriptor.getString("version"));
            assertEquals("SuperJogosDBZ", descriptor.getString("author"));
            assertTrue(descriptor.getStringList("libraries").contains("org.xerial:sqlite-jdbc:3.53.4.0"));
            assertTrue(descriptor.getStringList("libraries").contains("com.mysql:mysql-connector-j:26.7.0"));
            assertEquals(Set.of("ban", "tempban", "unban", "mute", "tempmute", "unmute", "kick", "history"),
                    descriptor.getConfigurationSection("commands").getKeys(false));
        }

        try (InputStream stream = getClass().getResourceAsStream("/config.yml")) {
            assertTrue(stream != null, "config.yml deve estar no artefato.");
            YamlConfiguration config = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
            assertEquals("br", config.getString("language"));
            assertEquals(9.0, config.getDouble("cinematic.monster.distance"));
            assertEquals(5000, config.getInt("database.mysql.connect-timeout"));
            assertEquals(10000, config.getInt("database.mysql.socket-timeout"));
            assertFalse(config.contains("messages"), "As mensagens devem ficar fora do config.yml.");
        }

        YamlConfiguration portuguese = loadYaml("/language_br.yml");
        YamlConfiguration english = loadYaml("/language_en.yml");
        assertEquals(portuguese.getConfigurationSection("messages").getKeys(true),
                english.getConfigurationSection("messages").getKeys(true),
                "Os dois idiomas devem oferecer as mesmas chaves de mensagem.");
        assertEquals("&cJogador não existe.", portuguese.getString("messages.player-never-joined"));
        assertEquals("&cPlayer does not exist.", english.getString("messages.player-never-joined"));
    }

    private YamlConfiguration loadYaml(String resourcePath) throws Exception {
        try (InputStream stream = getClass().getResourceAsStream(resourcePath)) {
            assertTrue(stream != null, resourcePath + " deve estar no artefato.");
            return YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }
    }
}
