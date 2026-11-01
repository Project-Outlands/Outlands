package net.minecraftforge.common.config;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.minecraftforge.fml.relauncher.Side;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the seam that lets the config subsystem run without a launched game, and the guard that every declared format
 * has a codec behind it.
 *
 * <p>Config code used to reach into {@code Loader} for its directory, class loader and phase. That made the whole
 * subsystem untestable and unusable outside the game - the five call sites were the entire coupling. Injecting the
 * environment is what turns those into arguments.
 */
class ConfigEnvironmentTest {

    @AfterEach
    void reset() {
        ConfigManager.setEnvironment(null);
    }

    @Config(modid = "mymod")
    static class TestConfig {
        public static int count = 1;
    }

    private static ConfigEnvironment environment(Path root, ClassLoader loader) {
        return new ConfigEnvironment() {
            @Override
            public File configDir() {
                return root.resolve("config").toFile();
            }

            @Override
            public ClassLoader modClassLoader() {
                return loader;
            }

            @Override
            public boolean loading() {
                return true;
            }

            @Override
            public Side side() {
                return Side.CLIENT;
            }
        };
    }

    /**
     * The point of the seam: a directory, a class loader and a phase are enough to load a config file. Nothing here
     * touches the loader, so this runs as an ordinary test.
     */
    @Test
    void theEnvironmentSuppliesTheDirectoryAConfigIsReadFrom(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("config"));
        Files.write(root.resolve("config/mymod.cfg"),
                "general {\n    I:count=5\n}\n".getBytes(StandardCharsets.UTF_8));

        ClassLoader loader = getClass().getClassLoader();
        ConfigManager.setEnvironment(environment(root, loader));

        Configuration cfg = ConfigManager.getConfiguration(TestConfig.class.getAnnotation(Config.class));

        assertEquals(5, cfg.getCategory("general").get("count").getInt(),
                "the file in the injected directory is the one that was read");
    }

    /** Two environments must not share a cache entry, or a test could see another one's configuration. */
    @Test
    void eachConfigDirectoryGetsItsOwnConfiguration(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("a/config"));
        Files.createDirectories(root.resolve("b/config"));
        Files.write(root.resolve("a/config/mymod.cfg"), "general {\n    I:count=1\n}\n".getBytes(StandardCharsets.UTF_8));
        Files.write(root.resolve("b/config/mymod.cfg"), "general {\n    I:count=2\n}\n".getBytes(StandardCharsets.UTF_8));

        ClassLoader loader = getClass().getClassLoader();

        ConfigManager.setEnvironment(environment(root.resolve("a"), loader));
        Configuration first = ConfigManager.getConfiguration(TestConfig.class.getAnnotation(Config.class));

        ConfigManager.setEnvironment(environment(root.resolve("b"), loader));
        Configuration second = ConfigManager.getConfiguration(TestConfig.class.getAnnotation(Config.class));

        assertEquals(1, first.getCategory("general").get("count").getInt());
        assertEquals(2, second.getCategory("general").get("count").getInt());
        assertSame(Config.Format.CFG, Config.Format.byExtension("cfg"));
    }

    /**
     * A format in the enum with no codec would be written back as CFG, which looks like it worked. Adding a value to
     * {@link Config.Format} has to break here first.
     */
    @Test
    void everyDeclaredFormatHasACodec() {
        for (Config.Format format : Config.Format.values()) {
            if (format == Config.Format.CFG)
                continue; // handled by Configuration itself; see ConfigCodecs.forFormat
            assertTrue(ConfigCodecs.forFormat(format) != null, "no codec for " + format);
        }
        assertEquals(null, ConfigCodecs.forFormat(Config.Format.CFG));
    }
}
