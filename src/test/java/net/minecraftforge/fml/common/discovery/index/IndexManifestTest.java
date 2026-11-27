package net.minecraftforge.fml.common.discovery.index;

import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.minecraftforge.fml.common.discovery.SkippedJars;
import net.minecraftforge.fml.common.discovery.cache.ClassScanRecord;
import net.minecraftforge.fml.common.discovery.cache.JarFingerprint;
import net.minecraftforge.fml.common.discovery.cache.JarScanRecord;
import net.minecraftforge.fml.common.discovery.cache.RecordFile;

import com.google.gson.Gson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The manifest is what a reader that is not the game process opens: the class path in resolution order, what was left
 * out, and which files of which archive are there.
 */
class IndexManifestTest {

    private static File writeJar(File file, String... entries) throws Exception {
        //noinspection ResultOfMethodCallIgnored
        file.getParentFile().mkdirs();
        try (OutputStream out = Files.newOutputStream(file.toPath());
                JarOutputStream jar = new JarOutputStream(out)) {
            for (String entry : entries) {
                jar.putNextEntry(new JarEntry(entry));
                jar.write(("entry " + entry).getBytes(StandardCharsets.UTF_8));
                jar.closeEntry();
            }
        }
        return file;
    }

    private static JarFingerprint fingerprintOf(File jar) throws Exception {
        try (java.util.jar.JarFile file = new java.util.jar.JarFile(jar)) {
            return JarFingerprint.compute(file.entries());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void theManifestNamesTheClassPathAndWhatEachArchiveHas(@TempDir Path dir) throws Exception {
        final File gameDir = dir.toFile();
        final File jar = writeJar(new File(gameDir, "mods/thing.jar"), "a/One.class", "META-INF/MANIFEST.MF");
        final JarFingerprint fingerprint = fingerprintOf(jar);

        final LoadIndex index = new LoadIndex(gameDir, List.of(jar));
        index.register(jar, new JarScanRecord(fingerprint, false, new int[] {0},
                new ClassScanRecord[] {new ClassScanRecord("a/One", 52, "java/lang/Object", 1,
                        new String[0], new ClassScanRecord.Annotation[0])}));
        RecordFile.write(RecordFile.fileFor(gameDir, jar),
                new JarScanRecord(fingerprint, false, new int[] {0},
                        new ClassScanRecord[] {new ClassScanRecord("a/One", 52, "java/lang/Object", 1,
                                new String[0], new ClassScanRecord.Annotation[0])}));
        SkippedJars.record(new File(gameDir, "mods/old.jar"), "contained copy lost version selection");

        try {
            final Map<String, Object> json = new Gson().fromJson(
                    IndexManifest.render(gameDir, index, "test"), Map.class);
            assertEquals(1.0, json.get("format"));
            assertEquals("test", json.get("reason"));
            assertNotNull(json.get("builtAt"));

            final List<Map<String, Object>> sources = (List<Map<String, Object>>) json.get("sources");
            assertEquals(1, sources.size());
            final Map<String, Object> source = sources.get(0);
            assertEquals("mods/thing.jar", source.get("path"));
            assertEquals("mod", source.get("role"));
            assertEquals("jar", source.get("kind"));
            assertEquals(Long.toHexString(fingerprint.value()), source.get("fingerprint"));

            final Map<String, Object> sections = (Map<String, Object>) source.get("sections");
            assertTrue(sections.containsKey("headers"), "the class record that discovery wrote is named");
            assertFalse(sections.containsKey("detail"), "nobody asked for detail yet, so it is absent, not broken");

            final Map<String, Object> keys = (Map<String, Object>) json.get("keys");
            assertEquals(64, ((String) keys.get("chunks")).length(), "a sha256, not a version number");
            assertEquals(64, ((String) keys.get("order")).length());

            final List<Map<String, Object>> excluded = (List<Map<String, Object>>) json.get("excluded");
            assertEquals(1, excluded.size());
            assertEquals("mods/old.jar", excluded.get(0).get("path"));

            final Map<String, Object> flags = (Map<String, Object>) json.get("flags");
            assertEquals(false, flags.get("dev"));
            assertEquals(false, flags.get("detail"));
            assertEquals(false, flags.get("registry"));
        } finally {
            SkippedJars.clear();
        }
    }

    @Test
    void writingPublishesTheManifestAndKeepsThePreviousOne(@TempDir Path dir) throws Exception {
        final File gameDir = dir.toFile();
        final File jar = writeJar(new File(gameDir, "mods/thing.jar"), "a/One.class");
        final LoadIndex index = new LoadIndex(gameDir, List.of(jar));

        index.writeManifest("discovery");
        final File manifest = new File(RecordFile.cacheDir(gameDir), "manifest.json");
        assertTrue(manifest.isFile());
        assertTrue(new String(Files.readAllBytes(manifest.toPath()), StandardCharsets.UTF_8).contains("\"discovery\""));

        index.writeManifest("dump");
        final File previous = new File(RecordFile.cacheDir(gameDir), "manifest.prev.json");
        assertTrue(previous.isFile(), "the previous generation stays readable");
        assertTrue(new String(Files.readAllBytes(previous.toPath()), StandardCharsets.UTF_8).contains("\"discovery\""));
        assertTrue(new String(Files.readAllBytes(manifest.toPath()), StandardCharsets.UTF_8).contains("\"dump\""));
    }
}
