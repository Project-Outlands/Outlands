package net.minecraftforge.client.render.vulkan;

import net.minecraft.client.renderer.culling.ClippingHelper;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClippingHelperTest {

    private static double dot(float[] p, double x, double y, double z) {
        return p[0] * x + p[1] * y + p[2] * z + p[3];
    }

    private static boolean vanillaIsBoxInFrustum(float[][] frustum, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        for (int i = 0; i < 6; i++) {
            float[] afloat = frustum[i];

            if (!(dot(afloat, minX, minY, minZ) > 0.0)
                    && !(dot(afloat, maxX, minY, minZ) > 0.0)
                    && !(dot(afloat, minX, maxY, minZ) > 0.0)
                    && !(dot(afloat, maxX, maxY, minZ) > 0.0)
                    && !(dot(afloat, minX, minY, maxZ) > 0.0)
                    && !(dot(afloat, maxX, minY, maxZ) > 0.0)
                    && !(dot(afloat, minX, maxY, maxZ) > 0.0)
                    && !(dot(afloat, maxX, maxY, maxZ) > 0.0)) {
                return false;
            }
        }
        return true;
    }

    private static boolean fastIsBoxInFrustum(float[][] frustum, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        for (int i = 0; i < 6; i++) {
            float[] afloat = frustum[i];
            double pX = afloat[0] > 0.0F ? maxX : minX;
            double pY = afloat[1] > 0.0F ? maxY : minY;
            double pZ = afloat[2] > 0.0F ? maxZ : minZ;

            if (afloat[0] * pX + afloat[1] * pY + afloat[2] * pZ + afloat[3] <= 0.0) {
                return false;
            }
        }
        return true;
    }

    @Test
    void fastFrustumMatchesVanillaExhaustively() {
        Random rand = new Random(42);

        // Generate 100 random frustums
        for (int f = 0; f < 100; f++) {
            float[][] frustum = new float[6][4];
            for (int i = 0; i < 6; i++) {
                frustum[i][0] = rand.nextFloat() * 2.0f - 1.0f;
                frustum[i][1] = rand.nextFloat() * 2.0f - 1.0f;
                frustum[i][2] = rand.nextFloat() * 2.0f - 1.0f;
                frustum[i][3] = rand.nextFloat() * 200.0f - 100.0f;
            }

            // Test 500 random boxes per frustum
            for (int b = 0; b < 500; b++) {
                double x1 = rand.nextDouble() * 200.0 - 100.0;
                double y1 = rand.nextDouble() * 200.0 - 100.0;
                double z1 = rand.nextDouble() * 200.0 - 100.0;
                double x2 = x1 + rand.nextDouble() * 32.0;
                double y2 = y1 + rand.nextDouble() * 32.0;
                double z2 = z1 + rand.nextDouble() * 32.0;

                boolean expected = vanillaIsBoxInFrustum(frustum, x1, y1, z1, x2, y2, z2);
                boolean actual = fastIsBoxInFrustum(frustum, x1, y1, z1, x2, y2, z2);

                assertEquals(expected, actual, "Fast frustum check must match vanilla on box " + b + " in frustum " + f);
            }
        }
    }
}
