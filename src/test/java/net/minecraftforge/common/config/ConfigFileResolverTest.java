package net.minecraftforge.common.config;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers which file a {@code @Config} declaration ends up behind.
 *
 * <p>The rule used to be the expression {@code new File(configDir, name + ".cfg")}, written out at each of the three
 * places that needed it, so the answer could only ever be a file called {@code .cfg} and the three places could drift
 * apart. Now that the declaration can name a format, the interesting cases are the ones where the declaration and the
 * disk disagree - a format that was removed, a name that still carries its extension - because those decide whether a
 * user's existing settings are found again or quietly left behind next to a freshly written default file.
 */
class ConfigFileResolverTest {

    private static Config.Format formatOf(File file) {
        return Config.Format.byExtension(extensionOf(file.getName()));
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? null : name.substring(dot + 1);
    }

    @Test
    void aDeclarationWithoutAFormatStillMeansCfg(@TempDir Path dir) {
        ConfigFileResolver.Target target = ConfigFileResolver.resolve(dir.toFile(), "mymod", "", null);

        assertEquals(new File(dir.toFile(), "mymod.cfg"), target.file());
        assertEquals(Config.Format.CFG, target.format());
        assertEquals(ConfigFileResolver.Origin.DEFAULT, target.origin());
        assertTrue(target.ignored().isEmpty());
    }

    @Test
    void aDeclaredFormatDecidesTheExtension(@TempDir Path dir) {
        ConfigFileResolver.Target target = ConfigFileResolver.resolve(dir.toFile(), "mymod", "settings",
                Config.Format.TOML);

        assertEquals(new File(dir.toFile(), "settings.toml"), target.file());
        assertEquals(Config.Format.TOML, target.format());
        assertEquals(ConfigFileResolver.Origin.DECLARED, target.origin());
    }

    @Test
    void yamlIsWrittenWithItsPreferredSpelling(@TempDir Path dir) {
        assertEquals("mymod.yml", ConfigFileResolver.resolve(dir.toFile(), "mymod", "", Config.Format.YAML)
                .file().getName());
        assertEquals(Config.Format.YAML, Config.Format.byExtension("yaml"));
    }

    /**
     * The case this rule exists for: a mod that used to declare TOML and no longer does must keep reading the file the
     * user has been editing. Defaulting straight back to CFG would write a new file next to it and make every setting
     * look reset.
     */
    @Test
    void anExistingFileWinsOverTheDefaultFormat(@TempDir Path dir) throws IOException {
        File existing = Files.createFile(dir.resolve("mymod.toml")).toFile();

        ConfigFileResolver.Target target = ConfigFileResolver.resolve(dir.toFile(), "mymod", "", null);

        assertEquals(existing, target.file());
        assertEquals(Config.Format.TOML, target.format());
        assertEquals(ConfigFileResolver.Origin.EXISTING_FILE, target.origin());
    }

    /** An explicit format is the author's statement of intent and outranks whatever happens to be on disk. */
    @Test
    void aDeclaredFormatIgnoresAFileOfAnotherFormat(@TempDir Path dir) throws IOException {
        Files.createFile(dir.resolve("mymod.toml"));

        ConfigFileResolver.Target target = ConfigFileResolver.resolve(dir.toFile(), "mymod", "", Config.Format.CFG);

        assertEquals("mymod.cfg", target.file().getName());
        assertEquals(Config.Format.CFG, target.format());
        assertEquals(ConfigFileResolver.Origin.DECLARED, target.origin());
    }

    /**
     * Two formats on disk is a question only the user can answer, so the tie-break is deterministic (enum order, CFG
     * first) and the losers are reported rather than thrown away silently.
     */
    @Test
    void severalCandidatesAreBrokenByDeclarationOrderAndReported(@TempDir Path dir) throws IOException {
        Files.createFile(dir.resolve("mymod.toml"));
        Files.createFile(dir.resolve("mymod.json"));

        ConfigFileResolver.Target target = ConfigFileResolver.resolve(dir.toFile(), "mymod", "", null);

        assertEquals("mymod.toml", target.file().getName());
        assertEquals(List.of(new File(dir.toFile(), "mymod.json")), target.ignored());
    }

    /**
     * {@code name="x.cfg"} is the pre-format spelling and produced the file {@code x.cfg.cfg}. Stripping the extension
     * would start reading a different file, so the old spelling is kept - the author is warned instead.
     */
    @Test
    void aNameThatCarriesItsExtensionKeepsTheOldSpelling(@TempDir Path dir) {
        ConfigFileResolver.Target target = ConfigFileResolver.resolve(dir.toFile(), "mymod", "mymod.cfg", null);

        assertEquals("mymod.cfg.cfg", target.file().getName());
        assertEquals(Config.Format.CFG, target.format());
        assertEquals(ConfigFileResolver.Origin.LEGACY_NAME, target.origin());
    }

    @Test
    void noFormatIsImplicitFromALegacyName(@TempDir Path dir) {
        assertEquals("mymod.cfg.cfg",
                ConfigFileResolver.resolve(dir.toFile(), "mymod", "mymod.cfg", null).file().getName());
        assertEquals("mymod.cfg.toml",
                ConfigFileResolver.resolve(dir.toFile(), "mymod", "mymod.cfg", Config.Format.TOML).file().getName());
    }

    @Test
    void aDotInTheMiddleOfANameIsNotAnExtension(@TempDir Path dir) {
        ConfigFileResolver.Target target = ConfigFileResolver.resolve(dir.toFile(), "mymod", "mymod.server", null);

        assertEquals("mymod.server.cfg", target.file().getName());
        assertEquals(ConfigFileResolver.Origin.DEFAULT, target.origin());
    }

    /** YAML has two spellings; a file written as {@code .yaml} must be recognised, not duplicated as {@code .yml}. */
    @Test
    void eitherYamlSpellingResolvesToTheSameFile(@TempDir Path dir) throws IOException {
        File existing = Files.createFile(dir.resolve("mymod.yaml")).toFile();

        ConfigFileResolver.Target target = ConfigFileResolver.resolve(dir.toFile(), "mymod", "", null);

        assertEquals(existing, target.file());
        assertEquals(Config.Format.YAML, target.format());
    }

    @Test
    void anEmptyNameFallsBackToTheModId(@TempDir Path dir) {
        assertSame(Config.Format.CFG, ConfigFileResolver.resolve(dir.toFile(), "mymod", "", null).format());
        assertEquals("mymod.cfg", ConfigFileResolver.resolve(dir.toFile(), "mymod", "", null).file().getName());
        assertEquals("mymod.cfg", ConfigFileResolver.resolve(dir.toFile(), "mymod", null, null).file().getName());
    }

    @Test
    void everyFormatKnowsTheExtensionItIsFoundBy() {
        for (Config.Format format : Config.Format.values()) {
            assertEquals(format, Config.Format.byExtension(format.extension()));
            assertEquals(format, formatOf(new File("x." + format.extension())));
        }
        assertEquals(Config.Format.CFG, Config.Format.byExtension("CFG"));
        assertEquals(null, Config.Format.byExtension("properties"));
        assertEquals(null, Config.Format.byExtension(null));
    }
}
