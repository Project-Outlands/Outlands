package net.minecraftforge.fml.common.discovery;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import net.minecraftforge.fml.common.discovery.asm.ASMModParser;
import net.minecraftforge.fml.common.discovery.cache.ClassScanRecord;
import net.minecraftforge.fml.common.discovery.cache.JarFingerprint;
import net.minecraftforge.fml.common.discovery.cache.JarScanRecord;
import net.minecraftforge.fml.common.discovery.cache.RecordFile;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round trip of the annotation scan through the on-disk record, using the classes of this package as the corpus.
 */
public class JarScanCacheTest {

    @Test
    public void coldScanRoundTripsThroughDisk(@TempDir Path temp) throws Exception {
        File gameDir = Files.createDirectories(temp.resolve("game")).toFile();
        File modsDir = Files.createDirectories(gameDir.toPath().resolve("mods")).toFile();
        File jar = new File(modsDir, "sample.jar");
        writeSampleJar(jar);

        List<JarEntry> entries;
        List<Integer> indices = new ArrayList<>();
        List<ClassScanRecord> records = new ArrayList<>();
        JarFingerprint fingerprint;
        try (JarFile jarFile = new JarFile(jar)) {
            entries = Collections.list(jarFile.entries());
            fingerprint = JarFingerprint.compute(entries);
            assertTrue(fingerprint.classEntryCount() > 5, "corpus should have several classes");

            List<JarEntry> scanned = new ArrayList<>();
            for (JarEntry entry : entries) {
                if (ITypeDiscoverer.shouldScan(entry.getName())) {
                    scanned.add(entry);
                }
            }
            List<ParallelJarParse.Parsed> parsed = ParallelJarParse.parse(scanned, jarFile);
            for (int ordinal = 0; ordinal < parsed.size(); ordinal++) {
                ASMModParser parser = parsed.get(ordinal).parser();
                assertNotNull(parser, "sample jar is built from compiled classes");
                parser.validate();
                ClassScanRecord record = parser.toRecord();
                if (JarScanRecord.hasContent(record)) {
                    indices.add(ordinal);
                    records.add(record);
                }
            }
            // interfaces alone are enough for a record to be stored, so the corpus must produce some
            assertFalse(records.isEmpty(), "corpus should contain annotated classes or interfaces");
        }

        File cacheFile = RecordFile.fileFor(gameDir, jar);
        assertNotNull(cacheFile, "jar below the game directory must be cacheable");
        assertTrue(cacheFile.getPath().startsWith(RecordFile.cacheDir(gameDir).getPath()));

        RecordFile.write(cacheFile, JarScanRecord.of(fingerprint, indices, records));
        assertTrue(cacheFile.isFile(), "write must produce the record file");

        JarScanRecord read = RecordFile.read(cacheFile, fingerprint);
        assertNotNull(read, "freshly written record must be readable");
        assertTrue(read.fromDisk(), "read records are flagged as coming from disk");
        assertEquals(fingerprint, read.fingerprint());
        assertArrayEquals(indices.stream().mapToInt(Integer::intValue).toArray(), read.indices());
        assertEquals(indices.size(), read.records().length);
        for (int i = 0; i < indices.size(); i++) {
            assertEquals(describe(records.get(i)), describe(read.records()[i]), "record " + i + " differs");
        }

        // A record is only ever valid for the fingerprint it was taken from.
        assertNull(RecordFile.read(cacheFile, new JarFingerprint(fingerprint.value() ^ 1L, fingerprint.classEntryCount())));
        assertNull(RecordFile.read(cacheFile, new JarFingerprint(fingerprint.value(), fingerprint.classEntryCount() + 1)));

        // A corrupt body is not usable, and must not be handed out twice.
        byte[] bytes = Files.readAllBytes(cacheFile.toPath());
        bytes[bytes.length - 1] ^= 0x7F;
        Files.write(cacheFile.toPath(), bytes);
        assertNull(RecordFile.read(cacheFile, fingerprint), "corrupt record must be rejected");
        assertNull(RecordFile.read(cacheFile, fingerprint), "quarantined record is not retried in this process");
    }

