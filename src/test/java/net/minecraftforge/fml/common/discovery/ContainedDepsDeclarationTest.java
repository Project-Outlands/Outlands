package net.minecraftforge.fml.common.discovery;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

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
        // An entry that says nothing about what it is describes a mod, which is what every bundled jar used to be.
        assertEquals(JarDeclaration.ContainedDepKind.MOD, deps.get(0).kind());
    }

    @Test
    void anEntryCanBeMarkedAsALibrary() {
        // Per entry rather than per jar: one jar routinely bundles both a prerequisite mod and a plain library, and the old
        // NonModDeps flag could only say "all of them" or "none of them".
        List<JarDeclaration.ContainedDep> deps = declaration(",\"containedDeps\":["
                + "{\"path\":\"libs/guide.jar\"},"
                + "{\"path\":\"libs/asm.jar\",\"kind\":\"LIB\"}]")
                .containedDeps();

        assertEquals(JarDeclaration.ContainedDepKind.MOD, deps.get(0).kind());
        assertEquals(JarDeclaration.ContainedDepKind.LIB, deps.get(1).kind());
    }

    @Test
    void theKindIsCaseInsensitiveAndAcceptsTheLongSpelling() {
        assertEquals(JarDeclaration.ContainedDepKind.LIB,
                declaration(",\"containedDeps\":[{\"path\":\"libs/a.jar\",\"kind\":\"lib\"}]").containedDeps().get(0).kind());
        assertEquals(JarDeclaration.ContainedDepKind.LIB,
                declaration(",\"containedDeps\":[{\"path\":\"libs/a.jar\",\"kind\":\"Library\"}]").containedDeps().get(0).kind());
    }

    @Test
    void anUnknownKindIsRejectedWithTheAcceptedValues() {
        // Falling back to MOD would silently extract a library as if it were a mod and scan it; falling back to LIB would
        // hide a prerequisite mod. Neither is a guess the loader should make.
        LoaderException failure = assertThrows(LoaderException.class,
                () -> declaration(",\"containedDeps\":[{\"path\":\"libs/a.jar\",\"kind\":\"PLUGIN\"}]"));

        assertTrue(failure.getMessage().contains("PLUGIN"), failure.getMessage());
        assertTrue(failure.getMessage().contains("MOD"), failure.getMessage());
        assertTrue(failure.getMessage().contains("LIB"), failure.getMessage());
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

    /**
     * Coordinates stay in the manifest. A jar's own maven identity is {@code Maven-Artifact}, and the jars that carry
     * one are usually plain libraries with no {@code mcmod.info} - so accepting coordinates here would suggest a
     * metadata section is required for jars that have nobody to add it. Reporting them as unknown is the intended
     * answer: an author who writes one is told plainly that it does nothing.
     */
    @Test
    void mavenCoordinatesInTheJarSectionAreUnknownKeys() {
        assertEquals(Set.of("maven", "artifact", "version"),
                declaration(",\"maven\":\"com.example:a:1.0\",\"artifact\":\"a\",\"version\":\"1.0\"")
                        .unknownKeys().keySet());
    }
}
