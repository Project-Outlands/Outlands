package top.outlands.install;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.Adler32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import net.minecraftforge.fml.common.patcher.ClassPatchReader;
import net.minecraftforge.fml.repackage.com.nothome.delta.Delta;
import net.minecraftforge.fml.repackage.com.nothome.delta.GDiffPatcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the install step's worker without a game: a synthetic pack in the real entry format, with real GDiff
 * payloads, applied to a synthetic jar. The load-bearing properties are that the applier writes an overlay of
 * exactly the patched classes, and that it refuses to guess when its input is not what the patches expect.
 */
class PatchApplierTest {

    @TempDir
    Path dir;

    @Test
    void writesOnlyTheClassesThePatchesRewrite() throws Exception {
        final byte[] untouched = bytes("untouched vanilla class");
        final byte[] source = bytes("original class bytes, the ones the patch was built against");
        final byte[] target = bytes("original class bytes, the ones the patch was built against, plus the patch");

        final File vanilla = jar("vanilla.jar", Map.of("aaa.class", untouched, "bbb.class", source));
        final File output = dir.resolve("out.jar").toFile();
        final byte[] pack = pack(Map.of("binpatch/client/bbb.binpatch",
                entry("bbb", "net/minecraft/Block", true, source, target)));

        final PatchApplier.Report report = PatchApplier.apply(pack, vanilla, output, "client");

        assertEquals(1, report.records());
        assertEquals(1, report.written());
        assertEquals(0, report.skipped());
        assertEquals(Set.of("bbb.class"), entries(output),
                "the overlay must not carry untouched vanilla classes, they keep coming from the vanilla jar");
        assertArrayEquals(target, entryBytes(output, "bbb.class"));
    }

    @Test
    void skipsRecordsWhoseClassThePatchesRemove() throws Exception {
        final byte[] source = bytes("class the patches delete");
        final File vanilla = jar("vanilla.jar", Map.of("aaa.class", source));
        final File output = dir.resolve("out.jar").toFile();
        final byte[] pack = pack(Map.of("binpatch/client/aaa.binpatch",
                entry("aaa", "net/minecraft/Thing", false, source, bytes("gone"))));

        final PatchApplier.Report report = PatchApplier.apply(pack, vanilla, output, "client");

        assertEquals(1, report.records());
        assertEquals(0, report.written());
        assertEquals(1, report.skipped());
        assertEquals(Set.of(), entries(output));
    }

    @Test
    void refusesAnInputJarThePatchesWereNotBuiltAgainst() throws Exception {
        final byte[] source = bytes("bytes A");
        final byte[] target = bytes("bytes B");
        final File vanilla = jar("vanilla.jar", Map.of("aaa.class", source));
        final File output = dir.resolve("out.jar").toFile();
        final byte[] pack = pack(Map.of("binpatch/client/aaa.binpatch",
                entry("aaa", "net/minecraft/Thing", true, bytes("bytes the patch never saw"), target)));

        final IOException e = assertThrows(IOException.class,
                () -> PatchApplier.apply(pack, vanilla, output, "client"));
        assertTrue(e.getMessage().contains("checksum mismatch"), e.getMessage());
    }

    @Test
    void refusesWhenAPatchedClassIsMissing() throws Exception {
        final byte[] source = bytes("bytes A");
        final File vanilla = jar("vanilla.jar", Map.of("aaa.class", source));
        final File output = dir.resolve("out.jar").toFile();
        final byte[] pack = pack(Map.of("binpatch/client/zzz.binpatch",
                entry("zzz", "net/minecraft/Missing", true, source, bytes("bytes B"))));

        final IOException e = assertThrows(IOException.class,
                () -> PatchApplier.apply(pack, vanilla, output, "client"));
        assertTrue(e.getMessage().contains("not the jar the patches were built against"), e.getMessage());
    }

    @Test
    void appliesOneSideOnly() throws Exception {
        final byte[] source = bytes("side specific source");
        final byte[] client = bytes("side specific client");
        final byte[] server = bytes("side specific server");
        final File vanilla = jar("vanilla.jar", Map.of("aaa.class", source));
        final File output = dir.resolve("out.jar").toFile();
        final byte[] pack = pack(Map.of(
                "binpatch/client/aaa.binpatch", entry("aaa", "net/minecraft/Thing", true, source, client),
                "binpatch/server/aaa.binpatch", entry("aaa", "net/minecraft/Thing", true, source, server)));

        PatchApplier.apply(pack, vanilla, output, "client");

        assertArrayEquals(client, entryBytes(output, "aaa.class"));
    }

