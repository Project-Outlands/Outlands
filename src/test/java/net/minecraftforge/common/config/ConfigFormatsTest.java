package net.minecraftforge.common.config;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers reading and writing the non-CFG formats.
 *
 * <p>These formats exist so a config can be written the way people actually write documents. That only helps if a key
 * means the same thing in all of them, so the cases here are about the model surviving the trip: a nested table becomes
 * a category, a list stays a list, a string that looks like a number stays a string, and a file can be written, read back
 * and still describe the same settings.
 */
class ConfigFormatsTest {

    private static Configuration read(Path root, Config.Format format, String text) throws IOException {
        File file = format == null ? root.resolve("mymod.cfg").toFile()
                : root.resolve("mymod." + format.extension()).toFile();

        if (format != null)
            Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
        else
            Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));

        Configuration cfg = format == null ? new Configuration(file) : new Configuration(file, format);
        cfg.load();
        return cfg;
    }

    private static Property property(Configuration cfg, String category, String name) {
        assertTrue(cfg.hasCategory(category), "no category " + category + " in " + cfg.getCategoryNames());
        Property property = cfg.getCategory(category).get(name);
        assertNotNull(property, "no property " + category + "." + name);
        return property;
    }

    // ---- TOML --------------------------------------------------------------------------------------------------

    @Test
    void tomlTablesBecomeCategories(@TempDir Path root) throws IOException {
        Configuration cfg = read(root, Config.Format.TOML, ""
                + "[general]\n"
                + "count = 5\n"
                + "hardmode = true\n"
                + "name = \"plain\"\n"
                + "\n"
                + "[general.advanced]\n"
                + "ratio = 0.25\n");

        assertEquals(5, property(cfg, "general", "count").getInt());
        assertTrue(property(cfg, "general", "hardmode").getBoolean());
        assertEquals("plain", property(cfg, "general", "name").getString());
        assertEquals(0.25, property(cfg, "general.advanced", "ratio").getDouble(), 1e-9);
    }

    @Test
    void tomlArraysBecomeListProperties(@TempDir Path root) throws IOException {
        Configuration cfg = read(root, Config.Format.TOML, "[general]\nnames = [\"a\", \"b\"]\n");

        Property names = property(cfg, "general", "names");
        assertTrue(names.isList());
        assertEquals("a", names.getStringList()[0]);
        assertEquals(2, names.getStringList().length);
    }

    /** A dotted key inside a table is data, not nesting: the model has to keep it a single property name. */
    @Test
    void tomlQuotedKeyWithADotStaysOneProperty(@TempDir Path root) throws IOException {
        Configuration cfg = read(root, Config.Format.TOML, "[general]\n\"a.b\" = 7\n");

        assertEquals(7, property(cfg, "general", "a.b").getInt());
        assertNull(cfg.getCategory("general").get("a"), "the dot must not have become a nested table");
    }

    @Test
    void anArrayOfTablesIsRefusedInsteadOfGuessed(@TempDir Path root) throws IOException {
        Configuration cfg = read(root, Config.Format.TOML, "[[general.rule]]\nname = \"a\"\n");

        assertTrue(cfg.getCategoryNames().isEmpty(), "nothing may be invented for a structure the model cannot hold");
    }

    @Test
    void tomlRoundTripsThroughTheModel(@TempDir Path root) throws IOException {
        Configuration original = read(root, Config.Format.TOML, ""
                + "[general]\n"
                + "count = 5\n"
                + "names = [\"a\", \"b\"]\n"
                + "\n"
                + "[client]\n"
                + "enabled = false\n");

        original.getCategory("general").get("count").setComment("how many");

        File target = root.resolve("written.toml").toFile();
        Configuration copy = new Configuration(target, Config.Format.TOML);
        copy.get("general", "count", 1).setValue("5");
        copy.get("general", "names", new String[] { "a", "b" });
        copy.get("client", "enabled", true).setValue("false");
        copy.getCategory("general").get("count").setComment("how many");
        copy.save();

        String text = new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8);
        assertTrue(text.contains("# how many"), "comments come from the annotations, not the previous file: " + text);
        assertTrue(text.contains("names = [\"a\", \"b\"]"), text);

        Configuration reread = new Configuration(target, Config.Format.TOML);
        reread.load();
        assertEquals(5, property(reread, "general", "count").getInt());
        assertEquals("b", property(reread, "general", "names").getStringList()[1]);
        assertEquals("false", property(reread, "client", "enabled").getString());
    }

    /** A property legitimately named with a dot must be quoted on the way out, or it would reread as a table. */
    @Test
    void tomlWritesDottedPropertyNamesQuoted(@TempDir Path root) throws IOException {
        File target = root.resolve("mymod.toml").toFile();
        Configuration cfg = new Configuration(target, Config.Format.TOML);
        cfg.get("general", "a.b", 1).setValue("7");
        cfg.save();

        String text = new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8);
        assertTrue(text.contains("\"a.b\" = 7"), text);
    }

    // ---- YAML --------------------------------------------------------------------------------------------------

    @Test
    void yamlMappingsBecomeCategories(@TempDir Path root) throws IOException {
        Configuration cfg = read(root, Config.Format.YAML, ""
                + "general:\n"
                + "  count: 5\n"
                + "  hardmode: true\n"
                + "  advanced:\n"
                + "    ratio: 0.25\n");

        assertEquals(5, property(cfg, "general", "count").getInt());
        assertTrue(property(cfg, "general", "hardmode").getBoolean());
        assertEquals(0.25, property(cfg, "general.advanced", "ratio").getDouble(), 1e-9);
    }

    /** YAML 1.2 core schema: these are strings, and if they were not the same file would mean different things per mod. */
    @Test
    void yamlDoesNotTreatYesNoOnOffAsBooleans(@TempDir Path root) throws IOException {
        Configuration cfg = read(root, Config.Format.YAML, "general:\n  word: yes\n  other: off\n");

        assertEquals("yes", property(cfg, "general", "word").getString());
        assertEquals("off", property(cfg, "general", "other").getString());
    }

    @Test
    void yamlListsStayLists(@TempDir Path root) throws IOException {
        Configuration cfg = read(root, Config.Format.YAML, "general:\n  names:\n  - a\n  - b\n");

        assertTrue(property(cfg, "general", "names").isList());
        assertEquals(2, property(cfg, "general", "names").getStringList().length);
    }

    @Test
    void yamlRoundTripsThroughTheModel(@TempDir Path root) throws IOException {
        File target = root.resolve("mymod.yml").toFile();
        Configuration cfg = new Configuration(target, Config.Format.YAML);
        cfg.get("general", "count", 1).setValue("9");
        cfg.get("general", "names", new String[] { "x", "y" });
        cfg.get("client", "enabled", true).setValue("false");
        cfg.save();

        Configuration reread = new Configuration(target, Config.Format.YAML);
        reread.load();

        assertEquals(9, property(reread, "general", "count").getInt());
        assertEquals("x", property(reread, "general", "names").getStringList()[0]);
        assertEquals("false", property(reread, "client", "enabled").getString());
    }

    // ---- HOCON -------------------------------------------------------------------------------------------------

    @Test
    void hoconObjectsBecomeCategories(@TempDir Path root) throws IOException {
        Configuration cfg = read(root, Config.Format.HOCON, ""
                + "general {\n"
                + "  count = 5\n"
                + "  advanced { ratio = 0.25 }\n"
                + "}\n");

        assertEquals(5, property(cfg, "general", "count").getInt());
        assertEquals(0.25, property(cfg, "general.advanced", "ratio").getDouble(), 1e-9);
    }

    @Test
    void hoconRoundTripsThroughTheModel(@TempDir Path root) throws IOException {
        File target = root.resolve("mymod.conf").toFile();
        Configuration cfg = new Configuration(target, Config.Format.HOCON);
        cfg.get("general", "count", 1).setValue("3");
        cfg.save();

        Configuration reread = new Configuration(target, Config.Format.HOCON);
        reread.load();

        assertEquals(3, property(reread, "general", "count").getInt());
    }

    /**
     * HOCON can include other files and read the environment; dumping the parsed result would freeze those
     * substitutions into the file. So it is materialised once and then left alone.
     */
    @Test
    void anExistingHoconFileIsNeverRewritten(@TempDir Path root) throws IOException {
        File target = root.resolve("mymod.conf").toFile();
        String original = "# written by hand\ngeneral { count = 1 }\n";
        Files.write(target.toPath(), original.getBytes(StandardCharsets.UTF_8));

        Configuration cfg = new Configuration(target, Config.Format.HOCON);
        cfg.load();
        cfg.get("general", "count", 1).setValue("9");
        cfg.get("general", "later", 2).setValue("3");
        cfg.save();

        String text = new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8);
        assertEquals(original, text, "the file has to stay exactly as its author left it");
    }

    @Test
    void aMissingHoconFileIsMaterialised(@TempDir Path root) throws IOException {
        File target = root.resolve("mymod.conf").toFile();
        Configuration cfg = new Configuration(target, Config.Format.HOCON);
        cfg.get("general", "count", 4).setValue("4");
        cfg.save();

        assertTrue(target.isFile(), "the first run has to write something");
        assertTrue(new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8).contains("count = 4"));
    }

    // ---- CFG ---------------------------------------------------------------------------------------------------

    /** CFG is unchanged: same text, same prefixes, and it now also gets the atomic write. */
    @Test
    void cfgStillWritesItsOwnSyntax(@TempDir Path root) throws IOException {
        File target = root.resolve("mymod.cfg").toFile();
        Configuration cfg = new Configuration(target);
        cfg.get("general", "count", 1).setValue("5");
        cfg.save();

        String text = new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8);
        assertTrue(text.contains("I:count=5"), text);

        Configuration reread = new Configuration(target);
        reread.load();
        assertEquals(5, property(reread, "general", "count").getInt());
    }
}
