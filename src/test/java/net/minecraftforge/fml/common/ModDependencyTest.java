package net.minecraftforge.fml.common;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

import net.minecraftforge.fml.relauncher.Side;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@code depends}: one object per dependency, so required / before-after / side stay three independent facts.
 *
 * <p>The annotation crosses the same three into one token ({@code client-required-after:foo@[1.0,)}). Flattening that
 * into metadata lists worked for two of the three and would have needed nine lists for the third, which is why the
 * entry form is structured instead of more keys.
 */
class ModDependencyTest {

    private static ModMetadata entry(String dependsJson) {
        String json = "{\"modList\":[{\"modid\":\"mymod\",\"version\":\"1.0\",\"depends\":" + dependsJson + "}]}";
        return MetadataCollection.from(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), "test.jar")
                .getMetadataForId("mymod", Collections.emptyMap());
    }

    private static List<ModDependency> parse(String dependsJson) {
        return entry(dependsJson).declaredDependencies;
    }

    private static void rejected(String dependsJson) {
        assertThrows(LoaderException.class, () -> parse(dependsJson), "expected this to be refused: " + dependsJson);
    }

    @Test
    void eachFactIsItsOwnKey() {
        List<ModDependency> deps = parse("["
                + "{\"on\":\"forge\",\"version\":\"[14.23.5.2860,)\",\"required\":true},"
                + "{\"on\":\"baubles\",\"order\":\"after\"},"
                + "{\"on\":\"thaumcraft\",\"order\":\"before\",\"required\":true},"
                + "{\"on\":\"jei\",\"order\":\"after\",\"side\":\"client\"}"
                + "]");

        assertEquals(4, deps.size());
        assertEquals("forge", deps.get(0).target());
        assertEquals("[14.23.5.2860,)", deps.get(0).versionRange());
        assertTrue(deps.get(0).required());
        assertEquals(ModDependency.Order.AFTER, deps.get(1).order());
        assertFalse(deps.get(1).required());
        assertEquals(ModDependency.Order.BEFORE, deps.get(2).order());
        assertTrue(deps.get(2).required());
        assertNotNull(deps.get(3).side());
        assertEquals("client", deps.get(3).side().name().toLowerCase(java.util.Locale.ENGLISH));
    }

    @Test
    void theResolvedListsAreTheOnesTheSorterReads() {
        ModMetadata metadata = entry("[{\"on\":\"forge\",\"version\":\"[14.23.5.2860,)\",\"order\":\"after\",\"required\":true}]");

        metadata.resolveDeclaredDependencies(Side.CLIENT);

        assertEquals(1, metadata.requiredMods.size());
        assertEquals(1, metadata.dependencies.size());
        assertTrue(metadata.dependants.isEmpty());
        // The same instance in both: Loader asks whether a dependency is required by looking it up in the requirement
        // set, and two separately parsed references with the same text do not compare equal (nor hash alike), so the
        // lookup would answer "not required" and a missing target would stop being an error.
        assertSame(metadata.requiredMods.iterator().next(), metadata.dependencies.get(0));
        assertTrue(metadata.requiredMods.contains(metadata.dependencies.get(0)));
    }

    @Test
    void aSideScopedDependencyOnlyExistsOnThatSide() {
        String json = "[{\"on\":\"clientlib\",\"required\":true,\"side\":\"client\"},"
                + "{\"on\":\"serverlib\",\"required\":true,\"side\":\"server\"},"
                + "{\"on\":\"bothlib\",\"required\":true}]";

        ModMetadata client = entry(json);
        client.resolveDeclaredDependencies(Side.CLIENT);
        ModMetadata server = entry(json);
        server.resolveDeclaredDependencies(Side.SERVER);

        assertEquals(2, client.requiredMods.size(), "the server-only requirement must not exist on the client");
        assertEquals(2, server.requiredMods.size(), "and the reverse");
        assertTrue(labels(client.requiredMods).contains("clientlib"));
        assertFalse(labels(client.requiredMods).contains("serverlib"));
        assertTrue(labels(server.requiredMods).contains("serverlib"));
        assertTrue(labels(server.requiredMods).contains("bothlib"), "an unscoped dependency is on both sides");
    }

    @Test
    void sideBothIsTheSameAsNoRestriction() {
        ModMetadata metadata = entry("[{\"on\":\"foo\",\"required\":true,\"side\":\"both\"}]");

        metadata.resolveDeclaredDependencies(Side.CLIENT);

        assertEquals(1, metadata.requiredMods.size());
    }

    @Test
    void aDependencyThatWouldDoNothingIsRefused() {
        // Neither required nor ordered: the annotation rejects this too, because an entry that reads as declared and
        // has no effect is worse than a jar that fails to load. A version on its own does not imply "required".
        rejected("[{\"on\":\"foo\"}]");
        rejected("[{\"on\":\"foo\",\"required\":false}]");
        rejected("[{\"on\":\"foo\",\"version\":\"[1.0,)\"}]");
    }

    @Test
    void valuesOfTheWrongTypeAreRefused() {
        rejected("[{\"on\":\"foo\",\"required\":\"true\"}]");
        rejected("[{\"on\":\"foo\",\"required\":true,\"version\":true}]");
        rejected("[{\"on\":\"foo\",\"required\":true,\"order\":1}]");
        rejected("[{\"on\":\"foo\",\"required\":true,\"side\":[]}]");
        rejected("[{\"on\":{\"id\":\"foo\"},\"required\":true}]");
        rejected("{\"on\":\"foo\"}");
        rejected("[1]");
    }

    @Test
    void anUnusableModIdIsRefused() {
        // Uppercase can never match a loaded mod id, and the annotation syntax characters would be read as instructions
        // or as a version separator.
        rejected("[{\"on\":\"Foo\",\"required\":true}]");
        rejected("[{\"on\":\"foo@[1.0,)\",\"required\":true}]");
        rejected("[{\"on\":\"\",\"required\":true}]");
        rejected("[{\"required\":true}]");
    }

    @Test
    void oneEntryPerMod() {
        rejected("[{\"on\":\"foo\",\"required\":true},{\"on\":\"foo\",\"order\":\"after\"}]");
    }

    @Test
    void anUnknownOrderOrRangeIsRefused() {
        rejected("[{\"on\":\"foo\",\"required\":true,\"order\":\"during\"}]");
        rejected("[{\"on\":\"foo\",\"required\":true,\"version\":\"[1.0\"}]");
    }

    @Test
    void theAnnotationSpellingSurvivesForLogs() {
        // Not decoration: the log line an author compares against their old annotation has to be the same string.
        assertEquals("required-after:forge@[14.23.5.2860,)", parse("[{\"on\":\"forge\",\"version\":\"[14.23.5.2860,)\",\"required\":true,\"order\":\"after\"}]").get(0).toString());
        assertEquals("after:baubles", parse("[{\"on\":\"baubles\",\"order\":\"after\"}]").get(0).toString());
        assertEquals("client-before:jei", parse("[{\"on\":\"jei\",\"order\":\"before\",\"side\":\"client\"}]").get(0).toString());
        assertEquals("required:foo", parse("[{\"on\":\"foo\",\"required\":true}]").get(0).toString());
    }

    private static List<String> labels(java.util.Collection<net.minecraftforge.fml.common.versioning.ArtifactVersion> versions) {
        return versions.stream().map(net.minecraftforge.fml.common.versioning.ArtifactVersion::getLabel).toList();
    }
}
