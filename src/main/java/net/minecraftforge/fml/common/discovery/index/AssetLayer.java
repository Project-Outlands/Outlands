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

    static AssetLayer archive(File jar) {
        return new ArchiveLayer(jar);
    }

    static AssetLayer objects(AssetObjects objects) {
        return new ObjectsLayer(objects);
    }
}

/** An archive the class loader may or may not already have open, resolved per query. */
final class ArchiveLayer implements AssetLayer {

    private final File jar;

    ArchiveLayer(File jar) {
        this.jar = Objects.requireNonNull(jar, "jar").getAbsoluteFile();
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
        try (BorrowedArchive archive = BorrowedArchive.of(jar, false)) {
            return archive.index().read(archive.index().find(path));
        } catch (RuntimeException e) {
            throw new IOException("Could not read " + path + " from " + jar, e);
        }
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
