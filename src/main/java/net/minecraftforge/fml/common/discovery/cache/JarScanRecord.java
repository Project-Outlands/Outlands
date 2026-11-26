package net.minecraftforge.fml.common.discovery.cache;

import java.util.List;

import com.google.common.primitives.Ints;

/**
 * The scan of one jar: every scanned class entry with its header and annotations, tagged with its ordinal among the
 * jar's scanned entries, plus the fingerprint the scan was taken for.
 *
 * <p>{@code indices} and {@code records} are parallel and ascending by index. Every scanned class is stored, so the
 * record doubles as the jar's class index; {@linkplain ClassScanRecord#hasContent() hasContent} says which classes
 * also contribute to the annotation table and can start a container. {@code fromDisk} tells whether the scan was read
 * back from the cache or freshly parsed; only fresh scans are written.
 */
public record JarScanRecord(JarFingerprint fingerprint, boolean fromDisk, int[] indices, ClassScanRecord[] records) {

    public JarScanRecord {
        if (indices.length != records.length) {
            throw new IllegalArgumentException("indices and records must be parallel");
        }
    }

    public static JarScanRecord of(JarFingerprint fingerprint, List<Integer> indices, List<ClassScanRecord> records) {
        return new JarScanRecord(fingerprint, false, Ints.toArray(indices), records.toArray(new ClassScanRecord[0]));
    }
}
