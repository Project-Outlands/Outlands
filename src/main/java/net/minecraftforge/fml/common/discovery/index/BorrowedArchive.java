package net.minecraftforge.fml.common.discovery.index;

import java.io.File;
import java.io.IOException;
import java.util.jar.JarFile;

import top.outlands.foundation.boot.ArchiveIndex;
import top.outlands.foundation.boot.ArchiveSet;

/**
 * The index of a jar, borrowed from the class loader when it already has the archive open and opened privately
 * otherwise.
 *
 * <p>The class loader keeps a handle per class path entry for the life of the process, and that is where the memory
 * for open archives is meant to live. Everything that reads a jar once — discovery, a fingerprint, a metadata entry —
 * only needs the same bytes, so it takes the class loader's handle when there is one and does not keep a second copy
 * alive when there is not.
 *
 * @see ArchiveSet
 */
public final class BorrowedArchive implements AutoCloseable {

    private final ArchiveIndex index;
    private final boolean owned;

    private BorrowedArchive(ArchiveIndex index, boolean owned) {
        this.index = index;
        this.owned = owned;
    }

    /** @return the class loader's handle for {@code file}, or a private one this object closes */
    public static BorrowedArchive of(File file) throws IOException {
        return of(file, true);
    }

    /**
     * @param verify whether a private handle verifies signatures on read, which is only worth turning off where the
     *               previous reader used a {@link java.util.zip.ZipFile}; a borrowed handle keeps the class loader's
     *               flag, and those entries are read on load anyway
     */
    public static BorrowedArchive of(File file, boolean verify) throws IOException {
        final ArchiveSet shared = ArchiveSet.classPath();
        final ArchiveIndex borrowed = shared.of(file.toPath());
        if (borrowed != null) {
            return new BorrowedArchive(borrowed, false);
        }
        return new BorrowedArchive(new ArchiveIndex(file.toPath(), new JarFile(file, verify)), true);
    }

    /** @return the class loader's handle, opened for good; the caller keeps the jar and must not close it */
    public static ArchiveIndex retained(File file) throws IOException {
        return ArchiveSet.classPath().open(file.toPath());
    }

    public ArchiveIndex index() {
        return index;
    }

    public JarFile jar() {
        return index.jarFile();
    }

    @Override
    public void close() {
        if (owned) {
            try {
                index.jarFile().close();
            } catch (IOException ignored) {
                // closing a handle nobody else can see; there is nothing useful to do about a failure
            }
        }
    }
}
