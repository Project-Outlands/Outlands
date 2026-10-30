package net.minecraftforge.fml.common.discovery;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import net.minecraftforge.fml.common.LoaderException;
import net.minecraftforge.fml.common.MetadataCollection;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@code jar.entrypoints}: the declaration that lets a jar present mods without mod classes.
 *
 * <p>The key is the mod id, so this is the only place a jar says "there is a mod called X" without an {@code @Mod}
 * annotation to find. Getting it wrong is silent in both directions: a malformed entry means a mod that never loads, and
 * an empty mod id means a mod nobody can name in a dependency.
 */
class EntrypointDeclarationTest {

    private static final String MANDATORY = "\"schemaVersion\":1,\"license\":\"MIT\"";

    private static JarDeclaration declaration(String extra) {
        String json = "{\"jar\":{" + MANDATORY + extra + "}}";
        JarDeclaration declaration = MetadataCollection
                .from(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), "test.jar")
                .getJarDeclaration();
        assertNotNull(declaration);
        return declaration;
    }

    @Test
    void anEntrypointIsKeyedByTheModIdItDeclares() {
        assertEquals(Map.of("clojuremod", "com.example.ClojureEntrypoint"),
                declaration(",\"entrypoints\":{\"clojuremod\":\"com.example.ClojureEntrypoint\"}").entrypoints());
    }

    @Test
    void severalEntrypointsAreKeptInOrder() {
        Map<String, String> entrypoints = declaration(",\"entrypoints\":{"
                + "\"first\":\"com.example.First\",\"second\":\"com.example.Second\"}").entrypoints();

        assertEquals(java.util.List.of("first", "second"), java.util.List.copyOf(entrypoints.keySet()));
    }

    @Test
    void declaringNoneYieldsAnEmptyMap() {
        assertTrue(declaration("").entrypoints().isEmpty());
        assertTrue(declaration(",\"entrypoints\":{}").entrypoints().isEmpty());
    }

    @Test
    void anEntryWithoutAClassIsRejected() {
        // Without a class there is nothing to call, so the declared mod would exist and never do anything.
        assertThrows(LoaderException.class, () -> declaration(",\"entrypoints\":{\"m\":\"\"}"));
        assertThrows(LoaderException.class, () -> declaration(",\"entrypoints\":{\"m\":null}"));
    }

    @Test
    void anEmptyModIdIsRejected() {
        assertThrows(LoaderException.class, () -> declaration(",\"entrypoints\":{\"\":\"com.example.X\"}"));
    }

    @Test
    void aMalformedSectionIsRejected() {
        assertThrows(LoaderException.class, () -> declaration(",\"entrypoints\":[\"com.example.X\"]"));
    }

    @Test
    void theRejectionNamesTheJar() {
        LoaderException failure = assertThrows(LoaderException.class,
                () -> declaration(",\"entrypoints\":{\"m\":\"\"}"));

        assertTrue(failure.getMessage().contains("test.jar"), failure.getMessage());
        assertTrue(failure.getMessage().contains("\"m\""), failure.getMessage());
    }

    @Test
    void entrypointsAreNotReportedAsUnknownKeys() {
        assertTrue(declaration(",\"entrypoints\":{\"m\":\"com.example.X\"}").unknownKeys().isEmpty());
    }
}
