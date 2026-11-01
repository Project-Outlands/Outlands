package net.minecraftforge.common.config;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the {@code custom_config/} patch layer: a hand-written file that overrides entries without touching the
 * generated config file.
 *
 * <p>Two properties of this layer are what the whole design rests on, so they are what these cases pin. The patch must
 * win over the file it patches, and it must not end up <em>in</em> that file: a pack update that replaces {@code config/}
 * is survivable, but only if removing the patch also brings the previous value back. Everything else here (unknown keys,
 * type mismatches, the new-key case) is about the failure being loud instead of silent.
 */
class ConfigPatchTest {

    /** The layout the resolver produces: config/x.cfg patched by its sibling custom_config/x.cfg. */
    private static File base(Path root) {
        return root.resolve("config").resolve("mymod.cfg").toFile();
    }

    private static void writeCase(Path root, String baseText, String patchText) throws IOException {
        write(root.resolve("config"), "mymod.cfg", baseText);
        if (patchText != null)
            write(root.resolve(ConfigPatch.DIRECTORY_NAME), "mymod.cfg", patchText);
    }

    private static void write(Path dir, String name, String text) throws IOException {
        Files.createDirectories(dir);
        Files.write(dir.resolve(name), text.getBytes(StandardCharsets.UTF_8));
    }

    private static Configuration load(Path root) {
        File file = base(root);
        Configuration cfg = new Configuration(file);
        cfg.load();
        cfg.setPatchLayer(ConfigPatch.load(root.resolve("config").toFile(), file));
        return cfg;
    }

    /** Mirrors what {@code ConfigManager} does when the annotation model reaches a property. */
    private static Property apply(Configuration cfg, String category, String name) {
        Property property = cfg.getCategory(category).get(name);
        ConfigPatch.applyTo(cfg, category, property);
        return property;
    }

    private static final String BASE_COUNT = "general {\n    I:count=5\n}\n";

    @Test
    void thePatchWinsOverTheFileButIsNotWrittenIntoIt(@TempDir Path root) throws IOException {
        writeCase(root, BASE_COUNT, "general {\n    I:count=9\n}\n");

        Configuration cfg = load(root);
        Property count = apply(cfg, "general", "count");

        assertEquals(9, count.getInt(), "the patch is what the game runs with");
        assertEquals("5", count.getSavedString(), "the file's own value has to survive");

        cfg.save();
        String saved = new String(Files.readAllBytes(base(root).toPath()), StandardCharsets.UTF_8);
        assertTrue(saved.contains("I:count=5"), "save must write the file's own value, not the patch: " + saved);
    }

    @Test
    void aKeyTheFileDoesNotHaveYetIsStillPatched(@TempDir Path root) throws IOException {
        // The mod added the key after the file was written; the patch has to reach it, and the default it materialises
        // must be the one that gets saved - otherwise removing the patch would leave the patched value behind.
        writeCase(root, "general {\n    I:count=5\n}\n", "general {\n    I:added=7\n}\n");

        Configuration cfg = load(root);
        Property added = cfg.getCategory("general").get("added");
        assertNull(added, "the file genuinely has no such key");

        added = new Property("added", (String) null, Property.Type.INTEGER);
        added.setDefaultValue("3");
        cfg.getCategory("general").put("added", added);
        ConfigPatch.applyTo(cfg, "general", added);

        assertEquals(7, added.getInt());
        assertEquals("3", added.getSavedString(), "the value the file would have materialised is what gets saved");
    }

    @Test
    void anUnknownKeyIsIgnoredAndDoesNotCreateAProperty(@TempDir Path root) throws IOException {
        writeCase(root, BASE_COUNT, "general {\n    I:cunt=9\n}\n");

        Configuration cfg = load(root);

        assertNull(cfg.getCategory("general").get("cunt"), "a typo must not invent an entry");
        assertEquals(5, cfg.getCategory("general").get("count").getInt());
        assertFalse(cfg.getCategory("general").get("count").isPatched());
    }

    @Test
    void aValueOfTheWrongTypeIsIgnoredInsteadOfBlowingUpLater(@TempDir Path root) throws IOException {
        writeCase(root, BASE_COUNT, "general {\n    I:count=not-a-number\n}\n");

        Property count = apply(load(root), "general", "count");

        assertEquals(5, count.getInt(), "the file's value keeps winning over an unusable patch value");
        assertFalse(count.isPatched());
    }

