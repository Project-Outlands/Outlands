package net.minecraftforge.client.render.vulkan;

import net.minecraftforge.client.render.vulkan.shader.SpirvReflection;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the push-constant layout that the shaders and the Java writers have to agree on.
 *
 * <p><b>Why this is a test and not a comment.</b> The block is written by byte offset and read by member name; a
 * mismatch produces no error from the driver, no validation message and no exception - just a picture where the fog
 * colour is the point size. The block is also exactly at Vulkan's guaranteed 128 bytes, so it has already been
 * repacked once (a colour became RGBA8 and three flags became an int) to fit two more features, which is exactly the
 * kind of edit that shifts an offset.</p>
 *
 * <p>Read from the compiled module, not from the source, because the module is what the driver sees.</p>
 */
class PushConstantLayoutTest {

    private static SpirvReflection.Block storageBlock(String resource, int binding) throws IOException {
        try (InputStream in = PushConstantLayoutTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, resource + " must be on the test classpath");
            SpirvReflection.Module module = SpirvReflection.reflect(in.readAllBytes());
            for (SpirvReflection.Block block : module.blocks()) {
                if (block.binding() == binding) {
                    return block;
                }
            }
            throw new AssertionError(resource + " declares no descriptor block at binding " + binding
                    + ", only " + module.blocks());
        }
    }

    private static SpirvReflection.Block pushConstants(String resource) throws IOException {
        try (InputStream in = PushConstantLayoutTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, resource + " must be on the test classpath");
            SpirvReflection.Module module = SpirvReflection.reflect(in.readAllBytes());
            assertEquals(1, module.pushConstants().size(), resource + " should declare exactly one push constant block");
            return module.pushConstants().get(0);
        }
    }

    /** {@code name, offset, name, offset, ...} - {@code Map.of} stops at ten pairs and these blocks are longer. */
    private static Map<String, Integer> offsets(Object... pairs) {
        Map<String, Integer> map = new java.util.LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], (Integer) pairs[i + 1]);
        }
        return map;
    }

    /**
     * Compares by position, not by name: the modules are compiled without debug names, so the reflection reports
     * {@code member0..memberN}. The order is the declaration order in the shader, which is the order a reader of
     * either side sees.
     */
    private static void assertLayout(SpirvReflection.Block block, Map<String, Integer> expected) {
        List<Map.Entry<String, Integer>> entries = new java.util.ArrayList<>(expected.entrySet());
        assertEquals(entries.size(), block.members().size(),
                () -> "the shader declares " + block.members().size() + " members, the writer uses " + entries.size()
                        + ": " + block.members());
        for (int i = 0; i < entries.size(); i++) {
            int expectedOffset = entries.get(i).getValue();
            int actual = block.members().get(i).offset();
            int index = i;
            assertEquals(expectedOffset, actual,
                    () -> entries.get(index).getKey() + " is member " + index + " and moved from " + expectedOffset
                            + " to " + actual + "; the Java writer still writes the old offset. Members are "
                            + block.members());
        }
    }

    /**
     * The fragment stage has to agree with the vertex stage about the block.
     *
     * <p>They are two hand-written declarations of one layout, and a member that lands elsewhere in the fragment
     * shader is not an error anywhere: the pipeline is created, the driver reads the same bytes through a different
     * name, and the fog colour becomes the point size. Checked here because the two are edited together and one of
     * them will eventually be edited alone.</p>
     */
    @Test
    void eachFragmentBlockMatchesItsVertexBlock() throws IOException {
        // Terrain still travels in push constants; the stream's per-draw block moved into a storage buffer, where the
        // two stages have to agree just as exactly.
        SpirvReflection.Block vertex = pushConstants("/assets/outlands/shaders/terrain.vert.spv");
        SpirvReflection.Block fragment = pushConstants("/assets/outlands/shaders/terrain.frag.spv");
        assertSharedPrefix("terrain", vertex, fragment);

        assertSharedPrefix("stream",
                storageBlock("/assets/outlands/shaders/stream.vert.spv", 1),
                storageBlock("/assets/outlands/shaders/stream.frag.spv", 1));
    }

    private static void assertSharedPrefix(String name, SpirvReflection.Block vertex,
                                           SpirvReflection.Block fragment) {
        // A stage may declare fewer members than the other - it only has to stay inside the range the pipeline
        // layout reserves - so the shared prefix is what has to agree.
        int shared = Math.min(vertex.members().size(), fragment.members().size());
        for (int i = 0; i < shared; i++) {
            int index = i;
            assertEquals(vertex.members().get(i).offset(), fragment.members().get(i).offset(),
                    () -> name + ": member " + index + " is at a different offset in the fragment stage: "
                            + vertex.members() + " vs " + fragment.members());
        }
    }

    @Test
    void theStreamBlockMatchesItsWriter() throws IOException {
        SpirvReflection.Block block = storageBlock("/assets/outlands/shaders/stream.vert.spv", 1);
        // The stride is the whole indexing scheme: the draw for entry N is issued with firstInstance = N, and the
        // shader reads draws[gl_InstanceIndex]. A member added to the struct moves every entry after the first.
        assertEquals(VulkanStream.DRAW_DATA_BYTES, block.arrayStride(),
                () -> "the draw-data array stride is " + block.arrayStride() + ", the writer packs "
                        + VulkanStream.DRAW_DATA_BYTES + " bytes a draw: " + block.members());
        assertLayout(block, offsets(
                "modelViewProjection", 0,
                "fogColor", 64,
                "overlayColorPacked", 80,
                "alphaReference", 84,
                "flags", 88,
                "fogMode", 92,
                "fogStart", 96,
                "fogEnd", 100,
                "fogDensity", 104,
                "fogTanX", 108,
                "fogTanY", 112,
                "pointSize", 116,
                "spare0", 120,
                "spare1", 124));
        assertTrue(block.size() <= VulkanStream.DRAW_DATA_BYTES,
                () -> "the stream block is " + block.size() + " bytes, over the " + VulkanStream.DRAW_DATA_BYTES
                        + " the writer packs");
    }

    @Test
    void theTerrainBlockMatchesItsWriter() throws IOException {
        SpirvReflection.Block block = pushConstants("/assets/outlands/shaders/terrain.vert.spv");
        assertLayout(block, offsets(
                "modelViewProjection", 0,
                "fogColor", 64,
                "chunkData", 80,
                "alphaReference", 88,
                "flags", 92,
                "fogMode", 96,
                "fogStart", 100,
                "fogEnd", 104,
                "fogDensity", 108,
                "cameraX", 112,
                "cameraY", 116,
                "cameraZ", 120,
                "spare", 124));
        assertTrue(block.size() <= VulkanPipeline.PUSH_CONSTANT_BYTES,
                () -> "the terrain block is " + block.size() + " bytes, over the guaranteed "
                        + VulkanPipeline.PUSH_CONSTANT_BYTES);
    }
}
