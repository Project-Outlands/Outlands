package net.minecraftforge.fml.common.discovery;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.jar.Attributes;

import net.minecraftforge.fml.common.LoaderException;
import net.minecraftforge.fml.common.MetadataCollection;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the two keys that decide how a jar loads early: {@code coremod} and {@code alsoMod}.
 *
 * <p>{@code alsoMod} answers one question that the manifest answered twice, with {@code FMLCorePluginContainsFMLMod}
 * and with the {@code ForceLoadAsMod} of the 1.7.10 era. Both remain readable, but neither is consulted once the jar
 * declares the section.
 */
class JarCoremodDeclarationTest {

    private static final String MANDATORY = "\"schemaVersion\":1,\"license\":\"MIT\"";

    @Test
    public void aCoremodClassIsRead() {
        assertEquals("com.example.Plugin", declaration("\"coremod\":\"com.example.Plugin\"").coreMod());
        assertEquals("com.example.Plugin", declaration("\"coremod\":\"  com.example.Plugin  \"").coreMod());
        assertEquals("com.example.Plugin", declaration("\"coremod\":\"com.example.Plugin\"").coreMod());
        assertNull(declaration("").coreMod());
    }

    @Test
    public void anEmptyCoremodIsRefused() {
        assertThrows(LoaderException.class, () -> declaration("\"coremod\":\"   \""));
        assertThrows(LoaderException.class, () -> declaration("\"coremod\":[]"));
    }

    @Test
    public void alsoModIsABoolean() {
        assertTrue(declaration("\"alsoMod\":true").alsoMod());
        assertFalse(declaration("\"alsoMod\":false").alsoMod());
        assertFalse(declaration("").alsoMod(), "a jar that says nothing is only a mod if the scan finds a mod");
    }

    @Test
    public void aQuotedAlsoModIsRefused() {
        // "true" in quotes reads the same to a human and differently to every other parser in this file
        assertThrows(LoaderException.class, () -> declaration("\"alsoMod\":\"true\""));
    }

    @Test
    public void theDeclarationWinsOverTheManifest() {
        Attributes attributes = new Attributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.put(JarDeclaration.MANIFEST_CORE_MOD, "manifest.Plugin");

        assertEquals("declared.Plugin", JarDeclaration.coreModOf(attributes, declaration("\"coremod\":\"declared.Plugin\"")));
        assertEquals("manifest.Plugin", JarDeclaration.coreModOf(attributes, declaration("")));
        assertEquals("manifest.Plugin", JarDeclaration.coreModOf(attributes, null));
        assertNull(JarDeclaration.coreModOf(null, null));
    }

    @Test
    public void aBlankManifestCoremodIsNoCoremod() {
        Attributes attributes = new Attributes();
        attributes.put(JarDeclaration.MANIFEST_CORE_MOD, "  ");

        assertNull(JarDeclaration.coreModOf(attributes, null));
    }

    @Test
    public void neitherKeyIsReportedAsUnknown() {
        JarDeclaration parsed = declaration("\"coremod\":\"com.example.Plugin\",\"alsoMod\":true");

        assertTrue(parsed.unknownKeys().isEmpty(), "a key the loader honours must not also be reported as unrecognised");
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
}
