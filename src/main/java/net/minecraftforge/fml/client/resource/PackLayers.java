package net.minecraftforge.fml.client.resource;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import net.minecraft.client.resources.IResourcePack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.discovery.index.AssetLayer;
import net.minecraftforge.fml.common.discovery.index.AssetStack;

/**
 * Turns the packs a reload was handed into an ordered {@link AssetStack}: the same list, the same order, and the same
 * answers, without asking any pack for a file it does not already have open.
 *
 * <p>Nothing is unpacked or reflected here. A {@code FileResourcePack} keeps its own {@code ZipFile} and answers
 * {@code resourceExists} with a central-directory lookup, so a layer that forwards to it costs what the game already
 * pays; and the pack repository closes a pack when it is removed from the list, so this stack owns no pack handle and
 * must not close one.
 *
 * <p>The stack keeps the list in the order the reload was given it and resolves from the end, because that is what
 * {@code FallbackResourceManager} does: the last pack with a file is the one the player sees.
 */
public final class PackLayers {

    private PackLayers() {
    }

    /**
     * @param packs the list {@code reloadResources} was given, lowest priority first
     * @return a stack over exactly those packs
     */
    public static AssetStack snapshot(List<IResourcePack> packs) {
        final List<AssetLayer> layers = new ArrayList<>(packs.size());
        for (IResourcePack pack : packs) {
            layers.add(layerOf(pack));
        }
        return AssetStack.of(layers, true);
    }

    /** @return a layer that asks {@code pack} itself, which is what preserves its pack name and its wrappers */
    public static AssetLayer layerOf(IResourcePack pack) {
        return new PackLayer(pack);
    }

    /**
     * The path a layer is asked about is an archive path ({@code assets/minecraft/lang/en_us.lang}); a pack is asked
     * with a {@link ResourceLocation}, so the namespace and the path inside it are split back out.
     *
     * @return the location, or null when the path is not under {@code assets/}
     */
    @Nullable
    static ResourceLocation locationOf(String path) {
        final String prefix = "assets/";
        if (!path.startsWith(prefix)) {
            return null;
        }
        final String rest = path.substring(prefix.length());
        final int slash = rest.indexOf('/');
        if (slash <= 0) {
            return null;
        }
        return new ResourceLocation(rest.substring(0, slash), rest.substring(slash + 1));
    }

    private static final class PackLayer implements AssetLayer {

        private final IResourcePack pack;

        PackLayer(IResourcePack pack) {
            this.pack = pack;
        }

        @Override
        public String describe() {
            try {
                return pack.getPackName();
            } catch (RuntimeException e) {
                return pack.getClass().getName();
            }
        }

        @Override
        @Nullable
        public java.io.File file() {
            return null;
        }

        @Override
        public boolean has(String path) {
            final ResourceLocation location = locationOf(path);
            if (location == null) {
                return false;
            }
            try {
                return pack.resourceExists(location);
            } catch (RuntimeException e) {
                // a pack that cannot answer is not a winner; the next one may still have the file
                return false;
            }
        }

        @Override
        @Nullable
        public byte[] read(String path) throws IOException {
            final ResourceLocation location = locationOf(path);
            if (location == null) {
                return null;
            }
            try (InputStream in = pack.getInputStream(location)) {
                return in.readAllBytes();
            } catch (FileNotFoundException e) {
                return null;
            } catch (RuntimeException e) {
                return null;
            }
        }

        @Override
        public String toString() {
            return describe();
        }
    }
}
