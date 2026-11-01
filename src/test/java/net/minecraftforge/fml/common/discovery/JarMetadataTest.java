package net.minecraftforge.fml.common.discovery;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import net.minecraftforge.fml.common.MetadataCollection;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the one read of a jar's {@code mcmod.info} that both passes share.
 *
 * <p>Assembling the classpath asks only whether the jar declares a {@code jar} section; discovery asks for the mod
 * list too. Two parses of the same file used to answer those two questions.
 */
class JarMetadataTest {

    private static final String JAR_SECTION = "\"jar\":{\"schemaVersion\":1,\"license\":\"MIT\"}";

    @Test
    void aJarWithoutMetadataSaysNothing(@TempDir Path dir) throws IOException {
        JarMetadata metadata = JarMetadata.of(jar(dir, "plain.jar", null));

        assertFalse(metadata.isPresent());
        assertNull(metadata.metadata());
        assertNull(metadata.declaration());
    }

    @Test
    void aModListWithoutAJarSectionIsNotADeclaration(@TempDir Path dir) throws IOException {
        JarMetadata metadata = JarMetadata.of(jar(dir, "legacy.jar", "{\"modList\":[{\"modid\":\"sample\"}]}"));

        assertTrue(metadata.isPresent());
        assertNotNull(metadata.metadata(), "the mod list is still there for discovery");
        assertNull(metadata.declaration(), "the fast path must not invent a section that is not there");
    }

    @Test
    void theSectionIsFoundAndDoesNotConsumeTheMetadata(@TempDir Path dir) throws IOException {
        JarMetadata metadata = JarMetadata.of(jar(dir, "modern.jar",
                "{" + JAR_SECTION + ",\"modList\":[{\"modid\":\"sample\"}]}"));

        assertNotNull(metadata.declaration());
        assertNotNull(metadata.metadata(), "asking for the declaration must not leave the mod list unreadable");
        assertTrue(metadata.metadata().getIds().contains("sample"));
    }

    @Test
    void theArrayFormHasNoSection(@TempDir Path dir) throws IOException {
        JarMetadata metadata = JarMetadata.of(jar(dir, "array.jar", "[{\"modid\":\"sample\"}]"));

        assertNotNull(metadata.metadata());
        assertNull(metadata.declaration());
    }

    @Test
    void oneEntryIsReadOnce(@TempDir Path dir) throws IOException {
        java.io.File file = jar(dir, "cached.jar", "{\"modList\":[{\"modid\":\"sample\"}]}");
        JarMetadata first = JarMetadata.of(file);

        assertSame(first, JarMetadata.of(file), "the same file must not be read twice");
        assertSame(first.metadata(), first.metadata(), "the parse must not be repeated either");

        MetadataCollection metadata = JarMetadata.of(file).metadata();
        assertNotNull(metadata);
        assertTrue(metadata.getIds().contains("sample"));
    }

    /** Creates a jar holding {@code mcmod.info} with {@code content}, or no entry at all when it is {@code null}. */
    private static java.io.File jar(Path dir, String name, String content) throws IOException {
        Path path = dir.resolve(name);
        try (OutputStream out = Files.newOutputStream(path); JarOutputStream jar = new JarOutputStream(out)) {
            if (content != null) {
                jar.putNextEntry(new JarEntry("mcmod.info"));
                jar.write(content.getBytes(StandardCharsets.UTF_8));
                jar.closeEntry();
            }
        }
        return path.toFile();
    }
}
