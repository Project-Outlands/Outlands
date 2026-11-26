package net.minecraftforge.fml.client.resource;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.resources.IResourcePack;
import net.minecraft.client.resources.data.IMetadataSection;
import net.minecraft.client.resources.data.MetadataSerializer;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.discovery.index.AssetStack;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The stack reads the packs the reload was handed, in the order the reload was handed them, and the last one wins.
 */
class PackLayersTest {

    @Test
    void aLaterPackOverridesAnEarlierOne() throws IOException {
        final FakePack vanilla = new FakePack("vanilla").with("minecraft/lang/en_us.lang", "vanilla");
        final FakePack pack = new FakePack("pack").with("minecraft/lang/en_us.lang", "pack");

        final AssetStack stack = PackLayers.snapshot(List.of(vanilla, pack));

        assertEquals(2, stack.layers().size());
        assertArrayEquals("pack".getBytes(StandardCharsets.UTF_8),
                stack.read("assets/minecraft/lang/en_us.lang"),
                "FallbackResourceManager walks from the end, so the pack the player put last is what is seen");
        assertEquals("pack", stack.winner("assets/minecraft/lang/en_us.lang").describe());
    }

    @Test
    void aFileOnlyTheVanillaSideHasFallsThrough() throws IOException {
        final FakePack vanilla = new FakePack("vanilla").with("minecraft/textures/blocks/stone.png", "png");
        final FakePack pack = new FakePack("pack").with("minecraft/lang/en_us.lang", "lang");

        final AssetStack stack = PackLayers.snapshot(List.of(vanilla, pack));

        assertArrayEquals("png".getBytes(StandardCharsets.UTF_8),
                stack.read("assets/minecraft/textures/blocks/stone.png"), "the pack without it does not answer for it");
        assertNull(stack.read("assets/minecraft/textures/blocks/missing.png"));
    }

    @Test
    void aPathThatIsNotAnAssetIsNotAskedOfAPack() throws IOException {
        final FakePack pack = new FakePack("pack").with("minecraft/lang/en_us.lang", "lang");

        final AssetStack stack = PackLayers.snapshot(List.of(pack));

        assertNull(stack.read("data/something/else.json"), "a pack only serves assets/");
        assertNull(stack.winner("data/something/else.json"));
    }

    @Test
    void aPackThatThrowsIsAMissAndNotAFailure() throws IOException {
        final IResourcePack broken = new FakePack("broken") {
            @Override
            public boolean resourceExists(ResourceLocation location) {
                throw new IllegalStateException("this pack is having a bad day");
            }

            @Override
            public InputStream getInputStream(ResourceLocation location) throws IOException {
                throw new IOException("and so is its stream");
            }
        };
        final FakePack good = new FakePack("good").with("minecraft/lang/en_us.lang", "good");

        final AssetStack stack = PackLayers.snapshot(List.of(broken, good));

        assertArrayEquals("good".getBytes(StandardCharsets.UTF_8),
                stack.read("assets/minecraft/lang/en_us.lang"), "the next pack still answers");
    }

    private static class FakePack implements IResourcePack {

        private final String name;
        private final Map<String, String> files = new LinkedHashMap<>();

        FakePack(String name) {
            this.name = name;
        }

        FakePack with(String path, String content) {
            files.put(path, content);
            return this;
        }

        @Override
        public InputStream getInputStream(ResourceLocation location) throws IOException {
            final String content = files.get(location.getNamespace() + "/" + location.getPath());
            if (content == null) {
                throw new FileNotFoundException(location.toString());
            }
            return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public boolean resourceExists(ResourceLocation location) {
            return files.containsKey(location.getNamespace() + "/" + location.getPath());
        }

        @Override
        public Set<String> getResourceDomains() {
            return Set.of("minecraft");
        }

        @Override
        public <T extends IMetadataSection> T getPackMetadata(MetadataSerializer serializer, String name)
                throws IOException {
            return null;
        }

        @Override
        public java.awt.image.BufferedImage getPackImage() {
            return null;
        }

        @Override
        public String getPackName() {
            return name;
        }
    }
}
