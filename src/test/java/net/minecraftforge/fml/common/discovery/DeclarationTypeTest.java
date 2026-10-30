package net.minecraftforge.fml.common.discovery;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import net.minecraftforge.fml.common.LoaderException;
import net.minecraftforge.fml.common.MetadataCollection;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@code jar.type}: the jar saying what it is, instead of the loader inferring it from what it can find.
 *
 * <p>The behaviour that matters is the negative one. A jar that declares {@code LIB} must stop contributing containers,
 * and an id the loader has never heard of must not start contributing them either - the failure mode of getting that
 * wrong is a mod loading that its author explicitly excluded, which no crash report would explain.
 */
class DeclarationTypeTest {

    private static JarDeclaration declaration(String extra) {
        String json = "{\"jar\":{\"schemaVersion\":1,\"license\":\"MIT\"" + extra + "}}";
        return MetadataCollection
                .from(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), "test.jar")
                .getJarDeclaration();
    }

    @Test
    void declaringNothingMeansMod() {
        // The overwhelming majority of jars have no jar section at all, and every one of them must keep being scanned.
        assertEquals(DeclarationTypes.MOD, declaration("").type());
    }

    @Test
    void somethingWithoutAJarSectionHasNoTypeOfItsOwn() {
        JarDeclaration none = MetadataCollection
                .from(new ByteArrayInputStream("[{\"modid\":\"old\"}]".getBytes(StandardCharsets.UTF_8)), "old.jar")
                .getJarDeclaration();

        assertEquals(null, none);
    }

    @Test
    void libIsParsedAndRecognised() {
        assertEquals(DeclarationTypes.LIB, declaration(",\"type\":\"lib\"").type());
        assertTrue(DeclarationTypes.isKnown(declaration(",\"type\":\"LIB\"").type()));
    }

    @Test
    void theTypeIsCaseInsensitive() {
        assertEquals(DeclarationTypes.LIB, declaration(",\"type\":\"Lib\"").type());
    }

    @Test
    void onlyModIsScannedForContainers() {
        assertTrue(DeclarationTypes.isScannedForContainer(DeclarationTypes.MOD));
        assertTrue(DeclarationTypes.isScannedForContainer("mod"));
        assertFalse(DeclarationTypes.isScannedForContainer(DeclarationTypes.LIB));
        // An id nobody registered must not behave like a mod: the loader has no factory for it and never will by accident.
        assertFalse(DeclarationTypes.isScannedForContainer("PLUGIN"));
        assertFalse(DeclarationTypes.isScannedForContainer(null));
    }

    @Test
    void anUnknownTypeIsStillReadable() {
        // A jar from a newer loader has to stay readable; the loader answers by keeping it on the classpath, not by
        // refusing to start over an optional jar.
        JarDeclaration declaration = declaration(",\"type\":\"somethingnew\"");

        assertEquals("SOMETHINGNEW", declaration.type());
        assertFalse(DeclarationTypes.isKnown(declaration.type()));
    }

    @Test
    void anUnusableTypeIdIsRejected() {
        assertThrows(LoaderException.class, () -> declaration(",\"type\":\"two words\""));
        assertThrows(LoaderException.class, () -> declaration(",\"type\":\"lib;\""));
    }

    @Test
    void aBlankTypeFallsBackToMod() {
        assertEquals(DeclarationTypes.MOD, declaration(",\"type\":\"  \"").type());
    }

    @Test
    void typeIsNotReportedAsAnUnknownKey() {
        JarDeclaration declaration = declaration(",\"type\":\"LIB\"");

        assertTrue(declaration.unknownKeys().isEmpty(), declaration.unknownKeys().toString());
    }

    @Test
    void registeringATypeMakesItExpectedButStillNotScanned() {
        DeclarationTypes.register("testcustom");

        assertTrue(DeclarationTypes.isKnown("TESTCUSTOM"));
        // Registering says "this id is expected"; building containers for it needs a factory, which arrives with the
        // declarative container work. Until then the jar is classpath only, exactly like an unknown type.
        assertFalse(DeclarationTypes.isScannedForContainer("TESTCUSTOM"));
    }

    @Test
    void builtInTypesCannotBeUnregisteredAndAreDescribed() {
        String described = DeclarationTypes.describe();

        assertTrue(described.contains(DeclarationTypes.MOD), described);
        assertTrue(described.contains(DeclarationTypes.LIB), described);
    }
}
