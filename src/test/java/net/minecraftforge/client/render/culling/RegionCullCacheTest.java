package net.minecraftforge.client.render.culling;

import net.minecraft.client.renderer.culling.ClippingHelper;
import net.minecraft.client.renderer.culling.Frustum;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RegionCullCacheTest {

    @Test
    void testClippingHelperIntersectBox() {
        ClippingHelper helper = new ClippingHelper();
        // Setup a simple frustum:
        // Left plane:   x >= -10  => 1*x + 0*y + 0*z + 10 >= 0
        // Right plane:  x <= 10   => -1*x + 0*y + 0*z + 10 >= 0
        // Bottom plane: y >= -10  => 0*x + 1*y + 0*z + 10 >= 0
        // Top plane:    y <= 10   => 0*x - 1*y + 0*z + 10 >= 0
        // Near plane:   z >= 0    => 0*x + 0*y + 1*z + 0 >= 0
        // Far plane:    z <= 50   => 0*x + 0*y - 1*z + 50 >= 0
        helper.frustum[0] = new float[]{1.0f, 0.0f, 0.0f, 10.0f};
        helper.frustum[1] = new float[]{-1.0f, 0.0f, 0.0f, 10.0f};
        helper.frustum[2] = new float[]{0.0f, 1.0f, 0.0f, 10.0f};
        helper.frustum[3] = new float[]{0.0f, -1.0f, 0.0f, 10.0f};
        helper.frustum[4] = new float[]{0.0f, 0.0f, 1.0f, 0.0f};
        helper.frustum[5] = new float[]{0.0f, 0.0f, -1.0f, 50.0f};

        // Fully inside: [-5, -5, 10] to [5, 5, 20]
        assertEquals(ClippingHelper.FULLY_INSIDE, helper.intersectBox(-5, -5, 10, 5, 5, 20));

        // Outside: [20, 20, 10] to [30, 30, 20] (fails right plane)
        assertEquals(ClippingHelper.OUTSIDE, helper.intersectBox(20, 20, 10, 30, 30, 20));

        // Partially inside: [5, 5, 10] to [15, 15, 20] (straddles x=10 right plane)
        assertEquals(ClippingHelper.PARTIALLY_INSIDE, helper.intersectBox(5, 5, 10, 15, 15, 20));
    }

    @Test
    void testRegionCullCache() {
        ClippingHelper helper = new ClippingHelper();
        // Setup camera at (0, 0, 0)
        // Frustum wide enough to contain region (0, 0, 0) which is [0..128, 0..64, 0..128]
        // Plane: left x >= -100, right x <= 300, bottom y >= -100, top y <= 200, near z >= -100, far z <= 300
        helper.frustum[0] = new float[]{1.0f, 0.0f, 0.0f, 100.0f};
        helper.frustum[1] = new float[]{-1.0f, 0.0f, 0.0f, 300.0f};
        helper.frustum[2] = new float[]{0.0f, 1.0f, 0.0f, 100.0f};
        helper.frustum[3] = new float[]{0.0f, -1.0f, 0.0f, 200.0f};
        helper.frustum[4] = new float[]{0.0f, 0.0f, 1.0f, 100.0f};
        helper.frustum[5] = new float[]{0.0f, 0.0f, -1.0f, 300.0f};

        Frustum frustum = new Frustum(helper);
        frustum.setPosition(0, 0, 0);

        RegionCullCache cache = new RegionCullCache();
        cache.begin(1);

        // Region (0, 0, 0): [0..128, 0..64, 0..128] -> padded: [-2..130, -2..66, -2..130] -> fully inside [-100..300]
        int state = cache.classify(0, 0, 0, frustum);
        assertEquals(RegionCullCache.FULLY_INSIDE, state);

        // Second query in same frame: should hit cache
        assertEquals(RegionCullCache.FULLY_INSIDE, cache.classify(0, 0, 0, frustum));

        // Region (5, 0, 0): [640..768, 0..64, 0..128] -> outside (x > 300)
        assertEquals(RegionCullCache.OUTSIDE, cache.classify(5, 0, 0, frustum));
    }
}
