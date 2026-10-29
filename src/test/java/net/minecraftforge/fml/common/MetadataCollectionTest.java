package net.minecraftforge.fml.common;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
}
