package net.minecraftforge.client.render.texture;

/**
 * A texture's level 0 and the sampler state that belongs with it, in the form a device image needs.
 *
 * <p><b>Why level 0 only.</b> Vanilla builds a mip chain for its atlases, and none of it is reachable without
 * generating mips on the device: the chain is uploaded level by level from data the game itself computed, so it is
 * available, but sampling it means one device image per level or a mip-generating copy. Level 0 alone renders
 * correctly at native scale and aliases at distance, which is a visible-but-tolerable state and, unlike sampling the
 * wrong level, not a wrong picture up close.</p>
 *
 * <p><b>{@code version} is what makes animation work.</b> Vanilla animates textures by writing into a sub-rectangle
 * of an existing level on a timer, so the pixels change while the handle stays the same. A cache keyed by handle
 * alone would show frame one forever; keyed by version it re-uploads exactly when the contents moved.</p>
 *
 * @param id         the backend's texture handle
 * @param width      level 0's width in texels
 * @param height     level 0's height in texels
 * @param argb       the pixels, {@code 0xAARRGGBB} per texel, rows from top to bottom
 * @param minFilter  the GL minification filter, or {@link #UNSET} when the game never set one
 * @param magFilter  the GL magnification filter, or {@link #UNSET}
 * @param wrapS      the GL wrap mode for S, or {@link #UNSET}
 * @param wrapT      the GL wrap mode for T, or {@link #UNSET}
 * @param version    bumped whenever the pixels change, so a cache can tell "same" from "unchanged"
 */
public record TextureImage(int id, int width, int height, int[] argb,
                           int minFilter, int magFilter, int wrapS, int wrapT, long version) {

    /** No parameter was set, so GL's own default applies. */
    public static final int UNSET = 0;
}
