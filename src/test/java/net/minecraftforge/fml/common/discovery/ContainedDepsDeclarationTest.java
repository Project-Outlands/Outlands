package net.minecraftforge.fml.common.discovery;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import net.minecraftforge.fml.common.LoaderException;
import net.minecraftforge.fml.common.MetadataCollection;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the structured {@code containedDeps} declaration.
 *
 * <p>The legacy {@code ContainedDeps} manifest key is a space-separated list of paths. It cannot say which library a
 * path is or which version it carries, so two jars bundling different versions of one library are indistinguishable -
 * and the loader ends up with whichever was extracted first. The structured form exists to make that sayable.
 */
class ContainedDepsDeclarationTest {

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
    void aFullySpecifiedDependencyIsRead() {
        List<JarDeclaration.ContainedDep> deps = declaration(
                ",\"containedDeps\":[{\"id\":\"guava\",\"version\":\"21.0\",\"path\":\"META-INF/libraries/guava.jar\"}]")
                .containedDeps();

        assertEquals(1, deps.size());
        assertEquals("guava", deps.get(0).id());
        assertEquals("21.0", deps.get(0).version());
        assertEquals("META-INF/libraries/guava.jar", deps.get(0).path());
    }

    @Test
    void aPathOnItsOwnBehavesLikeTheLegacyForm() {
        // Same reading the manifest key gets: identity from the file name, version "0". One entry can migrate at a
        // time without the two forms disagreeing about what a path means.
        List<JarDeclaration.ContainedDep> deps = declaration(
                ",\"containedDeps\":[{\"path\":\"libs/asm-9.5.jar\"}]").containedDeps();

        assertEquals("asm-9.5", deps.get(0).id());
        assertEquals("0", deps.get(0).version());
    }

    @Test
    void severalDependenciesKeepTheirOrder() {
        List<JarDeclaration.ContainedDep> deps = declaration(",\"containedDeps\":["
                + "{\"id\":\"a\",\"path\":\"libs/a.jar\"},{\"id\":\"b\",\"path\":\"libs/b.jar\"}]").containedDeps();

        assertEquals(List.of("a", "b"), deps.stream().map(JarDeclaration.ContainedDep::id).toList());
    }

    @Test
    void declaringNoneYieldsAnEmptyList() {
        assertTrue(declaration("").containedDeps().isEmpty());
        assertTrue(declaration(",\"containedDeps\":[]").containedDeps().isEmpty());
    }

    @Test
    void anEntryWithoutAPathIsRejected() {
        // Without a path there is nothing to put on the classpath, so accepting the entry would declare a library that
        // silently does not exist.
        assertThrows(LoaderException.class, () -> declaration(",\"containedDeps\":[{\"id\":\"x\"}]"));
        assertThrows(LoaderException.class, () -> declaration(",\"containedDeps\":[{\"path\":\"  \"}]"));
    }

    @Test
    void aPathThatIsNotAJarIsRejected() {
        assertThrows(LoaderException.class, () -> declaration(",\"containedDeps\":[{\"path\":\"libs/a.zip\"}]"));
    }

    @Test
    void aMalformedSectionIsRejected() {
        assertThrows(LoaderException.class, () -> declaration(",\"containedDeps\":\"libs/a.jar\""));
        assertThrows(LoaderException.class, () -> declaration(",\"containedDeps\":[\"libs/a.jar\"]"));
    }

    @Test
    void theRejectionNamesTheJar() {
        LoaderException failure = assertThrows(LoaderException.class,
                () -> declaration(",\"containedDeps\":[{\"path\":\"libs/a.zip\"}]"));

        assertTrue(failure.getMessage().contains("test.jar"), failure.getMessage());
        assertTrue(failure.getMessage().contains("libs/a.zip"), failure.getMessage());
    }

    @Test
    void containedDepsAreNotReportedAsUnknownKeys() {
        assertTrue(declaration(",\"containedDeps\":[{\"path\":\"libs/a.jar\"}]").unknownKeys().isEmpty());
    }
}
