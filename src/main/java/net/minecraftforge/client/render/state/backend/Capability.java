package net.minecraftforge.client.render.state.backend;

/**
 * The GL capabilities {@code GlStateManager}'s enable/disable pairs actually toggle.
 *
 * <p><b>A closed set, written down.</b> Every one originates from a {@code new
 * GlStateManager.BooleanState(...)} in vanilla or from a {@code GL_LIGHTn} constant, so the set is fixed by the game
 * rather than open. Naming them here means a backend switches on a value with meaning instead of comparing raw GL
 * enums, and a capability added later fails to compile rather than falling through silently.</p>
 *
 * <p><b>Why the lights are here.</b> They were not in the original set, and the omission was loud rather than quiet:
 * {@code RenderHelper.enableGUIStandardItemLighting} calls {@code enableLight(0)}, which reached this class as
 * {@code 16384} and threw. It only surfaced once item icons started drawing - the mod list's icons are the first
 * thing in the game that turns a light on.</p>
 */
public enum Capability {

    CULL_FACE,
    /** Fixed-function lighting: no pipeline equivalent, so it is a shader variant selector. */
    LIGHTING,
    /**
     * The eight fixed-function light sources.
     *
     * <p>Recorded and otherwise ignored, like {@link #LIGHTING}: a pipeline has no lights, and a draw that would have
     * been lit by them is handled by the shader variant decision instead. They are separate capabilities because the
     * game toggles them separately, and folding them into {@code LIGHTING} would make {@code disableLight(0)} turn
     * lighting off for the whole draw.</p>
     */
    LIGHT_0, LIGHT_1, LIGHT_2, LIGHT_3, LIGHT_4, LIGHT_5, LIGHT_6, LIGHT_7,
    COLOR_MATERIAL,
    FOG,
    DEPTH_TEST,
    SCISSOR_TEST,
    /** Fixed-function alpha test: Vulkan has none, so it is a shader variant selector. */
    ALPHA_TEST,
    BLEND,
    COLOR_LOGIC_OP,
    /** Two-dimensional texturing, which is always on in a pipeline-per-draw model. */
    TEXTURE_2D,
    POLYGON_OFFSET_LINE,
    POLYGON_OFFSET_FILL,
    /** Fixed-function: a normal-rescaling mode with no Vulkan equivalent. */
    RESCALE_NORMAL;

    /** Maps a GL capability constant. Throws for anything outside the set vanilla uses. */
    public static Capability ofGlEnum(int glCapability) {
        return switch (glCapability) {
            case 16384 -> LIGHT_0;
            case 16385 -> LIGHT_1;
            case 16386 -> LIGHT_2;
            case 16387 -> LIGHT_3;
            case 16388 -> LIGHT_4;
            case 16389 -> LIGHT_5;
            case 16390 -> LIGHT_6;
            case 16391 -> LIGHT_7;
            case 2884 -> CULL_FACE;
            case 2896 -> LIGHTING;
            case 2903 -> COLOR_MATERIAL;
            case 2912 -> FOG;
            case 2929 -> DEPTH_TEST;
            case 2977 -> SCISSOR_TEST;
            case 3008 -> ALPHA_TEST;
            case 3042 -> BLEND;
            case 3058 -> COLOR_LOGIC_OP;
            case 3553 -> TEXTURE_2D;
            case 10754 -> POLYGON_OFFSET_LINE;
            case 32823 -> POLYGON_OFFSET_FILL;
            case 32826 -> RESCALE_NORMAL;
            default -> throw new IllegalArgumentException("not a capability GlStateManager toggles: " + glCapability);
        };
    }
}
