package net.minecraftforge.fml.common.discovery.index;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

/**
 * Resolves a resource path through an ordered list of layers: the first one that has the path is the answer, so a
 * caller that wants a pack to override another puts it earlier in the list.
 *
 * <p>The order is the caller's, because only the caller knows it. A client resolves assets through vanilla, then the
 * mods, then the resource packs in the order the player arranged them, and the last of those wins over the first - the
 * pack repository holds that order, and nothing in the loader can derive it. Handing the list in is what keeps the
 * offline answer equal to the rendered one; a view built from the class path is only right for what is left of the
 * game outside the pack stack.
 *
 * <p>Vanilla is one priority level made of two halves: the objects the launcher downloaded, then the jar, in that
 * order, which is the order the game reads them in. They barely overlap in 1.12.2 - the index carries the sounds and
 * languages, the jar carries the textures and models - so either half being missing is a normal state a modded
 * instance runs in, and every layer here answers a miss instead of failing.
 *
 * <p>Reads go through {@link BorrowedArchive}, so an archive the class loader already has open is not opened again.
 */
public final class AssetView {

    public static final AssetView NONE = AssetView.of(List.of());

    private final List<AssetLayer> layers;

    private AssetView(List<AssetLayer> layers, boolean unused) {
        this.layers = List.copyOf(layers);
    }

    /** @param archives the layers, first wins, each an archive read as-is */
    public static AssetView archives(List<File> archives) {
        final List<AssetLayer> wrapped = new ArrayList<>(archives.size());
        for (File archive : archives) {
            wrapped.add(AssetLayer.of(archive));
        }
        return new AssetView(wrapped, true);
    }

    /** @param layers the layers, first wins */
    public static AssetView of(List<AssetLayer> layers) {
        return new AssetView(layers, true);
    }

    /**
     * @return vanilla's two halves in the order the game reads them: the downloaded objects, then the jar
     */
    public static AssetView vanilla(File jar, AssetObjects objects) {
        return objects.available()
                ? AssetView.of(List.of(AssetLayer.objects(objects), AssetLayer.of(jar)))
                : AssetView.archives(List.of(jar));
    }

    /** @return the layers in the order they are resolved, first wins */
    public List<AssetLayer> layers() {
        return layers;
    }

    /** @return the layer that serves {@code path}, or null when none does */
    @Nullable
    public AssetLayer winner(String path) {
        for (AssetLayer layer : layers) {
            if (layer.has(path)) {
                return layer;
            }
        }
        return null;
    }

    /** @return every layer that has {@code path}, in resolution order */
    public List<AssetLayer> layersOf(String path) {
        final List<AssetLayer> found = new ArrayList<>();
        for (AssetLayer layer : layers) {
            if (layer.has(path)) {
                found.add(layer);
            }
        }
        return found;
    }

    /** @return the bytes the winning layer has for {@code path}, or null when no layer has it */
    @Nullable
    public byte[] read(String path) throws IOException {
        for (AssetLayer layer : layers) {
            final byte[] bytes = layer.read(path);
            if (bytes != null) {
                return bytes;
            }
        }
        return null;
    }

    /** @return the number of layers that serve {@code path} */
    public int count(String path) {
        return layersOf(path).size();
    }

    /** One file a catalog found, and the layer that has it. */
    public record Entry(String path, AssetLayer layer) {
    }

    /**
     * Lists every file under {@code prefix}, layer by layer in resolution order, and sorted inside each layer.
     *
     * <p>Grouped by layer because that is the order a consumer merges in, and each file keeps the layer it came from
     * so an override can be attributed. A path several layers have appears once per layer, which is the point: the
     * caller decides whether the last one wins or whether they merge.
     *
     * @param prefix an archive path, such as {@code assets/minecraft/lang/} or {@code data/mymod/recipes/}
     * @return the files, or an empty list when no layer can enumerate
     */
    public List<Entry> catalog(String prefix) {
        final List<Entry> found = new ArrayList<>();
        for (AssetLayer layer : layers) {
            final List<String> paths = new ArrayList<>();
            try {
                layer.paths(prefix, paths::add);
            } catch (RuntimeException e) {
                // a layer that cannot enumerate contributes nothing; the others still list
                continue;
            }
            java.util.Collections.sort(paths);
            for (String path : paths) {
                found.add(new Entry(path, layer));
            }
        }
        return found;
    }

    /** @return the winning layer's bytes decoded as UTF-8, or null when no layer has the path */
    @Nullable
    public String readText(String path) throws IOException {
        final byte[] bytes = read(path);
        return bytes == null ? null : new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }
}
