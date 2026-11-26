package net.minecraftforge.fml.common.discovery.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A resource path resolves to the first layer that has it, and the order is the caller's: a resource pack that the
 * player put last wins over the mod before it, which is what the offline icon has to agree with.
 */
class AssetViewTest {

    @Test
    void theFirstLayerWithThePathWins(@TempDir Path dir) throws Exception {
        final File vanilla = jar(dir, "vanilla.jar", Map.of("assets/minecraft/textures/blocks/stone.png", "v"));
        final File mod = jar(dir, "mod.jar", Map.of("assets/minecraft/textures/blocks/stone.png", "m"));

        final AssetView view = AssetView.archives(List.of(mod, vanilla));

        assertEquals(mod, view.winner("assets/minecraft/textures/blocks/stone.png").file());
        assertArrayEquals("m".getBytes(StandardCharsets.UTF_8), view.read("assets/minecraft/textures/blocks/stone.png"));
    }

    @Test
    void everyLayerThatHasThePathIsListedInResolutionOrder(@TempDir Path dir) throws Exception {
        final File vanilla = jar(dir, "vanilla.jar", Map.of("assets/minecraft/lang/en_us.lang", "v"));
        final File mod = jar(dir, "mod.jar", Map.of("assets/minecraft/lang/en_us.lang", "m"));
        final File pack = jar(dir, "pack.zip", Map.of("assets/minecraft/lang/en_us.lang", "p"));

        final AssetView view = AssetView.archives(List.of(vanilla, mod, pack));

        assertEquals(List.of(vanilla, mod, pack), view.layersOf("assets/minecraft/lang/en_us.lang").stream().map(AssetLayer::file).toList());
        assertEquals(vanilla, view.winner("assets/minecraft/lang/en_us.lang").file(),
                "the first layer is the winner; a caller that wants a pack to override puts it first");
    }

    @Test
    void aPathNoLayerHasIsNotAWinner(@TempDir Path dir) throws Exception {
        final File vanilla = jar(dir, "vanilla.jar", Map.of("assets/minecraft/textures/blocks/stone.png", "v"));
        final AssetView view = AssetView.archives(List.of(vanilla));

        assertNull(view.winner("assets/minecraft/textures/blocks/missing.png"));
        assertNull(view.read("assets/minecraft/textures/blocks/missing.png"));
        assertEquals(0, view.layersOf("assets/minecraft/textures/blocks/missing.png").size());
    }

    @Test
    void aLayerThatCannotBeReadDoesNotStopTheOthers(@TempDir Path dir) throws Exception {
        final File broken = dir.resolve("broken.jar").toFile();
        Files.writeString(broken.toPath(), "not a zip");
        final File mod = jar(dir, "mod.jar", Map.of("assets/x/y.png", "m"));

        final AssetView view = AssetView.archives(List.of(broken, mod));

        assertEquals(mod, view.winner("assets/x/y.png").file());
    }

    @Test
    void theIndexScanWalksEveryArchiveUnderAPrefix(@TempDir Path dir) throws Exception {
        final File gameDir = Files.createDirectories(dir.resolve("game")).toFile();
        final Map<String, String> firstEntries = new LinkedHashMap<>();
        firstEntries.put("a/One.class", "1");
        firstEntries.put("a/Two.class", "2");
        firstEntries.put("b/Three.class", "3");
        final Map<String, String> secondEntries = new LinkedHashMap<>();
        secondEntries.put("a/Four.class", "4");
        secondEntries.put("assets/x/y.txt", "t");
        final File first = jar(dir, "first.jar", firstEntries);
        final File second = jar(dir, "second.jar", secondEntries);

        final LoadIndex index = new LoadIndex(gameDir, List.of(first, second));
        final List<String> seen = new ArrayList<>();
        final int count = index.scan("a/", true, (archive, name) -> seen.add(archive.getName() + "!" + name));

        assertEquals(3, count, "a/One, a/Two from the first archive and a/Four from the second");
        assertEquals(List.of("first.jar!a/One.class", "first.jar!a/Two.class", "second.jar!a/Four.class"),
                seen, "every match is reported once, archive by archive in search order");

        final List<String> everything = new ArrayList<>();
        assertEquals(5, index.scan("", false, (archive, name) -> everything.add(name)));
        assertEquals(5, everything.size());

        final List<String> classesOnly = new ArrayList<>();
        assertEquals(4, index.scan("", true, (archive, name) -> classesOnly.add(name)),
                "the resource is not a class file");
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
