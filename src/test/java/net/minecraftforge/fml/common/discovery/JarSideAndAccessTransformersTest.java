package net.minecraftforge.fml.common.discovery;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.jar.Attributes;

import net.minecraftforge.fml.common.LoaderException;
import net.minecraftforge.fml.common.MetadataCollection;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the two jar-level keys that used to live only in the manifest: {@code side} and {@code at}.
 *
 * <p>Both are read twice - side when deciding what may become a candidate and when extracting bundled dependencies,
 * access transformers when the early loading pass collects them - so the value a jar states has to be resolved one way
 * for all of them. These tests pin the resolution rule itself: declaration first, manifest second, default last.
 */
class JarSideAndAccessTransformersTest {

    private static final String MANDATORY = "\"schemaVersion\":1,\"license\":\"MIT\"";

    @Test
    void sideDefaultsToBoth() {
        assertEquals(JarDeclaration.Side.BOTH, declaration("").side());
        assertEquals(JarDeclaration.Side.BOTH, JarDeclaration.sideOf(null, null));
        assertEquals(JarDeclaration.Side.BOTH, JarDeclaration.sideOf(manifest(null, null), null));
    }

    @Test
    void sideIsReadInAnySpelling() {
        assertEquals(JarDeclaration.Side.CLIENT, declaration("\"side\":\"client\"").side());
        assertEquals(JarDeclaration.Side.CLIENT, declaration("\"side\":\"CLIENT\"").side());
        assertEquals(JarDeclaration.Side.SERVER, declaration("\"side\":\"Server\"").side());
    }

    @Test
    void anUnknownSideIsRefusedRatherThanGuessed() {
        // unlike loadOrder this can make a mod vanish on one side, so a typo must not be silently read as BOTH
        assertThrows(LoaderException.class, () -> declaration("\"side\":\"clientside\""));
    }

    @Test
    void theDeclarationWinsOverTheManifest() {
        Attributes attributes = manifest("SERVER", null);

        assertEquals(JarDeclaration.Side.CLIENT, JarDeclaration.sideOf(attributes, declaration("\"side\":\"client\"")));
        assertEquals(JarDeclaration.Side.SERVER, JarDeclaration.sideOf(attributes, null));
    }

    @Test
    void anUnreadableManifestSideFallsBackToBoth() {
        // the attribute predates validation: refusing to start a pack over it would cost more than it protects
        assertEquals(JarDeclaration.Side.BOTH, JarDeclaration.sideOf(manifest("clientside", null), null));
    }

    @Test
    void excludesOnlyTheOppositeSide() {
        assertTrue(JarDeclaration.Side.BOTH.excludes("SERVER") == false);
        assertTrue(JarDeclaration.Side.CLIENT.excludes("SERVER"));
        assertTrue(JarDeclaration.Side.CLIENT.excludes("CLIENT") == false);
        assertTrue(JarDeclaration.Side.SERVER.excludes("CLIENT"));
    }

    @Test
    void accessTransformersAreAListInTheDeclaration() {
        // the manifest form is a space-separated string, so a file name containing a space was unrepresentable
        assertEquals(List.of("accesstransformer.cfg", "extra at.cfg"),
                declaration("\"at\":[\"accesstransformer.cfg\",\"extra at.cfg\"]").accessTransformers());
        assertEquals(List.of(), declaration("").accessTransformers());
    }

    @Test
    void anAccessTransformerStringIsTheManifestForm() {
        // so that porting a jar does not require rewriting the value it already carries
        assertEquals(List.of("a.cfg", "b.cfg"), declaration("\"at\":\"a.cfg  b.cfg\"").accessTransformers());
    }

    @Test
    void accessTransformersAreResolvedDeclarationFirst() {
        Attributes attributes = manifest(null, "legacy1.cfg legacy2.cfg");

        assertEquals(List.of("new.cfg"), JarDeclaration.accessTransformersOf(attributes, declaration("\"at\":[\"new.cfg\"]")));
        assertEquals(List.of("legacy1.cfg", "legacy2.cfg"), JarDeclaration.accessTransformersOf(attributes, null));
        assertEquals(List.of(), JarDeclaration.accessTransformersOf(null, null));
        assertEquals(List.of(), JarDeclaration.accessTransformersOf(manifest(null, "   "), null));
    }

    @Test
    void neitherKeyIsReportedAsUnknown() {
        JarDeclaration parsed = declaration("\"side\":\"server\",\"at\":[\"a.cfg\"]");

        assertTrue(parsed.unknownKeys().isEmpty(), "a key the loader honours must not also be reported as unrecognised");
    }

    @Test
    void aNonStringAccessTransformerIsRefused() {
        assertThrows(LoaderException.class, () -> declaration("\"at\":[1]"));
        assertThrows(LoaderException.class, () -> declaration("\"at\":{\"a\":\"b\"}"));
    }

    /** Builds the {@code jar} section around {@code extra}, which must start with a comma or be empty. */
    private static JarDeclaration declaration(String extra) {
        String json = "{\"jar\":{" + MANDATORY + (extra.isEmpty() ? "" : "," + extra) + "}}";
        JarDeclaration declaration = MetadataCollection
                .from(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), "test.jar")
                .getJarDeclaration();
        assertNotNull(declaration);
        return declaration;
    }

    private static Attributes manifest(String modSide, String accessTransformers) {
        Attributes attributes = new Attributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (modSide != null) {
            attributes.put(JarDeclaration.MANIFEST_SIDE, modSide);
        }
        if (accessTransformers != null) {
            attributes.put(JarDeclaration.MANIFEST_ACCESS_TRANSFORMERS, accessTransformers);
        }
        return attributes;
    }
}
