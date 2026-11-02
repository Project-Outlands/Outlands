package net.minecraftforge.fml.common.discovery;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import net.minecraftforge.fml.common.LoaderException;
import net.minecraftforge.fml.common.MetadataCollection;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers the JSON type each key in the {@code jar} section accepts.
 *
 * <p>The reader used to stringify whatever it was given, so {@code "license": true} declared the licence {@code "true"}
 * and {@code "providers": {"scala": ["x"]}} declared the provider class {@code ["x"]}. Those jars then failed somewhere
 * else, with a message pointing at the wrong thing - or, in the licence case, did not fail at all.
 *
 * <p>The other direction matters too: a value that is text without being prose is written as a number by anyone
 * typing it by hand, so {@code 1} and {@code "1"} have to keep meaning the same thing where that is unambiguous.
 */
class JarSectionTypesTest {

    private static JarDeclaration parse(String jarSectionBody) {
        String json = "{\"jar\":{\"schemaVersion\":1,\"license\":\"MIT\"" + jarSectionBody + "}}";
        JarDeclaration declaration = MetadataCollection
                .from(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), "test.jar")
                .getJarDeclaration();
        assertNotNull(declaration);
        return declaration;
    }

    private static void rejected(String jarSectionBody) {
        assertThrows(LoaderException.class, () -> parse(jarSectionBody),
                "expected the jar section to refuse: " + jarSectionBody);
    }

    @Test
    void textKeysRefuseEverythingThatIsNotText() {
        // A licence is prose: there is no reading of `true` or of an object that means "MIT".
        rejected(",\"license\":true");
        rejected(",\"license\":42");
        rejected(",\"license\":{\"spdx\":\"MIT\"}");
        rejected(",\"side\":1");
        rejected(",\"loadOrder\":true");
    }

    @Test
    void nestedClassNamesRefuseStructuredValues() {
        // Both are maps of name to class name, so a value that is not a string is not a class name. Stringifying an
        // array into `["x"]` would carry it all the way to Class.forName.
        rejected(",\"entrypoints\":{\"mymod\":{}}");
        rejected(",\"entrypoints\":{\"mymod\":[\"a\"]}");
        rejected(",\"providers\":{\"scala\":[\"com.example.Adapter\"]}");
        rejected(",\"providers\":{\"scala\":true}");
    }

    @Test
    void containedDepFieldsRefuseBooleans() {
        rejected(",\"containedDeps\":[{\"path\":true}]");
        rejected(",\"containedDeps\":[{\"path\":\"a.jar\",\"version\":true}]");
        rejected(",\"containedDeps\":[{\"path\":\"a.jar\",\"id\":{\"a\":1}}]");
        rejected(",\"containedDeps\":[{\"path\":\"a.jar\",\"kind\":1}]");
    }

    @Test
    void versionsAndIdentifiersAcceptNumbers() {
        // The point of a number here: `"version": 1.0` is what a hand-written file says. Reading it as "1.0" is the
        // same value; reading it as an error would reject a correct jar over quoting.
        assertEquals("1.0",
                parse(",\"containedDeps\":[{\"path\":\"a.jar\",\"version\":1.0}]").containedDeps().get(0).version());
        assertEquals("2.12",
                parse(",\"providerVersions\":{\"scala\":[2.12]},\"providers\":{\"scala\":\"com.example.Adapter\"}")
                        .providerVersions().get("scala").get(0));
        assertEquals("42",
                parse(",\"containedDeps\":[{\"path\":\"a.jar\",\"id\":42}]").containedDeps().get(0).id());
    }

    @Test
    void theSchemaVersionIsANumberOrItsQuotedSpelling() {
        assertEquals(1, parse("").schemaVersion());

        String quoted = "{\"jar\":{\"schemaVersion\":\"1\",\"license\":\"MIT\"}}";
        assertEquals(1, MetadataCollection.from(new ByteArrayInputStream(quoted.getBytes(StandardCharsets.UTF_8)),
                "test.jar").getJarDeclaration().schemaVersion());

        String asBoolean = "{\"jar\":{\"schemaVersion\":true,\"license\":\"MIT\"}}";
        assertThrows(LoaderException.class, () -> MetadataCollection.from(
                new ByteArrayInputStream(asBoolean.getBytes(StandardCharsets.UTF_8)), "test.jar"));
    }

    @Test
    void theAccessTransformerListKeepsItsOwnRules() {
        // Already had them, pinned here because this is where the type rules now live: an array element is one path
        // and is never split, a single string is split on whitespace, and neither accepts a number.
        assertEquals(java.util.List.of("a.cfg", "b c.cfg"), parse(",\"at\":[\"a.cfg\",\"b c.cfg\"]").accessTransformers());
        assertEquals(java.util.List.of("a.cfg", "b.cfg"), parse(",\"at\":\"a.cfg b.cfg\"").accessTransformers());
        rejected(",\"at\":[1]");
        rejected(",\"at\":3");
    }
}
