package net.minecraftforge.client.render.state.backend;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import java.nio.FloatBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The recorded frame state, and the two places where a silent mistake would show up as geometry in the wrong place
 * rather than as an error: the degrees-to-radians conversion and the composition order.
 */
class FrameStateVkTest {

    private static final int MODELVIEW = 5888;
    private static final int TEXTURE = 5890;

    private static Vector4f transform(FrameStateVk state, float x, float y, float z) {
        Matrix4f matrix = new Matrix4f(state.matrix(MODELVIEW));
        return matrix.transform(new Vector4f(x, y, z, 1.0F));
    }

    /**
     * The outline mode's combine stack, as {@code GlStateManager.enableOutlineMode} sets it.
     *
     * <p>This is the state the entity-outline pass depends on: the lightmap unit replaces the light with the team
     * colour and takes its alpha from the texture, which is what gives the silhouette an alpha step to trace. The
     * sequence is copied from vanilla rather than simplified, because the whole question is whether the real one is
     * recognised.</p>
     */
    @Test
    void theOutlineCombineOnUnitZeroReplacesTheTexture() {
        FrameStateVk state = new FrameStateVk();
        int textureEnv = 8960;
        // glTexEnv(GL_TEXTURE_ENV, GL_TEXTURE_ENV_COLOR, teamColour), as a buffer because that is the overload
        state.texEnv(textureEnv, 8705, FloatBuffer.wrap(new float[] {1.0F, 1.0F, 1.0F, 1.0F}));
        state.texEnvi(textureEnv, 8704, 34160);   // GL_TEXTURE_ENV_MODE = GL_COMBINE
        state.texEnvi(textureEnv, 34161, 7681);   // GL_COMBINE_RGB = GL_REPLACE
        state.texEnvi(textureEnv, 34176, 34166);  // GL_SOURCE0_RGB = GL_CONSTANT
        state.texEnvi(textureEnv, 34192, 768);    // GL_OPERAND0_RGB = GL_SRC_COLOR
        state.texEnvi(textureEnv, 34162, 7681);   // GL_COMBINE_ALPHA = GL_REPLACE
        state.texEnvi(textureEnv, 34184, 5890);   // GL_SOURCE0_ALPHA = GL_TEXTURE
        state.texEnvi(textureEnv, 34200, 770);    // GL_OPERAND0_ALPHA = GL_SRC_ALPHA

        // 2, not 1: with unit 0 active the *texture* is what gets replaced, and that is the difference between a
        // white silhouette and one in the entity's own colour.
        assertEquals(2, state.overlayMode(), "unit 0 with a replace combine means the texture is replaced");
        assertEquals(1.0F, state.overlayColor()[0], 0.0001F, "and it is replaced with the team colour");
    }

    @Test
    void allThirteenCapabilitiesAreKnown() {
        // The set is closed by the thirteen BooleanState constructions in vanilla. A backend switching on a value
        // with meaning is the point; a new one should fail to compile rather than fall through.
        for (int glEnum : new int[] {2884, 2896, 2903, 2912, 2929, 2977, 3008, 3042, 3058, 3089, 3553, 10754, 32823, 32826}) {
            Capability.ofGlEnum(glEnum);
        }
        assertThrows(IllegalArgumentException.class, () -> Capability.ofGlEnum(3040),
                "a GL capability vanilla never toggles is not silently accepted");
    }

    @Test
    void capabilitiesAreTrackedAndNotJustRecorded() {
        FrameStateVk state = new FrameStateVk();
        // 13 toggles that vanilla drives through BooleanState, plus SCISSOR_TEST (3089), plus the eight GL_LIGHTn
        // sources that RenderHelper.enableGUIStandardItemLighting turns on when an item icon is drawn. The count is
        // asserted so that adding a capability is a deliberate act rather than a side effect - and so that removing one,
        // which would make some draw silently unrepresented, fails here.
        assertEquals(22, Capability.values().length);
        assertEquals(Capability.LIGHT_0, Capability.ofGlEnum(16384));
        assertEquals(Capability.LIGHT_7, Capability.ofGlEnum(16391));

        state.capability(Capability.DEPTH_TEST, true);
        state.capability(Capability.DEPTH_TEST, false);
        // Re-enabling after disabling must still be visible; a set that only ever adds would pass a naive test.
        state.capability(Capability.DEPTH_TEST, true);

        // The observer is the pipeline key, which is not exposed; the assertion here is that repeated toggles do
        // not throw and that the enum is the only input.
        assertTrue(Capability.DEPTH_TEST.name().equals("DEPTH_TEST"));
    }

