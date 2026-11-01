/*
 * Outlands addition. Same licence as the rest of FML (LGPL 2.1).
 */

package top.outlands.install;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.Adler32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import LZMA.LzmaInputStream;
import net.minecraftforge.fml.common.patcher.ClassPatch;
import net.minecraftforge.fml.common.patcher.ClassPatchReader;
import net.minecraftforge.fml.repackage.com.nothome.delta.GDiffPatcher;

/**
 * Applies the binary patch pack to a vanilla Minecraft jar and writes the result as an overlay jar.
 *
 * <p>This is the install step's worker. Modern loaders run an installer on first launch and keep the produced
 * jars next to their libraries; we do the same, except that the "installer" is this class and it is invoked by
 * {@code top.outlands.foundation} before the game's class loader exists (see {@code docs/install-step.md} in the
 * Foundation repository for the whole model).
 *
 * <p>The output is an <b>overlay</b>: only the classes the patches rewrite, nothing else. That makes it provably
 * equivalent to what runtime patching produces for those classes, and it lets everything else keep coming from the
 * vanilla jar the launcher already has. A full jar would have to be assembled from our recompiled tree, which would
 * also shadow classes that differ by nothing but line tables.
 *
 * <p>The classpath of the JVM that runs this is just the loader jar and the LZMA decoder, so nothing here may touch
 * Guava, log4j or any other FML dependency.
 *
 * <pre>
 *   java -cp outlands.jar:lzma.jar top.outlands.install.PatchApplier \
 *        --input &lt;vanilla client jar&gt; --output &lt;patched overlay jar&gt; [--side client|server] [--pack &lt;file&gt;]
 * </pre>
 */
public final class PatchApplier {
    /** The resource the loader reads at runtime; the same pack is the install step's input. */
    public static final String PACK_RESOURCE = "/binpatches.pack.lzma";

    /** Fixed entry time so the same inputs produce the same bytes, which keeps the marker meaningful. */
    private static final long ENTRY_TIME = 0L;

    private PatchApplier() {
    }

    /** What one run did, for the caller's log line and the marker. */
    public record Report(int records, int written, int skipped, long classBytes) {
        @Override
        public String toString() {
            return records + " records, " + written + " written, " + skipped + " skipped, " + classBytes
                    + " bytes of class data";
        }
    }

    /**
     * Applies every patch of one side, in a stable order, writing only the classes it rewrites.
     *
     * @param packJar an already decompressed pack
     * @throws IOException on a damaged pack, an input jar that is not the one the patches were built against, or a
     *                     patch whose expected input checksum does not match
     */
    public static Report apply(byte[] packJar, File input, File output, String side) throws IOException {
        final List<ClassPatch> patches = ClassPatchReader.readStrict(packJar, side);
        // Stable output: the patch pack's own order is whatever the packer emitted, and the marker fingerprints the
        // jar by its entries.
        patches.sort(Comparator.comparing(patch -> patch.name));

        final GDiffPatcher patcher = new GDiffPatcher();
        int written = 0;
        int skipped = 0;
        long classBytes = 0L;

        try (ZipFile in = new ZipFile(input);
             JarOutputStream out = new JarOutputStream(new BufferedOutputStream(new FileOutputStream(output)))) {
            for (ClassPatch patch : patches) {
                if (!patch.existsAtTarget) {
                    // The patches remove this class; there is nothing to write and nothing to read.
                    skipped++;
                    continue;
                }
                final ZipEntry source = in.getEntry(patch.name + ".class");
                if (source == null) {
                    throw new IOException(patch.name + " is patched but missing from " + input.getName()
                            + "; this is not the jar the patches were built against");
                }
                final byte[] bytes = in.getInputStream(source).readAllBytes();
                final int checksum = adler32(bytes);
                if (patch.inputChecksum != 0 && checksum != patch.inputChecksum) {
                    throw new IOException(String.format(
                            "patch input checksum mismatch for %s: patch expects %s, jar has %s",
                            patch.name, Integer.toHexString(patch.inputChecksum), Integer.toHexString(checksum)));
                }
                final byte[] patched = patcher.patch(bytes, patch.patch);
                final JarEntry entry = new JarEntry(patch.name + ".class");
                entry.setTime(ENTRY_TIME);
                out.putNextEntry(entry);
                out.write(patched);
                out.closeEntry();
                classBytes += patched.length;
                written++;
            }
        }
        return new Report(patches.size(), written, skipped, classBytes);
    }

    public static void main(String[] args) {
        File input = null;
        File output = null;
        File pack = null;
        String side = "client";
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--input" -> input = new File(args[++i]);
                case "--output" -> output = new File(args[++i]);
                case "--pack" -> pack = new File(args[++i]);
                case "--side" -> side = args[++i];
                case "--help", "-h" -> {
                    System.out.println("usage: PatchApplier --input <jar> --output <jar> "
                            + "[--side client|server] [--pack <file>]");
                    return;
                }
                default -> fail("unknown argument " + args[i]);
            }
        }
        if (input == null || output == null) {
            fail("--input and --output are required");
            return;
        }
        try {
            final byte[] packJar = pack == null ? readPackFromClasspath() : readPack(pack);
            final Report report = apply(packJar, input, output, side);
            System.out.println("patches: " + report);
            System.out.println("output: " + output.getAbsolutePath() + " (" + output.length() + " bytes)");
        } catch (Exception e) {
            fail(e.toString());
        }
    }

    /** Decompresses a pack file, the format {@code /binpatches.pack.lzma} is stored in. */
    public static byte[] readPack(File file) throws IOException {
        try (InputStream in = new LzmaInputStream(new FileInputStream(file))) {
            return in.readAllBytes();
        }
    }

    /** Decompresses the pack this jar carries, which is how the install step finds it by default. */
    public static byte[] readPackFromClasspath() throws IOException {
        try (InputStream in = PatchApplier.class.getResourceAsStream(PACK_RESOURCE)) {
            if (in == null) {
                throw new IOException("no " + PACK_RESOURCE + " on the classpath of " + PatchApplier.class.getName());
            }
            try (InputStream lzma = new LzmaInputStream(in)) {
                return lzma.readAllBytes();
            }
        }
    }

    private static int adler32(byte[] bytes) {
        final Adler32 adler = new Adler32();
        adler.update(bytes, 0, bytes.length);
        return (int) adler.getValue();
    }

    private static void fail(String message) {
        System.err.println("PatchApplier: " + message);
        System.exit(2);
    }
}
