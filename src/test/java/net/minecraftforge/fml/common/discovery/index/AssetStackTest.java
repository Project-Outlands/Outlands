package net.minecraftforge.fml.common.discovery.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The game resolves from the end of its pack list, so a stack is told which end wins once, and an archive the stack
 * opened itself is the stack's to close and to notice being replaced.
 */
class AssetStackTest {

    @Test
    void theLastLayerWinsWhenTheStackIsBuiltForTheGamesOrder(@TempDir Path dir) throws Exception {
        final File vanilla = jar(dir, "vanilla.jar", "assets/minecraft/lang/en_us.lang", "vanilla");
        final File pack = jar(dir, "pack.zip", "assets/minecraft/lang/en_us.lang", "pack");

        final AssetStack stack = AssetStack.of(List.of(AssetLayer.of(vanilla), AssetLayer.of(pack)), true);

        assertEquals(List.of(vanilla, pack).get(0), stack.layers().get(0).file(), "the layers stay in the order given");
        assertEquals("pack", new String(stack.read("assets/minecraft/lang/en_us.lang"), StandardCharsets.UTF_8),
                "but the query resolves from the end, which is what FallbackResourceManager does");
        assertEquals(pack, stack.winner("assets/minecraft/lang/en_us.lang").file());
    }

    @Test
    void theFirstLayerWinsWhenTheCallerPutTheHighestFirst(@TempDir Path dir) throws Exception {
        final File first = jar(dir, "first.jar", "assets/x.png", "first");
        final File second = jar(dir, "second.jar", "assets/x.png", "second");

        final AssetStack stack = AssetStack.of(List.of(AssetLayer.of(first), AssetLayer.of(second)), false);

        assertEquals("first", new String(stack.read("assets/x.png"), StandardCharsets.UTF_8));
    }

    @Test
    void anArchiveTheStackOpenedIsClosedByIt(@TempDir Path dir) throws Exception {
        final File pack = jar(dir, "pack.zip", "assets/x.png", "bytes");
        final AssetStack stack = AssetStack.of(List.of(AssetLayer.owned(pack)), false);

        assertArrayEquals("bytes".getBytes(StandardCharsets.UTF_8), stack.read("assets/x.png"));
        assertFalse(stack.stale(), "nothing changed it");

        stack.close();

        assertNull(stack.read("assets/x.png"), "the handle is this stack's, and it is gone");
        assertNull(stack.winner("assets/x.png"), "so is the answer");
    }

    @Test
    void aReplacedArchiveIsReportedAsStale(@TempDir Path dir) throws Exception {
        final File pack = jar(dir, "pack.zip", "assets/x.png", "one");
        final AssetStack stack = AssetStack.of(List.of(AssetLayer.owned(pack)), false);
        assertArrayEquals("one".getBytes(StandardCharsets.UTF_8), stack.read("assets/x.png"));

        // the same path, written again with different content: the handle still serves the old central directory,
        // which is exactly the case the fingerprint exists to catch
        jar(dir, "pack.zip", "assets/x.png", "two-longer-content");

        assertTrue(stack.stale(), "the file behind the layer changed");
        // the handle was opened before the rewrite, so what it answers now is whatever the file system left there:
        // the central directory is the old one while the bytes are the new ones. It must not throw, and a stack that
        // answers stale() is a stack to build again, not to read from.
        assertDoesNotThrow(() -> stack.read("assets/x.png"));
    }

    @Test
    void aBorrowedLayerIsNotTheStacksToClose(@TempDir Path dir) throws Exception {
        final File jar = jar(dir, "on-classpath.jar", "assets/x.png", "bytes");
        new top.outlands.foundation.boot.ClassPathIndex(new java.net.URL[] {jar.toPath().toUri().toURL()});

        final AssetStack stack = AssetStack.of(List.of(AssetLayer.of(jar)), false);
        stack.close();

        assertFalse(stack.stale(), "nothing was opened here");
        // the class loader's handle was borrowed, so closing the stack left it alone and it still serves bytes
        assertArrayEquals("bytes".getBytes(StandardCharsets.UTF_8), AssetLayer.of(jar).read("assets/x.png"),
                "the class loader's handle is still usable");
    }

    private static File jar(Path dir, String name, String entry, String content) throws IOException {
        final File file = dir.resolve(name).toFile();
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file.toPath()))) {
            for (Map.Entry<String, String> e : Map.of(entry, content).entrySet()) {
                out.putNextEntry(new ZipEntry(e.getKey()));
                out.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return file.getAbsoluteFile();
    }
}
