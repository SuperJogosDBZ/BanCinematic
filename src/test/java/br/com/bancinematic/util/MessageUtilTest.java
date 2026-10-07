package br.com.bancinematic.util;

import br.com.bancinematic.BanCinematicPlugin;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MessageUtilTest {
    private MessageUtil messages;

    @BeforeEach
    void setUp() {
        YamlConfiguration serverFile = new YamlConfiguration();
        serverFile.set("messages.custom", "&aTexto do servidor");

        YamlConfiguration jarDefaults = new YamlConfiguration();
        jarDefaults.set("messages.custom", "&7Texto padrão do JAR");
        jarDefaults.set("messages.reason-too-long", "&cMáximo de %max% caracteres.");
        jarDefaults.set("messages.ban-screen.title", "&c&lBANIDO");
        serverFile.setDefaults(jarDefaults);

        BanCinematicPlugin plugin = mock(BanCinematicPlugin.class);
        when(plugin.getLanguageConfig()).thenReturn(serverFile);
        messages = new MessageUtil(plugin);
    }

    @Test
    void textEditedByTheServerWinsOverTheJarDefault() {
        assertEquals("§aTexto do servidor", messages.get("messages.custom"));
    }

    @Test
    void keysMissingFromTheServerFileUseTheJarDefault() {
        assertEquals("§cMáximo de %max% caracteres.", messages.get("messages.reason-too-long"));
        assertEquals("&c&lBANIDO", messages.screen("messages.ban-screen", "", "", "", ""));
    }

    @Test
    void unknownKeysAreEmptyInsteadOfNull() {
        assertEquals("", messages.get("messages.nao-existe"));
        assertEquals("", messages.raw("messages.nao-existe"));
    }

    @Test
    void fallbackIsUsedOnlyWhenTheTextIsMissingOrBlank() {
        assertEquals("reserva", messages.raw("messages.nao-existe", "reserva"));
        assertEquals("&aTexto do servidor", messages.raw("messages.custom", "reserva"));
    }
}
