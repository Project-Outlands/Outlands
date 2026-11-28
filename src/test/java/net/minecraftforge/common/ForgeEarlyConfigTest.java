package net.minecraftforge.common;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the classic {@link Configuration} based early settings: the keys are camelCase, the values are read before
 * the loader exists, and a save/reload keeps them intact.
 */
class ForgeEarlyConfigTest {

    @AfterEach
    void reset() {
        ForgeConfig.reset();
        ForgeEarlyConfig.resetForTesting();
    }

    private static Path configDir(Path root) throws IOException {
        Path dir = root.resolve("config");
        Files.createDirectories(dir);
        return dir;
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
    }

    private static Property prop(Configuration cfg, String category, String key) {
        assertTrue(cfg.hasCategory(category), "no category " + category + " in " + cfg.getCategoryNames());
        Property p = cfg.getCategory(category).get(key);
        assertNotNull(p, "no property " + category + "." + key);
        return p;
    }

    @Test
    void syncCreatesCamelCaseKeysWithMetadata(@TempDir Path root) throws IOException {
        Path dir = configDir(root);
        ForgeEarlyConfig.loadEarly(dir.toFile());

        Configuration cfg = ForgeConfig.get(dir.toFile());
        assertTrue(cfg.hasCategory("early"));
        assertTrue(cfg.hasCategory("early.modern_windows_styles"));

        assertFalse(cfg.getCategory("early").containsKey("OPENGL_VERSION_MAJOR"), "old uppercase key must not exist");
        assertEquals(4, prop(cfg, "early", "openglVersionMajor").getInt());
        assertEquals(6, prop(cfg, "early", "openglVersionMinor").getInt());
        assertEquals("auto", prop(cfg, "early", "linuxVideoDriver").getString());
        assertEquals(1.0D, prop(cfg, "early", "inputScrollSpeed").getDouble(), 1e-9);
        assertEquals("FLAT", prop(cfg, "early", "modScanDepth").getString());
        String[] blacklist = prop(cfg, "early", "loadingPluginBlacklist").getStringList();
        assertEquals(9, blacklist.length);
        // Both wide-id core plugins stay out: this build supplies the ids itself (feat/wide-ids).
        assertTrue(java.util.Arrays.asList(blacklist).contains("org.dimdev.jeid.JEIDLoadingPlugin"));
        assertTrue(java.util.Arrays.asList(blacklist).contains("ru.fewizz.neid.asm.Plugin"));
        assertTrue(prop(cfg, "early", "openglVersionMajor").requiresMcRestart());
        assertFalse(prop(cfg, "early", "commandCompletion").requiresMcRestart());
        assertNotNull(prop(cfg, "early", "openglVersionMajor").getComment());
        assertEquals("forge.configgui.early.openglVersionMajor",
                prop(cfg, "early", "openglVersionMajor").getLanguageKey());
    }

    @Test
    void savingAndReloadingKeepsValues(@TempDir Path root) throws IOException {
        Path dir = configDir(root);
        ForgeEarlyConfig.loadEarly(dir.toFile());

        Configuration cfg = ForgeConfig.get(dir.toFile());
        prop(cfg, "early", "openglVersionMajor").setValue("3");
        prop(cfg, "early", "commandCompletion").setValue("true");
        ForgeEarlyConfig.sync(cfg, true);

        assertEquals(3, ForgeEarlyConfig.openglVersionMajor);
        assertTrue(ForgeEarlyConfig.commandCompletion);

        String text = Files.readString(cfg.getConfigFile().toPath());
        assertTrue(text.contains("early {"), text);
        assertTrue(text.contains("I:openglVersionMajor=3"), text);
        assertTrue(text.contains("B:commandCompletion=true"), text);

        ForgeConfig.reset();
        Configuration reread = new Configuration(cfg.getConfigFile());
        reread.load();
        assertEquals(3, prop(reread, "early", "openglVersionMajor").getInt());
        assertTrue(prop(reread, "early", "commandCompletion").getBoolean());
    }

    @Test
    void invalidScanDepthFallsBackToFlat(@TempDir Path root) throws IOException {
        Path dir = configDir(root);
        write(dir.resolve("forge.cfg"), "early {\n    S:modScanDepth=NONSENSE\n}\n");

        ForgeEarlyConfig.loadEarly(dir.toFile());

        assertEquals(ForgeEarlyConfig.ModScanDepth.FLAT, ForgeEarlyConfig.modScanDepth);
        assertEquals("FLAT", prop(ForgeConfig.get(dir.toFile()), "early", "modScanDepth").getString());
    }

    @Test
    void sharedConfigurationIsOneInstancePerFile(@TempDir Path root) throws IOException {
        Path dir = configDir(root);
        ForgeConfig.reset();

        Configuration first = ForgeConfig.get(dir.toFile());
        Configuration second = ForgeConfig.get(dir.toFile());
        Configuration third = ForgeConfig.get(new File(dir.toFile(), "."));

        assertTrue(first == second, "repeated get must return the same object");
        assertTrue(first == third, "the canonical path must be the same instance");
    }
}
