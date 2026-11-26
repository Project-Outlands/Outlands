package net.minecraftforge.fml.common.discovery.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import top.outlands.foundation.boot.ArchiveSet;
import top.outlands.foundation.boot.ClassPathIndex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A reader either borrows the class loader's handle or opens its own, and closing it must not close a handle that is
 * not its own: the class loader reads classes through that one for the rest of the process.
 */
class BorrowedArchiveTest {

    @Test
    void aJarTheClassLoaderHasIsBorrowedRatherThanOpenedAgain(@TempDir Path dir) throws Exception {
        final Path jar = jar(dir, "on-classpath.jar");
        // the default set is the process set, which is what the loader uses
        final ClassPathIndex classPath = new ClassPathIndex(new URL[] {jar.toUri().toURL()});
        assertNotNull(classPath.lookup("a/B.class"));

        // the class path index opens through the process set, so the borrow comes from there
        final BorrowedArchive borrow = BorrowedArchive.of(jar.toFile(), false);
        try {
            assertSame(ArchiveSet.classPath().of(jar).jarFile(), borrow.jar());
        } finally {
            borrow.close();
        }
        assertFalse(ArchiveSet.classPath().of(jar) == null, "closing a borrowed handle must not close the class loader's");
        assertEquals("b", new String((byte[]) classPath.read("a/B.class"), StandardCharsets.UTF_8));
    }

    @Test
    void aJarNobodyHasOpenIsClosedByItsReader(@TempDir Path dir) throws Exception {
        final Path jar = jar(dir, "private.jar");

        final BorrowedArchive borrow = BorrowedArchive.of(jar.toFile());
        final java.util.jar.JarFile handle = borrow.jar();
        borrow.close();

        assertThrows(IllegalStateException.class, handle::size, "the private handle is this reader's to close");
    }

    private static Path jar(Path dir, String name) throws IOException {
        final Path file = dir.resolve(name);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file))) {
            for (Map.Entry<String, String> entry : Map.of("a/B.class", "b").entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return file;
    }
}
