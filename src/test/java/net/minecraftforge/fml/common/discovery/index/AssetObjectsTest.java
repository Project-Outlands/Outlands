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
 * Vanilla's downloaded half is optional: a launcher that could not download, a download that stopped halfway and an
 * index that was never written are all states a modded instance runs in, and the jar's half has to keep answering.
 */
class AssetObjectsTest {

    @Test
    void theIndexNamesTheObjectAndTheObjectIsWhatIsRead(@TempDir Path dir) throws Exception {
        final Path assets = assets(dir, Map.of("minecraft/lang/en_us.lang", "hello"));

        final AssetObjects objects = AssetObjects.open(assets, "1.12");

        assertTrue(objects.available());
        assertEquals(1, objects.size());
        assertTrue(objects.lists("assets/minecraft/lang/en_us.lang"));
        assertTrue(objects.has("minecraft:lang/en_us.lang"), "both spellings of the name are the same name");
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), objects.read("assets/minecraft/lang/en_us.lang"));
    }

    @Test
    void aMissingIndexIsAnEmptyTableAndNotAnError(@TempDir Path dir) throws Exception {
        final Path assets = Files.createDirectories(dir.resolve("assets"));

        final AssetObjects objects = AssetObjects.open(assets, "1.12");

        assertFalse(objects.available());
        assertEquals(0, objects.size());
        assertFalse(objects.has("assets/minecraft/lang/en_us.lang"));
        assertNull(objects.read("assets/minecraft/lang/en_us.lang"));
    }

    @Test
    void anIndexThatWasNeverDownloadedIsTheSameAsNoIndex(@TempDir Path dir) throws Exception {
        assertFalse(AssetObjects.open(null).available());
        assertFalse(AssetObjects.open(dir.resolve("not-there")).available());
    }

    @Test
    void aHalfWrittenIndexIsSkippedAndTheRunGoesOn(@TempDir Path dir) throws Exception {
        final Path assets = dir.resolve("assets");
        final Path indexes = Files.createDirectories(assets.resolve("indexes"));
        Files.writeString(indexes.resolve("1.12.json"), "{\"objects\": {\"minecraft/lang/en_us.lang\": {\"ha", StandardCharsets.UTF_8);

        final AssetObjects objects = AssetObjects.open(assets, "1.12");

        assertFalse(objects.available(), "a truncated index answered nothing and threw nothing");
        assertNull(objects.read("assets/minecraft/lang/en_us.lang"));
    }

    @Test
    void anObjectThatWasNotDownloadedIsAListedMiss(@TempDir Path dir) throws Exception {
        final Path assets = dir.resolve("assets");
        // the index names it, the file was never downloaded: a real state after an interrupted download
        final Path indexes = Files.createDirectories(assets.resolve("indexes"));
        Files.createDirectories(assets.resolve("objects"));
        Files.writeString(indexes.resolve("1.12.json"),
                "{\"objects\":{\"minecraft/lang/en_us.lang\":{\"hash\":\"ab3f00\",\"size\":5}}}", StandardCharsets.UTF_8);

        final AssetObjects objects = AssetObjects.open(assets, "1.12");

        assertTrue(objects.available());
        assertTrue(objects.lists("assets/minecraft/lang/en_us.lang"));
        assertFalse(objects.has("assets/minecraft/lang/en_us.lang"), "the index lists it, the disk does not have it");
        assertNull(objects.read("assets/minecraft/lang/en_us.lang"));
    }

    @Test
    void theExistenceAnswerIsRemembered(@TempDir Path dir) throws Exception {
        final Path assets = assets(dir, Map.of("minecraft/lang/en_us.lang", "hello"));
        final AssetObjects objects = AssetObjects.open(assets, "1.12");
        assertTrue(objects.has("assets/minecraft/lang/en_us.lang"));

        // the launcher wrote the objects before the game started; one disappearing mid-run is an install that changed
        // under us, not something a lookup can paper over, and re-statting every one of them cost 1.15 us each
        for (Path file : (Iterable<Path>) Files.walk(assets.resolve("objects"))::iterator) {
            if (Files.isRegularFile(file)) {
                Files.delete(file);
            }
        }
        assertTrue(objects.has("assets/minecraft/lang/en_us.lang"), "the answer was remembered");
        assertTrue(objects.lists("assets/minecraft/lang/en_us.lang"), "and the index still knows the name");
    }

    @Test
    void theJarAnswersWhenTheDownloadedHalfIsMissing(@TempDir Path dir) throws Exception {
        final Path assets = dir.resolve("assets");
        Files.createDirectories(assets);
        final Map<String, String> entries = new LinkedHashMap<>();
        entries.put("assets/minecraft/textures/blocks/stone.png", "jar");
        entries.put("assets/minecraft/lang/en_us.lang", "jar-lang");
        final File jar = jar(dir, "minecraft.jar", entries);

        // no index, so the view is the jar alone: the state of a run that never reached the asset server
        final AssetView view = AssetView.vanilla(jar, AssetObjects.absent());

        assertEquals(1, view.layers().size());
        assertArrayEquals("jar-lang".getBytes(StandardCharsets.UTF_8),
                view.read("assets/minecraft/lang/en_us.lang"), "the jar keeps answering for everything");
    }

    @Test
    void theDownloadedHalfWinsOverTheJarAndFallsBackToIt(@TempDir Path dir) throws Exception {
        final Path assets = assets(dir, Map.of("minecraft/lang/en_us.lang", "downloaded"));
        final Map<String, String> entries = new LinkedHashMap<>();
        entries.put("assets/minecraft/lang/en_us.lang", "jar-lang");
        entries.put("assets/minecraft/textures/blocks/stone.png", "jar-png");
        final File jar = jar(dir, "minecraft.jar", entries);

        final AssetView view = AssetView.vanilla(jar, AssetObjects.open(assets, "1.12"));

        assertEquals(2, view.layers().size(), "the objects, then the jar");
        assertArrayEquals("downloaded".getBytes(StandardCharsets.UTF_8),
                view.read("assets/minecraft/lang/en_us.lang"), "the game reads the downloaded half first");
        assertArrayEquals("jar-png".getBytes(StandardCharsets.UTF_8),
                view.read("assets/minecraft/textures/blocks/stone.png"), "the jar's own half is untouched");
        assertNull(view.winner("assets/minecraft/lang/en_us.lang").file(), "the winner is a directory of objects");
        assertEquals(jar, view.winner("assets/minecraft/textures/blocks/stone.png").file());
    }

    /** Writes the objects and the index that names them, the way a launcher leaves them behind. */
    private static Path assets(Path dir, Map<String, String> files) throws IOException {
        final Path assets = dir.resolve("assets");
        final StringBuilder rows = new StringBuilder();
        for (Map.Entry<String, String> entry : files.entrySet()) {
            final byte[] bytes = entry.getValue().getBytes(StandardCharsets.UTF_8);
            final String hash = sha1(bytes);
            final Path file = assets.resolve("objects").resolve(hash.substring(0, 2)).resolve(hash);
            Files.createDirectories(file.getParent());
            Files.write(file, bytes);
            if (rows.length() > 0) {
                rows.append(',');
            }
            rows.append('"').append(entry.getKey()).append("\":{\"hash\":\"").append(hash)
                    .append("\",\"size\":").append(bytes.length).append('}');
        }
        final Path indexes = Files.createDirectories(assets.resolve("indexes"));
        Files.writeString(indexes.resolve("1.12.json"), "{\"objects\":{" + rows + "}}", StandardCharsets.UTF_8);
        return assets;
    }

    private static String sha1(byte[] bytes) throws IOException {
        try {
            final java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-1");
            final StringBuilder out = new StringBuilder();
            for (byte b : digest.digest(bytes)) {
                out.append(String.format("%02x", b));
            }
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static File jar(Path dir, String name, Map<String, String> entries) throws IOException {
        final File file = dir.resolve(name).toFile();
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file.toPath()))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return file.getAbsoluteFile();
    }
}
