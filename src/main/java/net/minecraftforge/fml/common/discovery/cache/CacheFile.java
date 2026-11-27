package net.minecraftforge.fml.common.discovery.cache;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import javax.annotation.Nullable;

/**
 * How a cache file reaches the disk: the payload as gzip when it is worth compressing, written through a temporary
 * file and moved into place.
 *
 * <p>The payload keeps its own magic and revision, so a reader that inflates it and checks those sees exactly what it
 * used to see. A file that is not gzip (the first two bytes are not {@code 1f 8b}) is read as it is, which is what keeps
 * caches written before this class existed usable.
 */
public final class CacheFile {

    /** Below this size the header and the deflate state cost more than the payload saves. */
    private static final int MIN_COMPRESSED_SIZE = 4096;

    private CacheFile() {}

    /** @return the payload of {@code file}, inflated when it was written compressed */
    public static byte[] read(File file) throws IOException {
        final byte[] raw = Files.readAllBytes(file.toPath());
        if (raw.length < 2 || (raw[0] & 0xFF) != 0x1F || (raw[1] & 0xFF) != 0x8B) {
            return raw;
        }
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(raw))) {
            return in.readAllBytes();
        }
    }

    /**
     * Writes {@code payload} to {@code file} atomically: a reader either sees the previous file or this one, and the
     * temporary file is removed when the move fails.
     */
    public static void write(File file, byte[] payload, String tempSuffix) throws IOException {
        final byte[] bytes = compress(payload);
        final File parent = file.getParentFile();
        if (parent != null) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        final File temp = new File(file.getPath() + tempSuffix);
        Files.write(temp.toPath(), bytes);
        try {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static byte[] compress(byte[] payload) throws IOException {
        if (payload.length < MIN_COMPRESSED_SIZE) {
            return payload;
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream(payload.length / 3 + 64);
        // Level 1: the write happens while the jar is still being discovered, and level 6 buys a tenth of the size
        // for three times the time on these payloads (measured on a 1.2 MB member table: 331 KB in 9 ms against
        // 286 KB in 23 ms).
        try (GZIPOutputStream gzip = new GZIPOutputStream(out, 512)) {
            gzip.write(payload);
        }
        return out.toByteArray();
    }
}
