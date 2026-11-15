package net.minecraftforge.client.render.vertex;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code glColorMaterial}'s five modes, which select the material components the current colour stands in for.
 *
 * <p>Vanilla itself uses two of them: {@code RenderHelper.enableStandardItemLighting} sets
 * {@code GL_AMBIENT_AND_DIFFUSE} for items and entities, and {@code EntityRenderer.setupFog} sets {@code GL_AMBIENT}
 * for the world pass. Treating both as the first one is what the model used to do, and it is invisible in the case
 * that dominates the screen (lit items) and wrong in the other.</p>
 *
 * <p>The model is exercised as {@code colour = vertex * outVertex + outConstant}, so a test reads the two arrays.</p>
 */
class ColorMaterialModeTest {

    /** One light straight down +Z, white diffuse, no ambient anywhere: the Lambert term is the only thing left. */
    private static LightingModel lit(int mode, boolean colorMaterial) {
        return new LightingModel(
                new float[] {0.0F, 0.0F, 0.0F},
                new float[LightingModel.LIGHTS * 3],
                whiteDiffuse(),
                towardPositiveZ(),
                lightZeroOnly(),
                colorMaterial, mode);
    }

    private static float[] whiteDiffuse() {
        float[] diffuse = new float[LightingModel.LIGHTS * 3];
        diffuse[0] = 1.0F;
        diffuse[1] = 1.0F;
        diffuse[2] = 1.0F;
        return diffuse;
    }

    private static float[] towardPositiveZ() {
        float[] direction = new float[LightingModel.LIGHTS * 3];
        direction[2] = 1.0F;
        return direction;
    }

    private static boolean[] lightZeroOnly() {
        boolean[] enabled = new boolean[LightingModel.LIGHTS];
        enabled[0] = true;
        return enabled;
    }

    @Test
    void ambientAndDiffuseLetsTheVertexColourDriveBoth() {
        float[] vertex = new float[3];
        float[] constant = new float[3];
        lit(LightingModel.AMBIENT_AND_DIFFUSE, true).shadeFactor(0.0F, 0.0F, 1.0F, vertex, constant);

        assertEquals(1.0F, vertex[0], 1.0e-5F, "the diffuse term multiplies the vertex colour");
        assertEquals(0.0F, constant[0], 1.0e-5F, "and nothing is added");
    }

    @Test
    void ambientAloneLeavesTheDiffuseToTheMaterial() {
        float[] vertex = new float[3];
        float[] constant = new float[3];
        lit(LightingModel.AMBIENT, true).shadeFactor(0.0F, 0.0F, 1.0F, vertex, constant);

        assertEquals(0.0F, vertex[0], 1.0e-5F, "ambient is zero here, so the colour multiplies nothing");
        assertEquals(0.8F, constant[0], 1.0e-5F, "the diffuse term uses GL's default material diffuse, not the colour");
    }

    @Test
    void diffuseAloneLeavesTheAmbientToTheMaterial() {
        float[] vertex = new float[3];
        float[] constant = new float[3];
        lit(LightingModel.DIFFUSE, true).shadeFactor(0.0F, 0.0F, 1.0F, vertex, constant);

        assertEquals(1.0F, vertex[0], 1.0e-5F, "the diffuse term follows the colour");
        assertEquals(0.0F, constant[0], 1.0e-5F, "and the ambient sum is zero in this model");
    }

    @Test
    void emissionIsAddedRatherThanSubstituted() {
        float[] vertex = new float[3];
        float[] constant = new float[3];
        lit(LightingModel.EMISSION, true).shadeFactor(0.0F, 0.0F, 1.0F, vertex, constant);

        assertEquals(1.0F, vertex[0], 1.0e-5F, "the colour is emitted");
        assertEquals(0.8F, constant[0], 1.0e-5F, "and the diffuse term is still there, from the default material");
    }

    @Test
    void specularChangesNothingBecauseTheModelHasNoSpecularTerm() {
        float[] vertex = new float[3];
        float[] constant = new float[3];
        lit(LightingModel.SPECULAR, true).shadeFactor(0.0F, 0.0F, 1.0F, vertex, constant);

        assertEquals(0.0F, vertex[0], 1.0e-5F, "no component follows the colour");
        assertEquals(0.8F, constant[0], 1.0e-5F, "which is the same picture as colour material being off");
    }

    @Test
    void colourMaterialOffIgnoresTheMode() {
        float[] vertex = new float[3];
        float[] constant = new float[3];
        lit(LightingModel.AMBIENT_AND_DIFFUSE, false).shadeFactor(0.0F, 0.0F, 1.0F, vertex, constant);

        assertEquals(0.0F, vertex[0], 1.0e-5F, "the vertex colour is not the material");
        assertTrue(constant[0] > 0.0F, "the material's own lit colour is what draws");
    }
}
