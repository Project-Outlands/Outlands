package net.minecraftforge.client.render.texture;

/**
 * A texture's mip chain and the sampler state that belongs with it, in the form a device image needs.
 *
 * <p><b>The chain is the game's own, not something generated here.</b> Vanilla builds mipmaps in Java - {@code
 * TextureUtil.generateMipmapData}, called from {@code TextureAtlasSprite.generateMipmaps} - and uploads every level
 * with its own {@code glTexSubImage2D}, so a backend that receives those calls already holds the whole chain. Nothing
 * in vanilla calls {@code glGenerateMipmap}: it is the mods (pregenerator's preview texture, in the survey of five
 * packs) that ask the driver to build one, and {@link net.minecraftforge.client.render.shim.FrameShim} reports that
 * request rather than guessing at the filter it would have used.
 *
 * <p><b>Every level is whatever the game wrote.</b> A level that was allocated and never filled is present and zero,
 * the same thing GL would sample. The chain always starts at level 0 and stops at the first level that was never
 * allocated, so {@code levels.length} is the number of levels there are, not the number the game once asked for.
 *
 * <p><b>{@code version} is what makes animation work.</b> Vanilla animates textures by writing into a sub-rectangle
 * of an existing level on a timer, so the pixels change while the handle stays the same. A cache keyed by handle
 * alone would show frame one forever; keyed by version it re-uploads exactly when the contents moved.</p>
 *
 * @param id        the backend's texture handle
 * @param width     level 0's width in texels
 * @param height    level 0's height in texels
 * @param levels    each level's pixels, {@code 0xAARRGGBB} per texel, rows from top to bottom; index = mip level
 * @param minFilter the GL minification filter, or {@link #UNSET} when the game never set one
 * @param magFilter the GL magnification filter, or {@link #UNSET}
 * @param wrapS     the GL wrap mode for S, or {@link #UNSET}
 * @param wrapT     the GL wrap mode for T, or {@link #UNSET}
 * @param version   bumped whenever the pixels change, so a cache can tell "same" from "unchanged"
 */
public record TextureImage(int id, int width, int height, int[][] levels,
                           int minFilter, int magFilter, int wrapS, int wrapT, long version) {

    /** No parameter was set, so GL's own default applies. */
    public static final int UNSET = 0;

    /** Level 0's pixels, which is all a caller that does not care about mips needs. */
    public int[] argb() {
        return levels[0];
    }

    /** How many levels the game uploaded, level 0 included - at least one. */
    public int levelCount() {
        return levels.length;
    }

    /** One level's pixels, rows top-down. */
    public int[] level(int level) {
        return levels[level];
    }

    /** A level's width: level 0's halved once per level, never below one, which is Vulkan's own rule. */
    public int levelWidth(int level) {
        return Math.max(1, width >> level);
    }

    /** A level's height, by the same rule. */
    public int levelHeight(int level) {
        return Math.max(1, height >> level);
    }
}
