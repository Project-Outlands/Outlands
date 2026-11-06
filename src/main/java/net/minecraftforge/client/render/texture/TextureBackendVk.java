package net.minecraftforge.client.render.texture;

import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Texture data on the Vulkan backend, held until a device image can be made of it.
 *
 * <p><b>What is real here and what is deferred.</b> The data, the mip chain with its sizes, the sampler parameters
 * and the bindings are all kept, in one canonical layout ({@code 0xAARRGGBB} ints), so that turning them into
 * {@code VkImage} + {@code VkSampler} is a copy rather than a re-interpretation. What does not happen yet is the
 * device part, and it is deliberately observable through {@link #pendingDeviceUploads()} rather than silently
 * dropped.</p>
 *
 * <p><b>Why deferring is correct rather than lazy.</b> The pipeline that will sample these needs a descriptor and a
 * command buffer to record the upload into, and neither exists while the game is still loading resource packs.
 * Uploading eagerly would mean creating images with no way to fill them and no ordering against the first draw.
 * Uploading on first use means the image is created in the frame that needs it, with the recording context that
 * frame has.</p>
 */
final class TextureBackendVk implements TextureBackend {

    /**
     * One mip level: its size, its pixels, and whether anything was ever written into them.
     *
     * <p>{@code hasData} is tracked separately from the array being empty because GL distinguishes "allocated with
     * undefined contents" ({@code glTexImage2D} with a null pointer) from "written". The first is the normal
     * allocation path - {@code TextureUtil} allocates every mip level that way and then fills level 0 with
     * {@code glTexSubImage2D} - so the two cases cannot share a representation.</p>
     */
    private static final class Level {
        final int width;
        final int height;
        final int[] pixels;
        boolean hasData;

        Level(int width, int height, int[] pixels) {
            this.width = width;
            this.height = height;
            this.pixels = pixels;
        }
    }

    private static final class Texture {
        final Map<Integer, Level> levels = new HashMap<>();
        final Map<Integer, Integer> intParameters = new HashMap<>();
        final Map<Integer, Float> floatParameters = new HashMap<>();

        boolean hasData() {
            return levels.values().stream().anyMatch(level -> level.hasData);
        }
    }

    private final Map<Integer, Texture> textures = new HashMap<>();
    private final Map<Integer, Integer> boundUnit = new HashMap<>();
    private int nextId = 1;

    @Override
    public int create() {
        int id = nextId++;
        textures.put(id, new Texture());
        return id;
    }

    /**
     * Drops a texture's contents, keeping its handle.
     *
     * <p><b>Why the handle survives.</b> {@code TextureUtil.allocateTextureImpl} is written as
     * {@code deleteTexture(id); bindTexture(id); glTexParameteri(...); glTexImage2D(...)} - a deleted GL name is
     * brought back by the next upload on it, so the id is reused immediately. Vulkan has no such revive, so the
     * translation is "the handle is ours and persists, the contents do not". Removing the handle here instead would
     * make the very next call on that id fail, which is exactly how this was found.</p>
     */
    @Override
    public void delete(int id) {
        Texture texture = textures.get(id);
        if (texture == null) {
            return;
        }
        texture.levels.clear();
        texture.intParameters.clear();
        texture.floatParameters.clear();
        boundUnit.values().removeIf(bound -> bound == id);
    }

    @Override
    public void bind(int unit, int id) {
        boundUnit.put(unit, id);
    }

    @Override
    public void upload(int id, int level, int width, int height, IntBuffer pixels) {
        Texture texture = texture(id);
        if (pixels == null) {
            // Allocation without data: undefined contents of the right size, filled later by subUpload.
            texture.levels.put(level, new Level(width, height, new int[width * height]));
            return;
        }
        int[] copy = new int[width * height];
        IntBuffer view = pixels.duplicate();
        view.rewind();
        view.get(copy, 0, Math.min(copy.length, view.remaining()));
        Level stored = new Level(width, height, copy);
        stored.hasData = true;
        texture.levels.put(level, stored);
    }

    @Override
    public void subUpload(int id, int level, int xOffset, int yOffset, int width, int height, IntBuffer pixels) {
        Level target = texture(id).levels.get(level);
        if (target == null) {
            throw new IllegalStateException("subUpload into texture " + id + " level " + level
                    + " before it was allocated");
        }
        if (xOffset < 0 || yOffset < 0 || xOffset + width > target.width || yOffset + height > target.height) {
            throw new IllegalStateException("subUpload of " + width + "x" + height + " at " + xOffset + ","
                    + yOffset + " does not fit texture " + id + " level " + level + " (" + target.width + "x"
                    + target.height + ")");
        }
        int[] source = new int[width * height];
        IntBuffer view = pixels.duplicate();
        view.rewind();
        view.get(source, 0, Math.min(source.length, view.remaining()));

        for (int row = 0; row < height; row++) {
            System.arraycopy(source, row * width, target.pixels, (yOffset + row) * target.width + xOffset, width);
        }
        target.hasData = true;
    }

    @Override
    public void parameter(int id, int pname, int value) {
        texture(id).intParameters.put(pname, value);
    }

    @Override
    public void parameter(int id, int pname, float value) {
        texture(id).floatParameters.put(pname, value);
    }

    @Override
    public int pendingDeviceUploads() {
        return (int) textures.values().stream().filter(Texture::hasData).count();
    }

    private Texture texture(int id) {
        Texture texture = textures.get(id);
        if (texture == null) {
            throw new IllegalStateException("unknown texture id " + id);
        }
        return texture;
    }
}