    @Test
    public void fingerprintFollowsScannedEntries(@TempDir Path temp) throws Exception {
        File first = temp.resolve("first.jar").toFile();
        File second = temp.resolve("second.jar").toFile();
        writeSampleJar(first);
        writeSampleJar(second);

        JarFingerprint a;
        JarFingerprint b;
        try (JarFile jar = new JarFile(first)) {
            a = JarFingerprint.compute(Collections.list(jar.entries()));
        }
        try (JarFile jar = new JarFile(second)) {
            b = JarFingerprint.compute(Collections.list(jar.entries()));
        }
        assertEquals(a, b, "identical jars must fingerprint identically");

        // a jar that is not below the game directory is outside the cache tree
        assertNull(RecordFile.fileFor(temp.resolve("game").toFile(), first));
    }

    @Test
    public void sweepDropsRecordsOfMissingJars(@TempDir Path temp) throws Exception {
        File gameDir = Files.createDirectories(temp.resolve("game")).toFile();
        File modsDir = Files.createDirectories(gameDir.toPath().resolve("mods")).toFile();
        File present = new File(modsDir, "present.jar");
        writeSampleJar(present);
        File gone = new File(modsDir, "gone.jar");
        writeSampleJar(gone);

        JarFingerprint fingerprint;
        try (JarFile jar = new JarFile(present)) {
            fingerprint = JarFingerprint.compute(Collections.list(jar.entries()));
        }
        File presentCache = RecordFile.fileFor(gameDir, present);
        File goneCache = RecordFile.fileFor(gameDir, gone);
        RecordFile.write(presentCache, JarScanRecord.of(fingerprint, List.of(), List.of()));
        RecordFile.write(goneCache, JarScanRecord.of(fingerprint, List.of(), List.of()));
        assertTrue(presentCache.isFile());
        assertTrue(goneCache.isFile());

        //noinspection ResultOfMethodCallIgnored
        gone.delete();
        RecordFile.sweep(gameDir);

        assertTrue(presentCache.isFile(), "record of an existing jar must survive");
        assertFalse(goneCache.isFile(), "record of a removed jar must be swept");
    }

    private static void assertArrayEquals(int[] expected, int[] actual) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], "index " + i);
        }
    }

    /** Deterministic rendering of a record; annotation value maps are ordered by key. */
    private static String describe(ClassScanRecord record) {
        StringBuilder out = new StringBuilder(record.internalName()).append('|').append(record.classVersion());
        for (String intf : record.interfaces()) {
            out.append("|i:").append(intf);
        }
        for (ClassScanRecord.Annotation annotation : record.annotations()) {
            out.append("|a:").append(annotation.annotationName()).append('#').append(annotation.member()).append('=');
            if (annotation.values() == null) {
                out.append("<null>");
                continue;
            }
            for (Map.Entry<String, Object> value : new java.util.TreeMap<>(annotation.values()).entrySet()) {
                out.append(value.getKey()).append('=').append(render(value.getValue())).append(',');
            }
        }
        return out.toString();
    }

    private static String render(Object value) {
        if (value instanceof Object[] array) {
            StringBuilder out = new StringBuilder("[");
            for (Object element : array) {
                out.append(render(element)).append(';');
            }
            return out.append(']').toString();
        }
        if (value instanceof java.util.List<?> list) {
            StringBuilder out = new StringBuilder("(");
            for (Object element : list) {
                out.append(render(element)).append(';');
            }
            return out.append(')').toString();
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder out = new StringBuilder("{");
            for (Map.Entry<?, ?> entry : new java.util.TreeMap<>(map).entrySet()) {
                out.append(entry.getKey()).append('=').append(render(entry.getValue())).append(',');
            }
            return out.append('}').toString();
        }
        return String.valueOf(value);
    }

    /** Zips the compiled classes of this package into {@code jar}, so the corpus needs no external test data. */
    private static void writeSampleJar(File jar) throws IOException, URISyntaxException {
        URL url = JarScanCacheTest.class.getResource("ASMDataTable.class");
        assertNotNull(url, "this package must be on the test classpath");
        Path directory = Path.of(url.toURI()).getParent();
        assertNotNull(directory, "classpath entry must be a directory for this test");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar.toPath()))) {
            try (java.util.stream.Stream<Path> files = Files.list(directory)) {
                for (Path file : files.sorted().toList()) {
                    String name = file.getFileName().toString();
                    if (!name.endsWith(".class")) {
                        continue;
                    }
                    out.putNextEntry(new JarEntry(name));
                    try (InputStream in = Files.newInputStream(file)) {
                        copy(in, out);
                    }
                    out.closeEntry();
                }
            }
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
    }
}
