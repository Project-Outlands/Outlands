package net.minecraftforge.fml.common;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the shape handling of {@code mcmod.info}: a top level array of mods, or an object that may carry jar level
 * data and an optional {@code modList}.
 *
 * <p>Only the failure modes are asserted, because they are what regressed before: the object branch was reached
 * unconditionally, so an empty or non-container file made gson return {@code null} and the following dereference
 * killed discovery, and an object without {@code modList} hit a null array. Both are inputs a jar can legitimately
 * carry, so both must be handled rather than assumed away.
 */
class MetadataCollectionTest {

    private static MetadataCollection parse(String json) {
        InputStream in = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
        return MetadataCollection.from(in, "test.jar");
    }

    @Test
    void arrayFormIsStillReadAsModList() {
        MetadataCollection collection = parse("[{\"modid\":\"first\",\"name\":\"First\",\"version\":\"1.0\"},"
                + "{\"modid\":\"second\",\"version\":\"2.0\"}]");

        assertEquals(2, collection.getIds().size());
        assertEquals("First", collection.getMetadataForId("first", java.util.Collections.emptyMap()).name);
        assertEquals("2.0", collection.getMetadataForId("second", java.util.Collections.emptyMap()).version);
    }

    @Test
    void objectFormWithModListIsReadAsModList() {
        MetadataCollection collection = parse("{\"modList\":[{\"modid\":\"only\",\"version\":\"3.0\"}]}");

        assertEquals(1, collection.getIds().size());
        assertEquals("3.0", collection.getMetadataForId("only", java.util.Collections.emptyMap()).version);
    }

    @Test
    void objectFormWithoutModListYieldsNoIdsAndDoesNotThrow() {
        // A jar that only carries jar level data (library, language provider, declarative mod) has nothing to
        // enumerate. Discovery treats an empty id set as "no mod here", which is the intended outcome.
        MetadataCollection collection = parse("{\"jar\":{\"type\":\"LIB\",\"schemaVersion\":1,\"license\":\"LGPL-2.1\"}}");

        assertTrue(collection.getIds().isEmpty());
    }

    @Test
    void emptyOrNonContainerContentIsIgnoredInsteadOfCrashing() {
        // Both of these used to run through gson into a null MetadataCollection and then NPE on the mod list.
        assertTrue(parse("").getIds().isEmpty());
        assertTrue(parse("   \n\t ").getIds().isEmpty());
        assertTrue(parse("\"just a string\"").getIds().isEmpty());
        assertTrue(parse("42").getIds().isEmpty());
        assertTrue(parse("null").getIds().isEmpty());
    }

    @Test
    void malformedJsonIsIgnoredInsteadOfCrashing() {
        // Trailing comma: gson rejects it (strict), and the file must be reported rather than half-read.
        assertTrue(parse("[{\"modid\":\"broken\",}]").getIds().isEmpty());
    }

    @Test
    void nullArrayElementsAreSkipped() {
        MetadataCollection collection = parse("[null,{\"modid\":\"survivor\",\"version\":\"1.0\"}]");

        assertEquals(1, collection.getIds().size());
        assertNotNull(collection.getMetadataForId("survivor", java.util.Collections.emptyMap()));
    }

    @Test
    void filesWithoutAJarSectionHaveNoDeclaration() {
        // Every file that predates the section: reading it must not invent one, otherwise a loader-side default
        // would apply to thousands of jars that never opted in.
        assertNull(parse("[{\"modid\":\"old\"}]").getJarDeclaration());
        assertNull(parse("{\"modList\":[{\"modid\":\"old\"}]}").getJarDeclaration());
    }

    @Test
    void jarSectionWithoutModListIsRead() {
        // A jar that only describes itself: library, language provider, or a mod with no @Mod class.
        var declaration = parse("{\"jar\":{\"schemaVersion\":1,\"license\":\"LGPL-2.1-only\"}}").getJarDeclaration();

        assertNotNull(declaration);
        assertEquals(1, declaration.schemaVersion());
        assertEquals("LGPL-2.1-only", declaration.license());
        assertTrue(declaration.unknownKeys().isEmpty());
    }

    @Test
    void jarSectionIsReadForJarsThatAlsoHaveMods() {
        var collection = parse("{\"jar\":{\"schemaVersion\":1,\"license\":\"MIT\",\"futureKey\":\"x\"},"
                + "\"modList\":[{\"modid\":\"mixed\"}]}");

        assertEquals(1, collection.getIds().size());
        var declaration = collection.getJarDeclaration();
        assertNotNull(declaration);
        // Unknown keys are reported, not rejected: a newer schema must not brick an older loader.
        assertEquals(java.util.Map.of("futureKey", "\"x\""), declaration.unknownKeys());
    }