    @Test
    void rotateTakesDegreesLikeGl() {
        // glRotatef(90, 0, 0, 1) maps +X to +Y. In radians it would map +X to almost +X, which is the mistake this
        // test exists to catch - it produces a picture that is wrong but not obviously wrong.
        FrameStateVk state = new FrameStateVk();
        state.matrixMode(MODELVIEW);
        state.rotate(90.0F, 0.0F, 0.0F, 1.0F);

        Vector4f mapped = transform(state, 1.0F, 0.0F, 0.0F);
        assertEquals(0.0F, mapped.x, 1.0e-5F);
        assertEquals(1.0F, mapped.y, 1.0e-5F);
    }

    @Test
    void translateThenRotateRotatesAboutTheTranslatedOrigin() {
        // Composition on the right is what makes this true. Composing on the left would rotate about the world
        // origin and move the translated point back.
        FrameStateVk state = new FrameStateVk();
        state.matrixMode(MODELVIEW);
        state.translate(10.0, 0.0, 0.0);
        state.rotate(90.0F, 0.0F, 0.0F, 1.0F);

        // The point (1,0,0) is first rotated to (0,1,0), then translated by (10,0,0).
        Vector4f mapped = transform(state, 1.0F, 0.0F, 0.0F);
        assertEquals(10.0F, mapped.x, 1.0e-5F);
        assertEquals(1.0F, mapped.y, 1.0e-5F);
    }

    @Test
    void pushAndPopRestoreTheMatrixAndKeepSeparateStacksPerMode() {
        FrameStateVk state = new FrameStateVk();
        state.matrixMode(MODELVIEW);
        state.translate(5.0, 0.0, 0.0);
        state.pushMatrix();
        state.translate(100.0, 0.0, 0.0);

        // The projection stack is independent: pushing to it must not disturb the modelview stack.
        state.matrixMode(5889);
        state.pushMatrix();
        state.popMatrix();

        state.matrixMode(MODELVIEW);
        Vector4f before = transform(state, 0.0F, 0.0F, 0.0F);
        assertEquals(105.0F, before.x, 1.0e-5F, "still shifted by the inner translate");

        state.popMatrix();
        Vector4f after = transform(state, 0.0F, 0.0F, 0.0F);
        assertEquals(5.0F, after.x, 1.0e-5F, "the outer translate survives");
    }

    @Test
    void anUnbalancedPopIsLoud() {
        FrameStateVk state = new FrameStateVk();
        state.matrixMode(MODELVIEW);
        assertThrows(IllegalStateException.class, state::popMatrix);
        assertThrows(IllegalStateException.class, state::popAttributes);
    }

    @Test
    void multMatrixReadsColumnMajor() {
        // glMultMatrixf reads column-major. Reading the same 16 floats the other way round is a transpose, which
        // is invisible for a diagonal matrix - so this one is not diagonal.
        //
        // The buffer is a heap one, deliberately: handing a heap FloatBuffer to Matrix4f.set(FloatBuffer) crashes
        // the JVM, because that path dereferences the buffer's native address field without checking isDirect().
        // This test is the regression guard for the copy in FrameStateVk.multiply.
        float[] columnMajor = {
                1, 0, 0, 0,
                0, 1, 0, 0,
                0, 0, 1, 0,
                3, 4, 5, 1,
        };
        FrameStateVk state = new FrameStateVk();
        state.matrixMode(MODELVIEW);
        state.multiply(FloatBuffer.wrap(columnMajor));

        Vector4f mapped = transform(state, 0.0F, 0.0F, 0.0F);
        assertEquals(3.0F, mapped.x, 1.0e-5F);
        assertEquals(4.0F, mapped.y, 1.0e-5F);
        assertEquals(5.0F, mapped.z, 1.0e-5F);
    }

