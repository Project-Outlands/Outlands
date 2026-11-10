package net.minecraftforge.client.render.texture;

import it.unimi.dsi.fastutil.ints.Int2FloatMap;
import it.unimi.dsi.fastutil.ints.Int2FloatOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;

import java.nio.IntBuffer;

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
        final Int2ObjectMap<Level> levels = new Int2ObjectOpenHashMap<>();
        final Int2IntMap intParameters = new Int2IntOpenHashMap();
        final Int2FloatMap floatParameters = new Int2FloatOpenHashMap();
        final java.util.List<SubUpdate> pendingSubUpdates = new java.util.ArrayList<>();
        /**
         * Bumped on every write, so a device-side cache can tell an unchanged texture from a same-handle one.
         *
         * <p>Vanilla animates textures by writing a sub-rectangle on a timer without touching the handle, so
         * "this id was uploaded once" is not the same question as "this id is up to date".</p>
         */
        long version;

        Texture() {
            intParameters.defaultReturnValue(TextureImage.UNSET);
        }

        boolean hasData() {
            return levels.values().stream().anyMatch(level -> level.hasData);
        }
    }

    private final Int2ObjectMap<Texture> textures = new Int2ObjectOpenHashMap<>();
    /** Textures that have received a framebuffer copy, and therefore need an sRGB image. */
    private final IntSet framebufferTargets = new IntOpenHashSet();
    private final Int2IntMap boundUnit = new Int2IntOpenHashMap();
    private final boolean[] unitTexture2dEnabled = new boolean[32];
    {
        // Unit 0 starts enabled in Minecraft
        unitTexture2dEnabled[0] = true;
        boundUnit.defaultReturnValue(0);
    }
    /**
     * Which unit the game last made active, zero-based.
     *
     * <p>Needed because a draw samples the active unit, and the game skips {@code bindTexture} when its own
     * per-unit cache already holds the value - so bind calls alone do not tell this backend which image a draw will
     * sample. Mirrored from {@code GlStateManager} rather than inferred.</p>
     */
    private int activeUnit;
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
        texture.pendingSubUpdates.clear();
        texture.version++;
        boundUnit.int2IntEntrySet().removeIf(entry -> entry.getIntValue() == id);
    }

    @Override
    public int boundTexture(int unit) {
        return boundUnit.get(unit);
    }

    /**
     * Level 0 as a device image would need it, or {@code null} when the handle has no allocated level 0 at all.
     *
     * <p>The sampler parameters are reported as recorded, with {@link TextureImage#UNSET} where the game never set
     * one, so the mapping to Vulkan's enums stays in the renderer that has to make it rather than being guessed
     * here.</p>
     */
    @Override
    public TextureImage image(int id) {
        Texture texture = textures.get(id);
        if (texture == null) {
            return null;
        }
        Level level = texture.levels.get(0);
        if (level == null) {
            return null;
        }
        // An allocated level counts even when nothing was written into it. That is not a corner case: the main
        // menu's blur texture is allocated and then filled by a framebuffer copy, never by glTexSubImage2D. Treating
        // "allocated, unwritten" as "no image" left that texture with no device image for the copy to land in, which
        // surfaced as "framebuffer copy into texture 9 that was never resolved for this frame". Zero content is the
        // right interpretation - it is what GL considers undefined-but-present, and the copy replaces it wholesale.
        return new TextureImage(id, level.width, level.height, level.pixels,
                parameter(texture, 10241), parameter(texture, 10240),
                parameter(texture, 10242), parameter(texture, 10243),
                texture.version);
    }

    private static int parameter(Texture texture, int pname) {
        return texture.intParameters.get(pname);
    }

    @Override
    public void bind(int unit, int id) {
        boundUnit.put(unit, id);
    }

    @Override
    public void activeUnit(int unit) {
        this.activeUnit = unit;
        net.minecraftforge.client.render.state.backend.FrameState fs =
                net.minecraftforge.client.render.state.backend.FrameStates.forNonGl();
        if (fs != null) {
            fs.activeTextureUnit(unit);
        }
    }

    @Override
    public int activeUnit() {
        return activeUnit;
    }

    /**
     * The texture on the active unit.
     *
     * <p>{@link #boundUnit} mirrors {@code GlStateManager}'s own per-unit cache exactly, because both are written by
     * the same call, including when that call is skipped as a cache hit. Reading it through the active unit is
     * therefore the same answer GL gives a draw.</p>
     */
    @Override
    public int boundTexture() {
        return boundUnit.get(activeUnit);
    }

    @Override
    public boolean isTexture2DEnabled(int unit) {
        return unit >= 0 && unit < unitTexture2dEnabled.length && unitTexture2dEnabled[unit];
    }

    @Override
    public boolean isTexture2DEnabled() {
        return isTexture2DEnabled(activeUnit);
    }

    @Override
    public void setTexture2DEnabled(int unit, boolean enabled) {
        if (unit >= 0 && unit < unitTexture2dEnabled.length) {
            unitTexture2dEnabled[unit] = enabled;
        }
    }

    @Override
    public void setTexture2DEnabled(boolean enabled) {
        setTexture2DEnabled(activeUnit, enabled);
    }

    /**
     * Records the copy at this point in the frame's sequence.
     *
     * <p>The destination is the texture bound on the active unit, which is what GL's {@code glCopyTexSubImage2D}
     * always meant: it targets the bound texture, not a named one.</p>
     */
    @Override
    public void copyFromFramebuffer(int level, int xOffset, int yOffset, int x, int y, int width, int height) {
        int texture = boundTexture();
        if (texture == 0) {
            throw new IllegalStateException("glCopyTexSubImage2D with no texture bound: the copy has no destination");
        }
        framebufferTargets.add(texture);
        if (!net.minecraftforge.client.render.vertex.FrameDraws.recordFramebufferCopy(texture, level, xOffset, yOffset,
                x, y, width, height)) {
            throw new IllegalStateException("glCopyTexSubImage2D outside a frame: the source framebuffer does not"
                    + " exist yet, so there is nothing to copy from");
        }
    }

    @Override
    public boolean isFramebufferTarget(int id) {
        return framebufferTargets.contains(id);
    }

    @Override
    public void upload(int id, int level, int width, int height, IntBuffer pixels) {
        Texture texture = texture(id);
        texture.pendingSubUpdates.clear();
        if (pixels == null) {
            // Allocation without data: undefined contents of the right size, filled later by subUpload.
            texture.levels.put(level, new Level(width, height, new int[width * height]));
            return;
        }
        int[] copy = new int[width * height];
        if (pixels.hasArray()) {
            System.arraycopy(pixels.array(), pixels.arrayOffset() + pixels.position(), copy, 0,
                    Math.min(copy.length, pixels.remaining()));
        } else {
            IntBuffer view = pixels.duplicate();
            view.get(copy, 0, Math.min(copy.length, view.remaining()));
        }
        Level stored = new Level(width, height, copy);
        stored.hasData = true;
        texture.levels.put(level, stored);
        texture.version++;
    }

    @Override
    public void subUpload(int id, int level, int xOffset, int yOffset, int width, int height, IntBuffer pixels) {
        Texture texture = texture(id);
        Level target = texture.levels.get(level);
        if (target == null) {
            throw new IllegalStateException("subUpload into texture " + id + " level " + level
                    + " before it was allocated");
        }
        if (xOffset < 0 || yOffset < 0 || xOffset + width > target.width || yOffset + height > target.height) {
            throw new IllegalStateException("subUpload of " + width + "x" + height + " at " + xOffset + ","
                    + yOffset + " does not fit texture " + id + " level " + level + " (" + target.width + "x"
                    + target.height + ")");
        }
        if (pixels.hasArray()) {
            int[] src = pixels.array();
            int baseSrcOffset = pixels.arrayOffset() + pixels.position();
            if (width == target.width && xOffset == 0) {
                System.arraycopy(src, baseSrcOffset, target.pixels, yOffset * target.width, width * height);
            } else {
                for (int row = 0; row < height; row++) {
                    System.arraycopy(src, baseSrcOffset + row * width, target.pixels,
                            (yOffset + row) * target.width + xOffset, width);
                }
            }
        } else {
            IntBuffer view = pixels.duplicate();
            int pos = view.position();
            for (int row = 0; row < height; row++) {
                view.position(pos + row * width);
                view.get(target.pixels, (yOffset + row) * target.width + xOffset, width);
            }
        }
        target.hasData = true;
        if (level == 0) {
            texture.pendingSubUpdates.add(new SubUpdate(xOffset, yOffset, width, height));
        }
        texture.version++;
    }

    @Override
    public java.util.List<SubUpdate> drainSubUpdates(int id) {
        Texture texture = textures.get(id);
        if (texture == null || texture.pendingSubUpdates.isEmpty()) {
            return java.util.List.of();
        }
        java.util.List<SubUpdate> updates = new java.util.ArrayList<>(texture.pendingSubUpdates);
        texture.pendingSubUpdates.clear();
        return updates;
    }

    @Override
    public void parameter(int id, int pname, int value) {
        texture(id).intParameters.put(pname, value);
    }

    @Override
    public void parameter(int id, int pname, float value) {
        texture(id).floatParameters.put(pname, value);
    }

    /**
     * Answers width and height for a real texture.
     *
     * <p>This is the cloud renderer's question - it binds the cloud texture and reads its size to build quads - not
     * the proxy-texture maximum-size trick. The proxy target is rejected rather than answered with a plausible
     * number, because a wrong maximum silently caps every atlas the game builds.</p>
     */
    @Override
    public int levelParameter(int id, int level, int glTarget, int glPname) {
        if (glTarget != 3553) {
            throw new UnsupportedOperationException("texture target " + glTarget + " is not modelled: this backend"
                    + " answers real textures (3553) only. The proxy target is a maximum-size probe and belongs to"
                    + " RenderBackend.maxTextureSize().");
        }
        Level stored = texture(id).levels.get(level);
        if (stored == null) {
            throw new IllegalStateException("texture " + id + " has no level " + level);
        }
        return switch (glPname) {
            case 4096 -> stored.width;    // GL_TEXTURE_WIDTH
            case 4097 -> stored.height;   // GL_TEXTURE_HEIGHT
            default -> throw new UnsupportedOperationException("texture level property " + glPname
                    + " is not modelled; this backend knows GL_TEXTURE_WIDTH (4096) and GL_TEXTURE_HEIGHT (4097)");
        };
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
