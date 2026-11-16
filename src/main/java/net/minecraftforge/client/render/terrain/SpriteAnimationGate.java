package net.minecraftforge.client.render.terrain;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;

import java.nio.ByteBuffer;

/**
 * Marks the animated sprites terrain actually draws, so the atlas only ticks what is on screen.
 *
 * <p><b>What vanilla does.</b> {@code TextureMap.updateAnimations} ticks every animated sprite each frame, and a tick
 * that reaches a new frame uploads it. A pack with hundreds of animated textures pays that upload for water, lava, fire
 * and every animated machine casing whether or not any of them is visible.</p>
 *
 * <p><b>How a sprite is marked.</b> While a finished chunk mesh is being captured, each quad's four texture
 * coordinates are averaged and looked up in {@link AnimatedSpriteIndex}. A sprite that a visible mesh samples is
 * marked, and the atlas ticks it once.</p>
 *
 * <p><b>The valve: a sprite that was never marked keeps ticking.</b> Only terrain is inspected here, and sprites are
 * also used by item models, particles and tile entity renderers, none of which this sees. So "never marked" means "this
 * code has no opinion about it", and the answer is vanilla's. Once terrain has marked a sprite at least once, its ticks
 * follow terrain visibility. That bounds the whole feature's risk: the worst it can do is stop an animation that
 * terrain used to draw and no longer does, which is what it is for.</p>
 */
public final class SpriteAnimationGate {

    private SpriteAnimationGate() {}

    /**
     * Marks every animated sprite the given finished terrain mesh samples.
     *
     * @param raw       vanilla's {@code DefaultVertexFormats.BLOCK} vertices
     * @param quadCount how many quads the buffer holds
     */
    public static void collect(ByteBuffer raw, int quadCount) {
        AnimatedSpriteIndex index = AnimatedSpriteIndex.current();
        if (index == null || quadCount <= 0) {
            return;
        }
        // The layout is vanilla's: 28 bytes a vertex, position at 0, texture coordinate at 16. Four vertices a quad.
        int stride = 28;
        int uvOffset = 16;
        for (int quad = 0; quad < quadCount; quad++) {
            float u = 0.0F;
            float v = 0.0F;
            for (int vertex = 0; vertex < 4; vertex++) {
                int at = quad * 4 * stride + vertex * stride + uvOffset;
                u += raw.getFloat(at);
                v += raw.getFloat(at + 4);
            }
            TextureAtlasSprite sprite = index.find(u * 0.25F, v * 0.25F);
            if (sprite != null) {
                sprite.outlandsMarkActive();
            }
        }
    }
}
