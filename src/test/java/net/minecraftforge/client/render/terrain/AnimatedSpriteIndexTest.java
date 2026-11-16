package net.minecraftforge.client.render.terrain;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which animated sprite a terrain quad samples, and what the atlas does with that answer.
 *
 * <p><b>What is worth pinning.</b> The identification has to be exact in one direction: a quad that samples an
 * animated sprite must be attributed to it, or that sprite stops animating while it is on screen. The other direction
 * is harmless - attributing a quad to a sprite it does not sample only costs a tick - so the test concentrates on the
 * hits, and on the valve that keeps every sprite terrain never samples animating exactly as before.</p>
 */
class AnimatedSpriteIndexTest {

    private static final int ATLAS = 1024;

    @Test
    void aCoordinateInsideASpriteFindsIt() {
        TextureAtlasSprite water = sprite("water", 16, 0, 0);
        TextureAtlasSprite lava = sprite("lava", 16, 16, 0);
        AnimatedSpriteIndex index = indexOf(water, lava);

        assertSame(water, index.find(0.004F, 0.004F), "the middle of the first sprite");
        assertSame(lava, index.find(0.020F, 0.004F), "the middle of the second");
    }

    @Test
    void aCoordinateBetweenSpritesFindsNothing() {
        AnimatedSpriteIndex index = indexOf(sprite("water", 16, 0, 0), sprite("lava", 16, 64, 64));
        assertNull(index.find(0.5F, 0.5F), "no sprite covers the middle of this atlas");
    }

    @Test
    void theEdgesOfASpriteCountAsInsideIt() {
        // A quad that samples the very last texel must still be attributed, or the sprite stops animating exactly when
        // the block is closest to the camera.
        TextureAtlasSprite sprite = sprite("edge", 16, ATLAS - 16, ATLAS - 16);
        AnimatedSpriteIndex index = indexOf(sprite);

        assertSame(sprite, index.find(1.0F, 1.0F), "the far corner of the atlas is the sprite's own corner");
        assertSame(sprite, index.find((ATLAS - 16) / (float) ATLAS, (ATLAS - 16) / (float) ATLAS));
    }

    @Test
    void aQuadIsAttributedToTheSpriteItsFourCornersSample() {
        // The real shape of the input: a finished terrain mesh, four vertices a quad, the texture coordinate at
        // offset 16 of a 28 byte vertex. The average of the corners is what identifies the sprite.
        TextureAtlasSprite water = sprite("water", 16, 0, 0);
        AnimatedSpriteIndex.rebuild(List.of(water));

        ByteBuffer raw = ByteBuffer.allocateDirect(4 * 28).order(ByteOrder.nativeOrder());
        float[] us = {0.001F, 0.014F, 0.014F, 0.001F};
        float[] vs = {0.001F, 0.001F, 0.014F, 0.014F};
        for (int vertex = 0; vertex < 4; vertex++) {
            raw.putFloat(vertex * 28 + 16, us[vertex]);
            raw.putFloat(vertex * 28 + 20, vs[vertex]);
        }

        SpriteAnimationGate.collect(raw, 1);

        assertTrue(water.outlandsShouldTick(), "the sprite the quad samples is marked, and ticks once");
        assertFalse(water.outlandsShouldTick(), "and the mark is consumed, so it ticks once per frame");
    }

    @Test
    void aSpriteTerrainNeverSamplesKeepsTicking() {
        // The valve. Item models, particles and tile entity renderers all sample the atlas without going through the
        // terrain capture, so "never marked" has to mean "behave like vanilla" rather than "never animate".
        TextureAtlasSprite itemSprite = sprite("item", 16, 0, 0);
        AnimatedSpriteIndex.rebuild(List.of(itemSprite));

        assertTrue(itemSprite.outlandsShouldTick());
        assertTrue(itemSprite.outlandsShouldTick());
    }

    @Test
    void aSpriteThatStopsBeingSampledStopsTicking() {
        TextureAtlasSprite water = sprite("water", 16, 0, 0);
        AnimatedSpriteIndex.rebuild(List.of(water));

        water.outlandsMarkActive();
        assertTrue(water.outlandsShouldTick(), "visible this frame");
        assertFalse(water.outlandsShouldTick(), "not sampled again: no tick");
        assertFalse(water.outlandsShouldTick(), "and it stays that way while it is off screen");
    }

    private static AnimatedSpriteIndex indexOf(TextureAtlasSprite... sprites) {
        AnimatedSpriteIndex.rebuild(List.of(sprites));
        return AnimatedSpriteIndex.current();
    }

    /**
     * A sprite with a known rectangle. The atlas fields are protected and {@code initSprite} computes the coordinates
     * from them, so a subclass is the shortest way to get a sprite that is not backed by a PNG.
     */
    private static TextureAtlasSprite sprite(String name, int size, int originX, int originY) {
        TextureAtlasSprite sprite = new TextureAtlasSprite(name) {
            {
                this.width = size;
                this.height = size;
            }
        };
        sprite.initSprite(ATLAS, ATLAS, originX, originY, false);
        return sprite;
    }

    @Test
    void anAtlasWithNoAnimatedSpritesStillHasAnIndex() {
        AnimatedSpriteIndex.rebuild(List.of());
        assertNotNull(AnimatedSpriteIndex.current(), "an index, not a null that would read as 'not built yet'");
        assertNull(AnimatedSpriteIndex.current().find(0.5F, 0.5F));
    }
}
