package net.minecraftforge.fml.common.discovery.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A development run ships its resources as a directory, so a layer has to read one: a view that only knew archives
 * silently answered nothing for every dev mod, and threw instead of missing when asked for the bytes.
 */
class DirectoryLayerTest {

    @Test
    void aDirectoryAnswersLikeAnArchiveWould(@TempDir Path dir) throws Exception {
        final File modDir = Files.createDirectories(dir.resolve("mod")).toFile();
        final Path file = Files.createDirectories(modDir.toPath().resolve("assets/minecraft/lang")).resolve("en_us.lang");
        Files.writeString(file, "lang", StandardCharsets.UTF_8);

        final AssetLayer layer = AssetLayer.of(modDir);

        assertTrue(layer.has("assets/minecraft/lang/en_us.lang"), "the same path spelling an archive would use");
        assertArrayEquals("lang".getBytes(StandardCharsets.UTF_8), layer.read("assets/minecraft/lang/en_us.lang"));
        assertFalse(layer.has("assets/minecraft/lang/zh_cn.lang"));
        assertNull(layer.read("assets/minecraft/lang/zh_cn.lang"));
        assertEquals(modDir, layer.file());
    }

    @Test
    void aDirectoryAndAnArchiveCanSitInOneView(@TempDir Path dir) throws Exception {
        final File modDir = Files.createDirectories(dir.resolve("dev-mod")).toFile();
        Files.createDirectories(modDir.toPath().resolve("assets/minecraft/textures"))
                .resolve("from-dir.png").toFile();
        Files.writeString(modDir.toPath().resolve("assets/minecraft/textures/from-dir.png"), "dir", StandardCharsets.UTF_8);
        final File jar = jar(dir, "pack.jar", Map.of("assets/minecraft/textures/from-jar.png", "jar"));

        final AssetView view = AssetView.archives(java.util.List.of(jar, modDir));

        // the jar wins where it has the file, the directory answers for what the jar does not have
        assertArrayEquals("jar".getBytes(StandardCharsets.UTF_8), view.read("assets/minecraft/textures/from-jar.png"));
        assertArrayEquals("dir".getBytes(StandardCharsets.UTF_8), view.read("assets/minecraft/textures/from-dir.png"));
        assertEquals(modDir, view.winner("assets/minecraft/textures/from-dir.png").file());
    }

    @Test
    void aPathCannotWalkOutOfTheDirectory(@TempDir Path dir) throws Exception {
        final Path modDir = Files.createDirectories(dir.resolve("mod"));
        final Path secret = dir.resolve("secret.txt");
        Files.writeString(secret, "secret", StandardCharsets.UTF_8);

        final AssetLayer layer = AssetLayer.of(modDir.toFile());

        assertFalse(layer.has("../secret.txt"), "a resource path is a path in the product, not on the disk");
        assertNull(layer.read("../secret.txt"));
        assertNull(layer.read("../secret.txt".replace('/', '\\')), "and not the Windows spelling of it either");
    }

    @Test
    void aDirectoryThatCannotBeReadIsAMissAndNotAnException(@TempDir Path dir) throws Exception {
        final File broken = dir.resolve("broken.jar").toFile();
        Files.writeString(broken.toPath(), "not a zip");

        final AssetLayer layer = AssetLayer.archive(broken);

        assertFalse(layer.has("assets/x.png"));
        assertNull(layer.read("assets/x.png"), "an unreadable layer answers a miss, the same as has()");
    }

    private static File jar(Path dir, String name, Map<String, String> entries) throws IOException {
        final File file = dir.resolve(name).toFile();
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file.toPath()))) {
            for (Map.Entry<String, String> entry : new LinkedHashMap<>(entries).entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return file.getAbsoluteFile();
    }
}
