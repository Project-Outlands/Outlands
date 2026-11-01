/*
 * Outlands addition. Same licence as the rest of FML (LGPL 2.1).
 */

package net.minecraftforge.fml.common.patcher;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.function.Consumer;

/**
 * Reads a decompressed binary patch pack - the jar that FML keeps as {@code /binpatches.pack.lzma} - into
 * {@link ClassPatch} records.
 *
 * <p>The pack holds one entry per patched class, named {@code binpatch/<side>/<class>.binpatch}. The payload of an
 * entry is, in order: a format byte (1), the source class name in internal form as written by
 * {@code DataOutputStream.writeUTF}, the target class name (the same class after the notch to SRG renames the
 * patch bakes in), a boolean telling whether the class still exists after patching, the Adler32 of the class the
 * patch expects to be applied to (only when it exists), the length of the GDiff payload and the payload itself.
 *
 * <p>This class deliberately uses nothing but the JDK. The install step (see {@code docs/install-step.md} in the
 * Foundation repository) runs it from a child JVM whose classpath is only the loader jar and the LZMA decoder, so
 * it must not pull in Guava, log4j or any part of FML that does.
 */
public final class ClassPatchReader {
    /** Every patch entry starts with this and continues with the side it belongs to. */
    public static final String ENTRY_PREFIX = "binpatch/";
    /** and ends with this. */
    public static final String ENTRY_SUFFIX = ".binpatch";
    /** The only payload format there is; a different byte means a pack built by an incompatible tool. */
    public static final int FORMAT_VERSION = 1;

    private ClassPatchReader() {
    }

    /**
     * Reads every patch of one side out of an already decompressed pack.
     *
     * @param packJar the decompressed pack, i.e. a jar
     * @param side    {@code client} or {@code server}, lower case
     * @param onError receives every entry that could not be read; the entry is skipped and the rest of the pack is
     *                still read, which is what the runtime wants. Pass a throwing consumer to be strict instead.
     */
    public static List<ClassPatch> read(byte[] packJar, String side, Consumer<Exception> onError) throws IOException {
        final String prefix = ENTRY_PREFIX + side + '/';
        final List<ClassPatch> patches = new ArrayList<>();
        try (JarInputStream jis = new JarInputStream(new ByteArrayInputStream(packJar))) {
            JarEntry entry;
            while ((entry = jis.getNextJarEntry()) != null) {
                if (!entry.getName().startsWith(prefix) || !entry.getName().endsWith(ENTRY_SUFFIX)) {
                    continue;
                }
                try {
                    patches.add(readEntry(jis.readAllBytes()));
                } catch (Exception e) {
                    onError.accept(e);
                }
            }
        }
        return patches;
    }

    /** Reads a single patch entry payload, see the class documentation for the layout. */
    public static ClassPatch readEntry(byte[] payload) throws IOException {
        final DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
        final int version = in.readByte();
        if (version != FORMAT_VERSION) {
            throw new IOException("unsupported binary patch format " + version);
        }
        final String name = in.readUTF();
        final String target = in.readUTF();
        final boolean exists = in.readBoolean();
        final int checksum = exists ? in.readInt() : 0;
        final byte[] patch = new byte[in.readInt()];
        in.readFully(patch);
        return new ClassPatch(name, name.replace('/', '.'), target.replace('/', '.'), exists, checksum, patch);
    }

    /**
     * Reads the pack the way the install step does: anything unreadable aborts instead of being skipped, because
     * a pack that cannot be read must not silently produce a half patched game.
     */
    public static List<ClassPatch> readStrict(byte[] packJar, String side) throws IOException {
        final List<Exception> problems = new ArrayList<>();
        final List<ClassPatch> patches = read(packJar, side, problems::add);
        if (!problems.isEmpty()) {
            final Exception cause = problems.get(0);
            throw new IOException("the binary patch pack has " + problems.size() + " unreadable entr"
                    + (problems.size() == 1 ? "y" : "ies") + ", first: " + cause, cause);
        }
        return patches;
    }

    /** Convenience for callers that decompress a pack from an {@link InputStream} they do not want to buffer. */
    public static byte[] drain(InputStream in) throws IOException {
        try {
            return in.readAllBytes();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }
}