    @Test
    void jarSectionRequiresSchemaVersionAndLicense() {
        // Declaring the section is opting into the current schema, so its mandatory fields are mandatory. A missing
        // licence is the one thing a downstream consumer cannot guess or default.
        assertThrows(LoaderException.class,
                () -> parse("{\"jar\":{\"license\":\"MIT\"}}"));
        assertThrows(LoaderException.class,
                () -> parse("{\"jar\":{\"schemaVersion\":1}}"));
        assertThrows(LoaderException.class,
                () -> parse("{\"jar\":{\"schemaVersion\":1,\"license\":\"  \"}}"));
    }

    @Test
    void aJarSectionOfTheWrongTypeIsRejected() {
        // "jar" being present is the jar opting into the current schema, so a value that is not an object is a broken
        // declaration rather than an absent one. Ignoring it would read the jar as legacy: no containedDeps, no side,
        // no access transformers - and no hint about why.
        assertThrows(LoaderException.class, () -> parse("{\"jar\":\"an object goes here\"}"));
        assertThrows(LoaderException.class, () -> parse("{\"jar\":[]}"));
        assertThrows(LoaderException.class, () -> parse("{\"jar\":1}"));
    }

    @Test
    void aModListOfTheWrongTypeIsReportedAndReadAsAbsent() {
        // The opposite call, and deliberately so: modList predates the jar section, so getting its shape wrong is old
        // metadata rather than a claim to follow a new schema. Mods are still found by scanning for @Mod, which is
        // what an absent list falls back to, so this must not be fatal.
        var collection = parse("{\"jar\":{\"schemaVersion\":1,\"license\":\"MIT\"},\"modList\":{\"modid\":\"x\"}}");

        assertNotNull(collection.getJarDeclaration());
        assertTrue(collection.getIds().isEmpty());
    }

    @Test
    void anEntryOfTheWrongTypeDoesNotTakeTheJarSectionWithIt() {
        // One bad entry used to throw inside gson, reach the "not valid JSON" handler, and discard the whole file -
        // including the jar section, which has nothing to do with the entry that was malformed.
        var collection = parse("{\"jar\":{\"schemaVersion\":1,\"license\":\"MIT\"},"
                + "\"modList\":[{\"modid\":\"broken\",\"useDependencyInformation\":1},"
                + "{\"modid\":\"intact\",\"version\":\"1.0\"}]}");

        assertNotNull(collection.getJarDeclaration());
        assertFalse(collection.getIds().contains("broken"), "the malformed entry is the one that is dropped");
        assertEquals("1.0", collection.getMetadataForId("intact", java.util.Collections.emptyMap()).version);
    }

    @Test
    void dependsWinsOverTheOlderListsInTheSameEntry() {
        // Both in one entry is a half-finished migration. The new form is authoritative - it is the only one that can
        // say "client only" - and the older keys are named in a warning rather than quietly ignored.
        var collection = parse("{\"modList\":[{\"modid\":\"mixed\","
                + "\"depends\":[{\"on\":\"foo\",\"required\":true}],"
                + "\"requiredMods\":[\"bar@[1.0,)\"]}]}");

        ModMetadata metadata = collection.getMetadataForId("mixed", java.util.Collections.emptyMap());
        assertEquals(1, metadata.declaredDependencies.size());
        metadata.resolveDeclaredDependencies(net.minecraftforge.fml.relauncher.Side.CLIENT);

        assertEquals(1, metadata.requiredMods.size());
        assertEquals("foo", metadata.requiredMods.iterator().next().getLabel());
    }

    @Test
    void languageKeysAreReadFromAModEntry() {
        // These three keys are per-mod, not jar level, so they must survive gson into ModMetadata: the merge with the
        // annotation reads them from there, and a key that gson silently drops would look declared but be invisible.
        var collection = parse("[{\"modid\":\"scaly\",\"modLanguage\":\"scala\","
                + "\"modLanguageAdapter\":\"com.example.Adapter\",\"modLanguageVersion\":\"[2.13,2.14)\"}]");

        ModMetadata metadata = collection.getMetadataForId("scaly", java.util.Collections.emptyMap());
        assertEquals("scala", metadata.modLanguage);
        assertEquals("com.example.Adapter", metadata.modLanguageAdapter);
        assertEquals("[2.13,2.14)", metadata.modLanguageVersion);
    }

    @Test
    void languageKeysAreNotTreatedAsJarLevelKeys() {
        // warnOnJarLevelKeyInModEntry flags jar-level keys written in a mod entry. These belong in the entry, so they
        // must stay out of that set - a warning telling authors to move them into the "jar" object is the opposite of
        // what the schema says, and would send them to a place where they are not read either.
        assertFalse(net.minecraftforge.fml.common.discovery.JarDeclaration.knownKeys().contains("modLanguage"));
        assertFalse(net.minecraftforge.fml.common.discovery.JarDeclaration.knownKeys().contains("modLanguageAdapter"));
        assertFalse(net.minecraftforge.fml.common.discovery.JarDeclaration.knownKeys().contains("modLanguageVersion"));
    }
}
