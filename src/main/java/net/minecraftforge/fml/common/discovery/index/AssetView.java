package net.minecraftforge.fml.common.discovery.index;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

/**
 * Resolves a resource path through an ordered list of archives: the first layer that has the path is the answer, so a
 * caller that wants a pack to override another puts it earlier in the list.
 *
 * <p>The order is the caller's, because only the caller knows it. A client resolves assets through vanilla, then the
 * mods, then the resource packs in the order the player arranged them, and the last of those wins over the first — the
 * resource pack repository holds that order, and nothing in the loader can derive it. Handing the list in is what
 * keeps the offline answer equal to the rendered one; a view built from the class path is only right for what is left
 * of the game outside the pack stack.
 *
 * <p>Reads go through {@link BorrowedArchive}, so an archive the class loader already has open is not opened again.
 */
public final class AssetView {

    public static final AssetView NONE = new AssetView(List.of());

    private final List<File> layers;

    public AssetView(List<File> layers) {
        this.layers = List.copyOf(layers);
    }

    /** @return the layers in the order they are resolved, first wins */
    public List<File> layers() {
        return layers;
    }

    /** @return the archive that serves {@code path}, or null when no layer has it */
    @Nullable
    public File winner(String path) {
        for (File layer : layers) {
            try (BorrowedArchive archive = BorrowedArchive.of(layer, false)) {
                if (archive.index().find(path) != null) {
                    return layer;
                }
            } catch (IOException | RuntimeException e) {
                // an unreadable layer is not a winner; the next one may still have the file
            }
        }
        return null;
    }

    /** @return every layer that has {@code path}, in resolution order */
    public List<File> layersOf(String path) {
        final List<File> found = new ArrayList<>();
        for (File layer : layers) {
            try (BorrowedArchive archive = BorrowedArchive.of(layer, false)) {
                if (archive.index().find(path) != null) {
                    found.add(layer);
                }
            } catch (IOException | RuntimeException e) {
                // same as above: a layer that cannot be read contributes nothing
            }
        }
        return found;
    }

    /** @return the bytes the winning layer has for {@code path}, or null when no layer has it */
    @Nullable
    public byte[] read(String path) throws IOException {
        for (File layer : layers) {
            try (BorrowedArchive archive = BorrowedArchive.of(layer, false)) {
                final java.util.jar.JarEntry entry = archive.index().find(path);
                if (entry != null) {
                    return archive.index().read(entry);
                }
            } catch (RuntimeException e) {
                throw new IOException("Could not read " + path + " from " + layer, e);
            }
        }
        return null;
    }

    /** @return the number of layers that serve {@code path} */
    public int count(String path) {
        return layersOf(path).size();
    }
}