    @Test
    void aListPatchDoesNotApplyToOneScalar(@TempDir Path root) throws IOException {
        writeCase(root, BASE_COUNT, "general {\n    I:count <\n    9\n    8\n     >\n}\n");

        Property count = apply(load(root), "general", "count");

        assertEquals(5, count.getInt());
        assertFalse(count.isPatched());
    }

    @Test
    void listValuesArePatchedAsAWhole(@TempDir Path root) throws IOException {
        writeCase(root, "general {\n    S:names <\n    a\n    b\n     >\n}\n",
                "general {\n    S:names <\n    x\n     >\n}\n");

        Property names = apply(load(root), "general", "names");

        assertEquals("x", names.getStringList()[0]);
        assertEquals(1, names.getStringList().length);
        assertEquals(2, names.getSavedStringList().length, "the file's own list has to survive for the writer");
    }

    @Test
    void aPatchUnderAnotherCategoryStaysThere(@TempDir Path root) throws IOException {
        writeCase(root, BASE_COUNT, "client {\n    I:count=9\n}\n");

        Configuration cfg = load(root);
        ConfigPatch.applyTo(cfg, "general", cfg.getCategory("general").get("count"));

        assertEquals(5, cfg.getCategory("general").get("count").getInt(),
                "a category is part of the key; the patch says nothing about general");
    }

    @Test
    void nestedCategoriesAreAddressable(@TempDir Path root) throws IOException {
        writeCase(root, "general {\n    sub {\n        I:count=5\n    }\n}\n",
                "general {\n    sub {\n        I:count=9\n    }\n}\n");

        Property count = apply(load(root), "general.sub", "count");

        assertEquals(9, count.getInt());
        assertEquals("5", count.getSavedString());
    }

    @Test
    void withoutAPatchFileNothingHappens(@TempDir Path root) throws IOException {
        writeCase(root, BASE_COUNT, null);

        File file = base(root);
        assertNull(ConfigPatch.load(root.resolve("config").toFile(), file), "no custom_config means no layer at all");

        Configuration cfg = load(root);
        assertEquals(5, cfg.getCategory("general").get("count").getInt());
    }

    @Test
    void thePatchDirectoryIsASiblingOfConfig(@TempDir Path root) {
        File configDir = root.resolve("config").toFile();
        assertEquals(new File(root.toFile(), "custom_config/mymod.cfg"), ConfigPatch.fileFor(configDir, base(root)));
    }

    /**
     * {@code @Config(name = "sub/name")} has always put the file in a subdirectory, because the resolver joins the name
     * onto {@code config/} as a path. The patch has to follow that path rather than the bare file name, or every
     * {@code quality.cfg} in the tree would share one patch file.
     */
    @Test
    void aSubdirectoryConfigGetsItsOwnPatchInTheSameShape(@TempDir Path root) {
        File configDir = root.resolve("config").toFile();
        File quality = new File(configDir, "sodium/quality.cfg");
        File shadows = new File(configDir, "shaders/quality.cfg");

        assertEquals(new File(root.toFile(), "custom_config/sodium/quality.cfg"),
                ConfigPatch.fileFor(configDir, quality));
        assertEquals(new File(root.toFile(), "custom_config/shaders/quality.cfg"),
                ConfigPatch.fileFor(configDir, shadows),
                "same file name, different directory: they must not share a patch");
    }

    /** A file the resolver put outside {@code config/} is not part of a pack's tree, so nothing patches it. */
    @Test
    void aFileOutsideConfigHasNoPatch(@TempDir Path root) {
        File configDir = root.resolve("config").toFile();
        assertNull(ConfigPatch.fileFor(configDir, root.resolve("shared/mymod.cfg").toFile()));
        assertNull(ConfigPatch.fileFor(configDir, new File(configDir, "../shared/mymod.cfg")));
    }

    @Test
    void reApplyingKeepsTheOriginalSavedValue(@TempDir Path root) throws IOException {
        writeCase(root, BASE_COUNT, "general {\n    I:count=9\n}\n");

        Configuration cfg = load(root);
        Property count = apply(cfg, "general", "count");
        apply(cfg, "general", "count");

        assertEquals(9, count.getInt());
        assertEquals("5", count.getSavedString(), "a second pass must not record the patched value as the file value");
    }
}