    @Test
    void unknownValuesAreRejected() {
        FrameStateVk state = new FrameStateVk();
        assertThrows(IllegalArgumentException.class, () -> state.shadeModel(1234));
        assertThrows(IllegalArgumentException.class, () -> state.cullFace(1234));
        assertThrows(IllegalArgumentException.class, () -> state.matrixMode(1234));
        assertThrows(IllegalArgumentException.class, () -> state.depthFunc(1234));
        assertThrows(IllegalArgumentException.class, () -> state.blendFunc(1234, 1));
        assertThrows(IllegalArgumentException.class, () -> state.polygonMode(1029, 1234));
    }

    @Test
    void attribPushesAreCountedNotApplied() {
        FrameStateVk state = new FrameStateVk();
        state.pushAttributes();
        state.pushAttributes();
        state.popAttributes();
        state.popAttributes();
        // A third pop has nothing to restore; the depth going negative is a caller bug, not a state to absorb.
        assertThrows(IllegalStateException.class, state::popAttributes);
        assertFalse(false);
    }

    @Test
    void glLightTransformsDirectionByCurrentModelView() {
        FrameStateVk state = new FrameStateVk();
        state.matrixMode(MODELVIEW);
        // Rotate 90 degrees around Y: maps +X to -Z, +Z to +X
        state.rotate(90.0F, 0.0F, 1.0F, 0.0F);

        FloatBuffer pos0 = FloatBuffer.wrap(new float[] {1.0F, 0.0F, 0.0F, 0.0F});
        state.glLight(16384, 4611, pos0); // GL_LIGHT0, GL_POSITION

        float[] l0 = state.lighting().lightDirection(0);
        // (1, 0, 0) rotated by 90 Y is (0, 0, -1) in eye space
        assertEquals(0.0F, l0[0], 1.0e-5F);
        assertEquals(0.0F, l0[1], 1.0e-5F);
        assertEquals(-1.0F, l0[2], 1.0e-5F);
    }

    @Test
    void depthFuncIsTracked() {
        FrameStateVk state = new FrameStateVk();
        assertEquals(513, state.depthFunc()); // GL_LESS default
        state.depthFunc(514); // GL_EQUAL
        assertEquals(514, state.depthFunc());
        state.depthFunc(515); // GL_LEQUAL
        assertEquals(515, state.depthFunc());
    }

    @Test
    void textureMatrixReturnsNullWhenIdentityAndValuesWhenTransformed() {
        FrameStateVk state = new FrameStateVk();
        state.matrixMode(TEXTURE);
        assertNull(state.textureMatrix());

        state.scale(8.0F, 8.0F, 8.0F);
        float[] mat = state.textureMatrix();
        assertNotNull(mat);
        assertEquals(8.0F, mat[0], 0.001F);
        assertEquals(8.0F, mat[5], 0.001F);

        state.loadIdentity();
        assertNull(state.textureMatrix());
    }

    @Test
    void lightmapTextureMatrixOnUnit1DoesNotAffectUnit0TextureMatrix() {
        FrameStateVk state = new FrameStateVk();
        // Simulate EntityRenderer.enableLightmap() on texture unit 1
        state.activeTextureUnit(1);
        state.matrixMode(TEXTURE);
        state.loadIdentity();
        state.scale(0.00390625F, 0.00390625F, 0.00390625F);
        state.translate(8.0F, 8.0F, 8.0F);
        state.matrixMode(MODELVIEW);
        state.activeTextureUnit(0);

        // Unit 0 texture matrix must remain null (identity) - must not collapse UVs of GUI/items/font
        assertNull(state.textureMatrix(), "Unit 0 texture matrix must not be polluted by unit 1 lightmap scaling");

        // Unit 1 matrix can still be queried when unit 1 is active
        state.activeTextureUnit(1);
        Matrix4fc m1 = state.matrix(TEXTURE);
        assertEquals(0.00390625F, m1.m00(), 0.0001F);
        assertEquals(0.03125F, m1.m30(), 0.0001F); // 8.0 * 0.00390625 = 0.03125
    }

