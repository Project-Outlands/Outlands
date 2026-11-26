package net.minecraftforge.fml.common.discovery.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a script asks first: which files are there, and where did each come from. The answer is grouped by layer, so
 * the same path appearing twice is the attribution and not a duplicate to clean up.
 */
class CatalogTest {

    @Test
    void aCatalogListsEveryLayerInResolutionOrder(@TempDir Path dir) throws Exception {
        final Map<String, String> minecraft = new LinkedHashMap<>();
        minecraft.put("data/example/recipes/b.json", "b");
        minecraft.put("data/example/recipes/a.json", "a");
        minecraft.put("data/example/recipes/notes.txt", "not a recipe");
        final File jar = jar(dir, "example.jar", minecraft);
        final File modDir = Files.createDirectories(dir.resolve("dev-mod")).toFile();
        final Path recipe = Files.createDirectories(modDir.toPath().resolve("data/example/recipes")).resolve("a.json");
        Files.writeString(recipe, "override", StandardCharsets.UTF_8);

        final AssetView view = AssetView.archives(List.of(jar, modDir));
        final List<AssetView.Entry> catalog = view.catalog("data/example/recipes/");

        assertEquals(List.of("data/example/recipes/a.json", "data/example/recipes/b.json",
                "data/example/recipes/notes.txt", "data/example/recipes/a.json"),
                catalog.stream().map(AssetView.Entry::path).toList(),
                "one archive sorted, then the directory, with the shared path listed in both");
        assertEquals(jar, catalog.get(0).layer().file());
        assertEquals(modDir, catalog.get(3).layer().file(), "the second copy is attributed to the directory");
    }

    @Test
    void theWinnerIsStillTheFirstLayerUnderAPlainView(@TempDir Path dir) throws Exception {
        final File jar = jar(dir, "a.jar", Map.of("data/x/one.json", "from-jar"));
        final File modDir = Files.createDirectories(dir.resolve("dir")).toFile();
        Files.createDirectories(modDir.toPath().resolve("data/x"));
        Files.writeString(modDir.toPath().resolve("data/x/one.json"), "from-dir", StandardCharsets.UTF_8);

        final AssetView view = AssetView.archives(List.of(jar, modDir));

        assertEquals("from-jar", view.readText("data/x/one.json"), "a catalog is a listing, it does not change the order");
        assertNull(view.readText("data/x/missing.json"));
    }

    @Test
    void theDownloadedHalfCanBeListedToo(@TempDir Path dir) throws Exception {
        final Path assets = assets(dir, Map.of("minecraft/lang/en_us.lang", "lang", "minecraft/lang/zh_cn.lang", "lang2"));
        final AssetObjects objects = AssetObjects.open(assets, "1.12");

        final AssetView view = AssetView.of(List.of(AssetLayer.objects(objects)));
        final List<AssetView.Entry> catalog = view.catalog("assets/minecraft/lang/");

        assertEquals(List.of("assets/minecraft/lang/en_us.lang", "assets/minecraft/lang/zh_cn.lang"),
                catalog.stream().map(AssetView.Entry::path).toList());
        assertEquals("lang", view.readText("assets/minecraft/lang/en_us.lang"));
    }

    @Test
    void aPrefixNobodyHasIsAnEmptyCatalogAndNotAnError(@TempDir Path dir) throws Exception {
        final File jar = jar(dir, "a.jar", Map.of("data/x/one.json", "1"));

        final AssetView view = AssetView.archives(List.of(jar));

        assertTrue(view.catalog("data/nothing/here/").isEmpty());
        assertNull(view.readText("data/nothing/here/x.json"));
        assertNotNull(view.layers(), "the view is still usable");
    }

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
