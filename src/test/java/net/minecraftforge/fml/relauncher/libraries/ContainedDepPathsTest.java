package net.minecraftforge.fml.relauncher.libraries;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.jar.Attributes;

import net.minecraftforge.fml.common.MetadataCollection;
import net.minecraftforge.fml.common.discovery.JarDeclaration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Covers which entries a jar's bundled dependencies are read from.
 *
 * <p>The legacy {@code ContainedDeps} manifest attribute was the only source, so a jar with no manifest could say
 * nothing and a jar that moved to the structured declaration kept being read from the attribute it left behind. The
 * declaration is now the primary source, and the attribute the fallback - which is what lets a jar written against the
 * current metadata carry bundled dependencies at all.
 */
class ContainedDepPathsTest {

    private static final String MANDATORY = "\"schemaVersion\":1,\"license\":\"MIT\"";

    @Test
    void theDeclarationAloneIsEnough() {
        // the case this exists for: no manifest attribute, and no manifest either
        JarDeclaration declaration = declaration("\"containedDeps\":[{\"path\":\"a.jar\"},{\"path\":\"b.jar\"}]");

        assertEquals(List.of("a.jar", "b.jar"), LibraryManager.containedDepPaths(null, declaration, "test.jar"));
    }

    @Test
    void theManifestAttributeAloneStillWorks() {
        // every jar written before the section existed, and every plain library jar
        assertEquals(List.of("a.jar", "b.jar"), LibraryManager.containedDepPaths(manifest("a.jar b.jar"), null, "test.jar"));
        assertEquals(List.of(), LibraryManager.containedDepPaths(manifest(null), null, "test.jar"));
        assertEquals(List.of(), LibraryManager.containedDepPaths(null, null, "test.jar"));
    }

    @Test
    void neitherSourceMentionsBundledEntries() {
        JarDeclaration declaration = declaration("");

        assertEquals(List.of(), LibraryManager.containedDepPaths(manifest(null), declaration, "test.jar"));
        assertEquals(List.of(), LibraryManager.containedDepPaths(null, declaration, "test.jar"));
    }

    @Test
    void theDeclarationWinsWhenBothSayTheSameThing() {
        JarDeclaration declaration = declaration("\"containedDeps\":[{\"path\":\"META-INF/libraries/x.jar\"}]");

        assertEquals(List.of("META-INF/libraries/x.jar"),
                LibraryManager.containedDepPaths(manifest("x.jar"), declaration, "test.jar"));
    }

    @Test
    void theDeclarationWinsWhenTheTwoDisagree() {
        // a jar that migrated and left a stale attribute behind: following the attribute would drop entries silently
        JarDeclaration declaration = declaration("\"containedDeps\":[{\"path\":\"new.jar\"}]");

        assertEquals(List.of("new.jar"), LibraryManager.containedDepPaths(manifest("old.jar"), declaration, "test.jar"));
    }

    @Test
    void repeatedSeparatorsInTheLegacyAttributeAreNotPaths() {
        // "a.jar  b.jar" splits into an empty element, which used to be reported as a dependency that is not a jar
        assertEquals(List.of("a.jar", "b.jar"), LibraryManager.containedDepPaths(manifest("a.jar  b.jar "), null, "test.jar"));
    }

    private static Attributes manifest(String containedDeps) {
        Attributes attributes = new Attributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (containedDeps != null) {
            attributes.put(LibraryManager.MODCONTAINSDEPS, containedDeps);
        }
        return attributes;
    }

    private static JarDeclaration declaration(String extra) {
        String json = "{\"jar\":{" + MANDATORY + (extra.isEmpty() ? "" : "," + extra) + "}}";
        JarDeclaration declaration = MetadataCollection
                .from(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), "test.jar")
                .getJarDeclaration();
        assertNotNull(declaration);
        return declaration;
    }

}
