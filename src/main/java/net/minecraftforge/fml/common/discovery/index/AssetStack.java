package net.minecraftforge.fml.common.discovery.index;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;

/**
 * An ordered stack of asset layers as one object: the order a query resolves in, the layers it was built from, and
 * whatever those layers opened.
 *
 * <p>The game's own order is not the one a lookup walks. {@code FallbackResourceManager} walks its pack list backwards
 * and takes the last pack that has the file, so the list a caller holds is in ascending priority and the answer is at
 * its end. A stack built with {@code preferLast} keeps that list as given and resolves from the end, which is one
 * reversal decided once instead of one per caller and per query.
 *
 * <p>A stack owns nothing the layers do not: an archive layer built with {@link AssetLayer#owned} opened its file and
 * closes it here, while a layer that borrows the class loader's handle releases nothing. A resource pack holds its own
 * {@code ZipFile} and the pack repository closes it when the pack is removed, so the packs this stack reads are not
 * this stack's to close either - only the files it opened itself are.
 */
public final class AssetStack implements AutoCloseable {

    public static final AssetStack EMPTY = new AssetStack(List.of(), false);

    /** The layers in the order the caller gave them, which is the order they are reported in. */
    private final List<AssetLayer> layers;
    /** The layers in the order a query resolves in: the same list, or its reverse under {@code preferLast}. */
    private final List<AssetLayer> resolution;
    private final AssetView view;

    private AssetStack(List<AssetLayer> layers, boolean preferLast) {
        this.layers = List.copyOf(layers);
        final List<AssetLayer> resolved = new ArrayList<>(this.layers);
        if (preferLast) {
            Collections.reverse(resolved);
        }
        this.resolution = List.copyOf(resolved);
        this.view = AssetView.of(this.resolution);
    }

    /**
     * @param layers the layers in the game's own order, lowest priority first
     * @param preferLast whether the last layer that has a path wins, which is what the game does
     */
    public static AssetStack of(List<AssetLayer> layers, boolean preferLast) {
        return new AssetStack(layers, preferLast);
    }

    /** @return the layers in the order the caller gave them */
    public List<AssetLayer> layers() {
        return layers;
    }

    /** @return the layers in the order a query resolves in */
    public List<AssetLayer> resolutionOrder() {
        return resolution;
    }

    public AssetView view() {
        return view;
    }

    /** @return the layer that serves {@code path}, or null when none does */
    @Nullable
    public AssetLayer winner(String path) {
        return view.winner(path);
    }

    /** @return the bytes the winning layer has for {@code path}, or null when none has it */
    @Nullable
    public byte[] read(String path) throws java.io.IOException {
        return view.read(path);
    }

    /**
     * @return whether a file behind this stack changed since it was read, which means the stack has to be built again;
     *         only a layer that opened a file itself can answer
     */
    public boolean stale() {
        for (AssetLayer layer : layers) {
            if (layer.stale()) {
                return true;
            }
        }
        return false;
    }

    /** Closes every layer that opened something; borrowed and external layers are untouched. */
    @Override
    public void close() {
        for (AssetLayer layer : layers) {
            layer.close();
        }
    }

    @Override
    public String toString() {
        final List<String> names = new ArrayList<>(layers.size());
        for (AssetLayer layer : layers) {
            names.add(layer.describe());
        }
        return String.join(" -> ", names);
    }
}
