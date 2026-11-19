package net.minecraftforge.client.render.vertex;

import net.minecraft.util.BlockRenderLayer;
import net.minecraftforge.client.render.NonGlTestBackend;
import net.minecraftforge.client.render.state.backend.Capability;
import net.minecraftforge.client.render.state.backend.FrameState;
import net.minecraftforge.client.render.state.backend.FrameStates;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A terrain layer carries the alpha test the frame actually had, not the one a renderer would assume.
 *
 * <p><b>Why this is worth pinning.</b> {@code EntityRenderer.renderWorld} sets {@code glAlphaFunc(GL_GREATER, 0.5)}
 * once for the world, draws {@code SOLID} with the test disabled and {@code CUTOUT_MIPPED}/{@code CUTOUT} with it
 * enabled, and only afterwards lowers the reference to 0.1. A renderer that hardcoded 0.1 for a cutout layer - which
 * is the value a reader finds by grepping for {@code alphaFunc} and stopping at the first unrelated hit - keeps every
 * texel a mip level blends with a fully transparent neighbour. The colour under a transparent texel is black, so
 * sugar cane, whose texture is half transparent, turned into black stripes as soon as it was minified at all.</p>
 */
class TerrainAlphaTestRecordingTest {

    private static final int GL_GREATER = 516;
    private static final float VANILLA_WORLD_REFERENCE = 0.5F;

    @BeforeEach
    void selectANonGlBackend() {
        NonGlTestBackend.select();
    }

    @AfterEach
    void restore() {
        FrameDraws.drain();
        NonGlTestBackend.restore();
    }

    private static FrameState state() {
        FrameState state = FrameStates.forNonGl();
        assertNotNull(state, "the test selected a non-GL backend");
        return state;
    }

    /** The state {@code renderWorld} leaves in place before the terrain passes. */
    private static void vanillaWorldAlphaTest() {
        FrameState state = state();
        state.capability(Capability.ALPHA_TEST, true);
        state.alphaFunc(GL_GREATER, VANILLA_WORLD_REFERENCE);
    }

    @Test
    void aCutoutLayerIsRecordedWithTheFramesReferenceAndNotTheDefault() {
        FrameDraws.beginCollecting();
        vanillaWorldAlphaTest();
        FrameStates.recordTerrainLayer(BlockRenderLayer.CUTOUT_MIPPED.ordinal(), 0.0, 0.0, 0.0);

        FrameDraws.TerrainLayer layer = onlyLayer();
        assertTrue(layer.alphaTest(), "a cutout layer is drawn with the alpha test on");
        assertEquals(VANILLA_WORLD_REFERENCE, layer.alphaReference(), 1.0e-6F,
                "the reference must be the 0.5 the world pass set, not the 0.1 the renderer left behind");
        assertEquals(GL_GREATER, layer.alphaComparison(), "GL_GREATER is what vanilla compares with");
    }

    @Test
    void aSolidLayerIsRecordedWithTheTestOff() {
        FrameDraws.beginCollecting();
        vanillaWorldAlphaTest();
        // renderWorldPass calls disableAlpha() for SOLID and enableAlpha() for the cutout layers.
        state().capability(Capability.ALPHA_TEST, false);
        FrameStates.recordTerrainLayer(BlockRenderLayer.SOLID.ordinal(), 0.0, 0.0, 0.0);

        FrameDraws.TerrainLayer layer = onlyLayer();
        assertFalse(layer.alphaTest(), "SOLID is drawn with the alpha test off, whatever its reference says");
    }

    @Test
    void aReferenceChangedMidFrameIsFollowed() {
        FrameDraws.beginCollecting();
        vanillaWorldAlphaTest();
        FrameStates.recordTerrainLayer(BlockRenderLayer.CUTOUT.ordinal(), 0.0, 0.0, 0.0);
        // A mod that lowers the reference before its own layer gets the test it asked for.
        state().alphaFunc(GL_GREATER, 0.1F);
        FrameStates.recordTerrainLayer(BlockRenderLayer.TRANSLUCENT.ordinal(), 0.0, 0.0, 0.0);

        List<FrameDraws.Operation> ops = FrameDraws.drain();
        assertEquals(2, ops.size(), () -> "expected two layers, got " + ops);
        assertEquals(VANILLA_WORLD_REFERENCE, ((FrameDraws.TerrainLayer) ops.get(0)).alphaReference(), 1.0e-6F);
        assertEquals(0.1F, ((FrameDraws.TerrainLayer) ops.get(1)).alphaReference(), 1.0e-6F);
    }

    private static FrameDraws.TerrainLayer onlyLayer() {
        List<FrameDraws.Operation> ops = FrameDraws.drain();
        assertEquals(1, ops.size(), () -> "expected one terrain layer, got " + ops);
        return (FrameDraws.TerrainLayer) ops.get(0);
    }
}