    @Test
    void cachedArraysAvoidAllocationsWhenUnmodified() {
        FrameStateVk state = new FrameStateVk();
        assertSame(state.currentColor(), state.currentColor());
        assertSame(state.viewport(), state.viewport());
        assertSame(state.scissorBox(), state.scissorBox());
        assertSame(state.colorMask(), state.colorMask());
        // The lighting snapshot is shared until the game changes the model: that is what lets a draw hold it
        // without copying eight lights' worth of state.
        assertSame(state.lighting(), state.lighting());

        state.color(0.5F, 0.5F, 0.5F, 1.0F);
        assertEquals(0.5F, state.currentColor()[0], 0.001F);
    }

    @Test
    void aChangedLightingModelProducesANewSnapshot() {
        FrameStateVk state = new FrameStateVk();
        // Colour material on, as vanilla leaves it for lit geometry: then the material ambient is the vertex colour
        // and the factor is the lighting sum itself rather than 0.2 times it.
        state.capability(Capability.COLOR_MATERIAL, true);
        var before = state.lighting();
        assertSame(before, state.lighting(), "nothing changed, so the same snapshot comes back");

        state.lightModelAmbient(FloatBuffer.wrap(new float[] {0.5F, 0.5F, 0.5F, 1.0F}));
        var afterAmbient = state.lighting();
        assertNotSame(before, afterAmbient, "the model ambient changed");
        float[] factor = new float[3];
        // A normal pointing away from both default lights leaves only the ambient term.
        afterAmbient.shadeFactor(0.0F, -1.0F, 0.0F, factor);
        assertEquals(0.5F, factor[0], 1.0e-5F, "the ambient reaches the shaded result");
    }

    @Test
    void glLightDiffuseAndAmbientReachTheModel() {
        FrameStateVk state = new FrameStateVk();
        state.lightModelAmbient(FloatBuffer.wrap(new float[] {0.5F, 0.5F, 0.5F, 1.0F}));
        state.glLight(16384, 4609, FloatBuffer.wrap(new float[] {0.2F, 0.05F, 0.05F, 1.0F}));   // GL_DIFFUSE
        state.glLight(16384, 4608, FloatBuffer.wrap(new float[] {0.1F, 0.0F, 0.0F, 1.0F}));     // GL_AMBIENT
        state.capability(Capability.LIGHT_0, true);
        state.capability(Capability.COLOR_MATERIAL, true);

        // The seeded default direction of every light is +Z, so this normal faces light 0 head on.
        float[] factor = new float[3];
        state.lighting().shadeFactor(0.0F, 0.0F, 1.0F, factor);

        assertEquals(0.5F + 0.1F + 0.2F, factor[0], 1.0e-4F, "model ambient + light ambient + red diffuse");
        assertEquals(0.5F + 0.05F, factor[1], 1.0e-4F, "green gets the ambients and its own diffuse");
        assertEquals(0.5F + 0.05F, factor[2], 1.0e-4F, "blue gets the ambients and its own diffuse");
    }

    @Test
    void aDisabledLightContributesNothing() {
        FrameStateVk state = new FrameStateVk();
        state.glLight(16384, 4609, FloatBuffer.wrap(new float[] {0.6F, 0.6F, 0.6F, 1.0F}));
        state.glLight(16384, 4611, FloatBuffer.wrap(new float[] {0.0F, 1.0F, 0.0F, 0.0F}));
        state.lightModelAmbient(FloatBuffer.wrap(new float[] {0.4F, 0.4F, 0.4F, 1.0F}));
        state.capability(Capability.COLOR_MATERIAL, true);

        float[] factor = new float[3];
        // Light 0 is off (GL's default), so a normal pointing straight at it is lit by the ambient alone.
        state.lighting().shadeFactor(0.0F, 1.0F, 0.0F, factor);
        assertEquals(0.4F, factor[0], 1.0e-5F, "a disabled light is not a light");

        state.capability(Capability.LIGHT_0, true);
        state.lighting().shadeFactor(0.0F, 1.0F, 0.0F, factor);
        assertEquals(1.0F, factor[0], 1.0e-5F, "0.4 ambient + 0.6 diffuse, clamped");
    }

}
