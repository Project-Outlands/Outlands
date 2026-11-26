package net.minecraftforge.fml.common.discovery.index;

import java.io.File;
import java.io.IOException;
import java.util.Objects;

import javax.annotation.Nullable;

/**
 * One layer a resource path can come from: an archive, the launcher's downloaded objects, or whatever a caller wires
 * up later. A view is an ordered list of these, and the first that has the path is what the player sees.
 */
public interface AssetLayer {

    /** @return a name for diagnostics, which is the jar's file name or the index that was read */
    String describe();

    /** @return the file this layer reads, or null when it is not a single archive (the downloaded objects are not) */
    @Nullable
    File file();

    boolean has(String path);

    /** @return the bytes, or null when this layer does not have the path */
    @Nullable
    byte[] read(String path) throws IOException;

    /** @return whether the file behind this layer changed since it was read; only a layer that owns a handle can say */
    default boolean stale() {
        return false;
    }

    /** Releases whatever this layer opened. A layer that borrows the class loader's handle releases nothing. */
    default void close() {
    }

    static AssetLayer archive(File jar) {
        return new ArchiveLayer(jar);
    }

    /**
     * @return a layer that opens the archive itself and keeps it open until {@link #close()}, for a file the class
     *         loader does not have - a resource pack, in other words
     */
    static AssetLayer owned(File jar) {
        return ArchiveLayer.owned(jar);
    }

    static AssetLayer directory(File dir) {
        return new DirectoryLayer(dir);
    }

    /** @return a layer for whichever of the two {@code source} is; a development run's mods are directories */
    static AssetLayer of(File source) {
        return source.isDirectory() ? directory(source) : archive(source);
    }

    static AssetLayer objects(AssetObjects objects) {
        return new ObjectsLayer(objects);
    }
}

/** An archive the class loader may or may not already have open, resolved per query. */
final class ArchiveLayer implements AssetLayer {

    private final File jar;
    /** Set when this layer opened the archive itself, which is then also this layer's to close. */
    @Nullable
    private final top.outlands.foundation.boot.ArchiveIndex owned;

    ArchiveLayer(File jar) {
        this(jar, null);
    }

    ArchiveLayer(File jar, @Nullable top.outlands.foundation.boot.ArchiveIndex owned) {
        this.jar = Objects.requireNonNull(jar, "jar").getAbsoluteFile();
        this.owned = owned;
    }

    static AssetLayer owned(File jar) {
        try {
            final top.outlands.foundation.boot.ArchiveIndex index = new top.outlands.foundation.boot.ArchiveIndex(
                    jar.toPath(), new java.util.jar.JarFile(jar, false));
            return new ArchiveLayer(jar, index);
        } catch (IOException e) {
            // an archive that will not open is a layer that answers nothing, not a stack that fails to build
            return new ArchiveLayer(jar);
        }
    }

    @Override
    public String describe() {
        return jar.getName();
    }

    @Override
    public File file() {
        return jar;
    }

    @Override
    public boolean has(String path) {
        if (owned != null) {
            try {
                return owned.find(path) != null;
            } catch (RuntimeException e) {
                // a handle that was closed with its stack answers nothing rather than throwing at the next query
                return false;
            }
        }
        try (BorrowedArchive archive = BorrowedArchive.of(jar, false)) {
            return archive.index().find(path) != null;
        } catch (IOException | RuntimeException e) {
            // an unreadable layer is not a winner; the next one may still have the file
            return false;
        }
    }

    @Override
    @Nullable
    public byte[] read(String path) throws IOException {
        if (owned != null) {
            try {
                return owned.read(owned.find(path));
            } catch (RuntimeException e) {
                return null;
            }
        }
        try (BorrowedArchive archive = BorrowedArchive.of(jar, false)) {
            return archive.index().read(archive.index().find(path));
        } catch (IOException | RuntimeException e) {
            // unreadable is a miss, exactly what has() answers for the same layer: one broken jar in a stack must not
            // take the query down when the next layer may well have the file
            return null;
        }
    }

    @Override
    public boolean stale() {
        return owned != null && !owned.fingerprint().matches(jar.toPath());
    }

    @Override
    public void close() {
        if (owned != null) {
            try {
                owned.jarFile().close();
            } catch (IOException ignored) {
                // closing a handle nobody else can see; there is nothing useful to do about a failure
            }
        }
    }

    @Override
    public String toString() {
        return describe();
    }
}

/** A directory that holds {@code assets/} or {@code data/} at its root, which is how a development run ships them. */
final class DirectoryLayer implements AssetLayer {

    private final File dir;

    DirectoryLayer(File dir) {
        this.dir = Objects.requireNonNull(dir, "dir").getAbsoluteFile();
    }

    @Override
    public String describe() {
        return dir.getName() + "/";
    }

    @Override
    public File file() {
        return dir;
    }

    @Override
    public boolean has(String path) {
        return resolve(path) != null;
    }

    @Override
    @Nullable
    public byte[] read(String path) throws IOException {
        final java.nio.file.Path file = resolve(path);
        if (file == null) {
            return null;
        }
        try {
            return java.nio.file.Files.readAllBytes(file);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * @return the file under this directory, or null when it is not there
     */
    @Nullable
    private java.nio.file.Path resolve(String path) {
        if (path.isEmpty() || path.indexOf('\0') >= 0) {
            return null;
        }
        final java.nio.file.Path root = dir.toPath().normalize();
        final java.nio.file.Path file = root.resolve(path).normalize();
        // a path in an archive cannot walk out of it, and one asked for here must not either
        if (!file.startsWith(root) || !java.nio.file.Files.isRegularFile(file)) {
            return null;
        }
        return file;
    }

    @Override
    public String toString() {
        return describe();
    }
}

/** The launcher's {@code assets/objects}, which is a directory of content addressed files rather than an archive. */
final class ObjectsLayer implements AssetLayer {

    private final AssetObjects objects;

    ObjectsLayer(AssetObjects objects) {
        this.objects = Objects.requireNonNull(objects, "objects");
    }

    @Override
    public String describe() {
        return objects.available() ? "assets/objects (" + objects.indexId() + ")" : "assets/objects";
    }

    @Override
    @Nullable
    public File file() {
        return null;
    }

    @Override
    public boolean has(String path) {
        return objects.has(path);
    }

    @Override
    @Nullable
    public byte[] read(String path) throws IOException {
        return objects.read(path);
    }

    @Override
    public String toString() {
        return describe();
    }
}
