package net.minecraftforge.client.render.state.backend;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The panorama's matrix sequence, checked against what GL would produce.
 *
 * <p><b>Why this test exists.</b> The main menu's background comes out at an angle the GL renderer never shows, and
 * every other stage was verified byte-for-byte: the vertices, the texture coordinates, the composed matrix's shape,
 * and the six face images against their source PNGs. That leaves the primitives the composed matrix is built from.
 * gluRotate composes as a right-handed rotation about a normalised axis; if this implementation composes anything
 * else - a transposed rotation, a different handedness, a sign - the picture is mirrored or turned by a multiple of
 * 90 degrees while every individual call still looks plausible.</p>
 *
 * <p>The expectations below are written out by hand from glRotatef's definition, not captured from this code.</p>
 */
class PanoramaMatrixTest {

    private static final int PROJECTION = 5889;
    private static final int MODELVIEW = 5888;

    /** glRotatef(180, 1, 0, 0): x stays, y and z negate. */
    @Test
    void rotateAboutXMatchesGl() {
        FrameStateVk state = new FrameStateVk();
        state.matrixMode(MODELVIEW);
        state.rotate(180.0F, 1.0F, 0.0F, 0.0F);
        assertMaps(state, 1, 0, 0, 1, 0, 0, "rotate(180, X) on +X");
        assertMaps(state, 0, 1, 0, 0, -1, 0, "rotate(180, X) on +Y");
        assertMaps(state, 0, 0, 1, 0, 0, -1, "rotate(180, X) on +Z");
    }

    /** glRotatef(90, 0, 0, 1): +X goes to +Y, +Y goes to -X. */
    @Test
    void rotateAboutZMatchesGl() {
        FrameStateVk state = new FrameStateVk();
        state.matrixMode(MODELVIEW);
        state.rotate(90.0F, 0.0F, 0.0F, 1.0F);
        assertMaps(state, 1, 0, 0, 0, 1, 0, "rotate(90, Z) on +X");
        assertMaps(state, 0, 1, 0, -1, 0, 0, "rotate(90, Z) on +Y");
    }

    /** glRotatef(90, 0, 1, 0): +Z goes to +X, +X goes to -Z. */
    @Test
    void rotateAboutYMatchesGl() {
        FrameStateVk state = new FrameStateVk();
        state.matrixMode(MODELVIEW);
        state.rotate(90.0F, 0.0F, 1.0F, 0.0F);
        assertMaps(state, 0, 0, 1, 1, 0, 0, "rotate(90, Y) on +Z");
        assertMaps(state, 1, 0, 0, 0, 0, -1, "rotate(90, Y) on +X");
    }

    /**
     * The panorama's opening pair, composed.
     *
     * <p>glRotatef post-multiplies, so {@code rotate(180, X)} then {@code rotate(90, Z)} is {@code R180x · R90z},
     * which maps {@code +X -> -Y}, {@code +Y -> -X}, {@code +Z -> -Z}. Those three vectors are the whole
     * expectation: they say that the face's local up ends up pointing along -X, so the picture is expected to read
     * as rolled, and any other answer here means this renderer composes rotations differently from GL.</p>
     */
    @Test
    void panoramaOpeningPairMatchesGl() {
        FrameStateVk state = new FrameStateVk();
        state.matrixMode(MODELVIEW);
        state.rotate(180.0F, 1.0F, 0.0F, 0.0F);
        state.rotate(90.0F, 0.0F, 0.0F, 1.0F);
        assertMaps(state, 1, 0, 0, 0, -1, 0, "panorama base on +X");
        assertMaps(state, 0, 1, 0, -1, 0, 0, "panorama base on +Y");
        assertMaps(state, 0, 0, 1, 0, 0, -1, "panorama base on +Z");
    }

    private static void assertMaps(FrameStateVk state, float inX, float inY, float inZ,
                                   float outX, float outY, float outZ, String what) {
        Matrix4f m = new Matrix4f(state.modelView());
        Vector4f v = m.transform(new Vector4f(inX, inY, inZ, 0.0F));
        System.out.printf("%s: (%g,%g,%g) -> (%6.3f,%6.3f,%6.3f)  expected (%g,%g,%g)%n",
                what, inX, inY, inZ, v.x, v.y, v.z, outX, outY, outZ);
        assertEquals(outX, v.x, 1e-5, what + " x");
        assertEquals(outY, v.y, 1e-5, what + " y");
        assertEquals(outZ, v.z, 1e-5, what + " z");
    }
}