    @Test
    void readerAndApplierAgreeOnTheEntryFormat() throws Exception {
        final byte[] source = bytes("payload for the reader");
        final byte[] target = bytes("payload for the reader, patched");
        final byte[] patch = gdiff(source, target);
        final byte[] pack = pack(Map.of("binpatch/client/aaa.binpatch",
                rawEntry("aaa", "net/minecraft/Thing", true, adler(source), patch)));

        final var patches = ClassPatchReader.readStrict(pack, "client");

        assertEquals(1, patches.size());
        assertEquals("aaa", patches.getFirst().name);
        assertEquals("net.minecraft.Thing", patches.getFirst().targetClassName);
        assertTrue(patches.getFirst().existsAtTarget);
        assertEquals(adler(source), patches.getFirst().inputChecksum);
        assertArrayEquals(patch, patches.getFirst().patch);
        assertArrayEquals(target, new GDiffPatcher().patch(source, patches.getFirst().patch));
    }

    @Test
    void twoRunsOverTheSameInputsProduceTheSameBytes() throws Exception {
        final byte[] source = bytes("same source");
        final byte[] target = bytes("a different, longer result");
        final File vanilla = jar("vanilla.jar", Map.of("aab.class", source, "aaa.class", source));
        final byte[] pack = pack(Map.of(
                "binpatch/client/aaa.binpatch", entry("aaa", "net/minecraft/Thing", true, source, target),
                "binpatch/client/aab.binpatch", entry("aab", "net/minecraft/Other", true, source, target)));

        final File first = dir.resolve("first.jar").toFile();
        final File second = dir.resolve("second.jar").toFile();
        PatchApplier.apply(pack, vanilla, first, "client");
        PatchApplier.apply(pack, vanilla, second, "client");

        assertArrayEquals(Files.readAllBytes(first.toPath()), Files.readAllBytes(second.toPath()),
                "the marker fingerprints the artifact, so the same inputs must produce the same bytes");
        assertEquals(Set.of("aaa.class", "aab.class"), entries(first),
                "entries are written in a stable order regardless of the pack's ordering");
    }

    // ---------------------------------------------------------------- fixtures

    /** A pack entry in the real format, with the GDiff and the input checksum the format demands. */
    private static byte[] entry(String name, String targetName, boolean exists, byte[] source, byte[] target)
            throws IOException {
        return rawEntry(name, targetName, exists, exists ? adler(source) : 0, gdiff(source, target));
    }

    private static byte[] rawEntry(String name, String targetName, boolean exists, int checksum, byte[] patch)
            throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(ClassPatchReader.FORMAT_VERSION);
        out.writeUTF(name);
        out.writeUTF(targetName);
        out.writeBoolean(exists);
        if (exists) {
            out.writeInt(checksum);
        }
        out.writeInt(patch.length);
        out.write(patch);
        out.flush();
        return bytes.toByteArray();
    }

    private static byte[] gdiff(byte[] source, byte[] target) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        new Delta().compute(source, target, out);
        final byte[] patch = out.toByteArray();
        // guards the fixture itself: if the writer's format ever changed, the test would otherwise pass for the
        // wrong reason
        assertArrayEquals(target, new GDiffPatcher().patch(source, patch));
        return patch;
    }

    /** A decompressed patch pack: a jar of {@code binpatch/<side>/<class>.binpatch} entries. */
    private static byte[] pack(Map<String, byte[]> entries) throws IOException {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(e.getKey()));
                out.write(e.getValue());
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private File jar(String name, Map<String, byte[]> entries) throws IOException {
        final File file = dir.resolve(name).toFile();
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file.toPath()))) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(e.getKey()));
                out.write(e.getValue());
                out.closeEntry();
            }
        }
        return file;
    }

    private static Set<String> entries(File jar) throws IOException {
        try (ZipFile zf = new ZipFile(jar)) {
            return new LinkedHashMap<>(mapOf(zf)).keySet();
        }
    }

    private static byte[] entryBytes(File jar, String name) throws IOException {
        try (ZipFile zf = new ZipFile(jar)) {
            final ZipEntry entry = zf.getEntry(name);
            assertTrue(entry != null, name + " missing from " + jar.getName());
            try (InputStream in = zf.getInputStream(entry)) {
                return in.readAllBytes();
            }
        }
    }

    private static Map<String, byte[]> mapOf(ZipFile zf) throws IOException {
        final Map<String, byte[]> map = new LinkedHashMap<>();
        for (ZipEntry e : zf.stream().toList()) {
            try (InputStream in = zf.getInputStream(e)) {
                map.put(e.getName(), in.readAllBytes());
            }
        }
        return map;
    }

    private static int adler(byte[] bytes) {
        final Adler32 adler = new Adler32();
        adler.update(bytes, 0, bytes.length);
        return (int) adler.getValue();
    }

    private static byte[] bytes(String text) {
        return text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
}
