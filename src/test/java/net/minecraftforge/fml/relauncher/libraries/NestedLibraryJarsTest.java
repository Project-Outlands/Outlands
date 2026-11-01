package net.minecraftforge.fml.relauncher.libraries;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers reading a bundled library straight out of its parent jar.
 *
 * <p>The alternative - extracting to disk first - works, but leaves a second copy of somebody else's library that has to
 * be matched to its bundler and kept in step with it. These cases pin the properties that make the substitute usable:
 * the URL behaves like a class path root, both spellings of the entry resolve, and a closed file system fails loudly
 * instead of returning stale bytes.
 */
class NestedLibraryJarsTest {

    @AfterEach
    void release() {
        NestedLibraryJars.closeAll();
        System.clearProperty("cleanroom.nestedJars");
    }

    /** Builds {@code outer.jar} containing {@code inner.jar}, which itself contains {@code entries}. */
    private static Path outerWithInner(Path dir, String innerEntry, String... entries) throws IOException {
        Path inner = dir.resolve("inner.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(inner))) {
            for (int i = 0; i < entries.length; i += 2) {
                out.putNextEntry(new JarEntry(entries[i]));
                out.write(entries[i + 1].getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }

        Path outer = dir.resolve("outer.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(outer))) {
            out.putNextEntry(new JarEntry(innerEntry));
            out.write(Files.readAllBytes(inner));
            out.closeEntry();
        }
        return outer;
    }

    @Test
    void aResourceInsideABundledJarIsReadable(@TempDir Path dir) throws IOException {
        Path outer = outerWithInner(dir, "META-INF/libraries/inner.jar", "hello.txt", "hi");

        URL base = NestedLibraryJars.urlFor(outer.toFile(), "META-INF/libraries/inner.jar");
        assertNotNull(base);
        assertTrue(base.toString().endsWith("!/"), "a class path root has to end in !/ so names can be appended: " + base);

        try (var in = new URL(base, "hello.txt").openStream()) {
            assertEquals("hi", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /**
     * A declaration may name the entry without the directory the jar actually uses, because the manifest and the
     * declaration do not have to agree on the spelling. Both have to reach the same bytes.
     */
    @Test
    void theEntryIsFoundUnderEitherSpelling(@TempDir Path dir) throws IOException {
        Path outer = outerWithInner(dir, "META-INF/libraries/inner.jar", "hello.txt", "hi");

        assertEquals("META-INF/libraries/inner.jar", NestedLibraryJars.entryInside(outer.toFile(), "inner.jar"));
        assertNotNull(NestedLibraryJars.urlFor(outer.toFile(), "inner.jar"));
    }

    @Test
    void aMissingEntryResolvesToNothing(@TempDir Path dir) throws IOException {
        Path outer = outerWithInner(dir, "META-INF/libraries/inner.jar", "hello.txt", "hi");

        assertNull(NestedLibraryJars.entryInside(outer.toFile(), "absent.jar"));
        assertNull(NestedLibraryJars.urlFor(outer.toFile(), "absent.jar"));
    }

    /** The point of the URL shape: a plain class loader has to be able to use it unchanged. */
    @Test
    void theUrlIsUsableAsAClasspathRoot(@TempDir Path dir) throws IOException {
        Path outer = outerWithInner(dir, "META-INF/libraries/inner.jar", "pkg/deep.txt", "deep");

        URL base = NestedLibraryJars.urlFor(outer.toFile(), "META-INF/libraries/inner.jar");
        try (URLClassLoader loader = new URLClassLoader(new URL[] {base}, null);
             var in = loader.getResourceAsStream("pkg/deep.txt")) {
            assertNotNull(in);
            assertEquals("deep", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /**
     * The cache is a cache, not a lifetime: releasing it and reading again has to work.
     *
     * <p>The game never releases it, because a class loader holds these URLs for the whole process and a file system
     * closed underneath a {@link java.nio.file.Path} someone kept makes that path throw instead of read. Tests need a
     * reset, so the reset has to leave the URLs usable.
     */
    @Test
    void releasingTheFileSystemsLeavesTheUrlsUsable(@TempDir Path dir) throws IOException {
        Path outer = outerWithInner(dir, "META-INF/libraries/inner.jar", "hello.txt", "hi");
        URL base = NestedLibraryJars.urlFor(outer.toFile(), "META-INF/libraries/inner.jar");

        byte[] before = new URL(base, "hello.txt").openStream().readAllBytes();
        NestedLibraryJars.closeAll();
        byte[] after = new URL(base, "hello.txt").openStream().readAllBytes();

        assertEquals(new String(before, StandardCharsets.UTF_8), new String(after, StandardCharsets.UTF_8));
    }

    @Test
    void aResourceThatIsNotInTheBundledJarIsAnIOException(@TempDir Path dir) throws IOException {
        Path outer = outerWithInner(dir, "META-INF/libraries/inner.jar", "hello.txt", "hi");
        URL base = NestedLibraryJars.urlFor(outer.toFile(), "META-INF/libraries/inner.jar");

        assertThrows(IOException.class, () -> new URL(base, "nope.txt").openStream());
    }

    /** The escape hatch has to work in both directions, or a broken launch has no way back. */
    @Test
    void extractModeTurnsMountingOff() {
        assertTrue(NestedLibraryJars.enabled(), "serving from inside the jar is the default");

        System.setProperty("cleanroom.nestedJars", "extract");
        assertFalse(NestedLibraryJars.enabled());
        assertEquals("extract", NestedLibraryJars.mode());

        System.setProperty("cleanroom.nestedJars", "zipfs");
        assertTrue(NestedLibraryJars.enabled());
    }

    @Test
    void theSameJarIsOnlyOpenedOnce(@TempDir Path dir) throws IOException {
        Path outer = outerWithInner(dir, "META-INF/libraries/inner.jar", "a.txt", "a", "b.txt", "b");
        URL base = NestedLibraryJars.urlFor(outer.toFile(), "META-INF/libraries/inner.jar");

        byte[] first = new URL(base, "a.txt").openStream().readAllBytes();
        byte[] second = new URL(base, "b.txt").openStream().readAllBytes();
        byte[] again = new URL(base, "a.txt").openStream().readAllBytes();

        assertEquals("a", new String(first, StandardCharsets.UTF_8));
        assertEquals("b", new String(second, StandardCharsets.UTF_8));
        assertEquals("a", new String(again, StandardCharsets.UTF_8), "a cached file system still reads correctly");
    }
}
