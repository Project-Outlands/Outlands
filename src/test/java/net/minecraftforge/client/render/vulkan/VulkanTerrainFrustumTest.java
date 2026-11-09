package net.minecraftforge.client.render.vulkan;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VulkanTerrainFrustumTest {

    @Test
    void boxInsidePerspectiveFrustumIsAccepted() {
        Matrix4f proj = new Matrix4f().perspective((float) Math.toRadians(70.0), 1.0f, 0.05f, 1000.0f);
        Matrix4f view = new Matrix4f().lookAt(0, 0, 0, 0, 0, -1, 0, 1, 0); // looking down -Z
        Matrix4f mvp = proj.mul(view);

        float[] m = new float[16];
        mvp.get(m);

        float[] planes = new float[24];
        VulkanTerrainLayer.extractFrustumPlanes(m, planes);

        // Box right in front of camera (Z from -10 to -26, X from -8 to 8, Y from -8 to 8)
        assertTrue(VulkanTerrainLayer.isBoxInFrustum(planes, -8, -8, -26, 8, 8, -10));

        // Box behind camera (Z from 10 to 26)
        assertFalse(VulkanTerrainLayer.isBoxInFrustum(planes, -8, -8, 10, 8, 8, 26));

        // Box far to the right outside 70-deg FOV (X from 100 to 116, Z around -10)
        assertFalse(VulkanTerrainLayer.isBoxInFrustum(planes, 100, -8, -26, 116, 8, -10));

        // Box far to the left outside 70-deg FOV (X from -116 to -100, Z around -10)
        assertFalse(VulkanTerrainLayer.isBoxInFrustum(planes, -116, -8, -26, -100, 8, -10));

        // Box far beyond the far plane (Z from -1500 to -1484)
        assertFalse(VulkanTerrainLayer.isBoxInFrustum(planes, -8, -8, -1500, 8, 8, -1484));

        // Camera is inside the chunk box (X from -8 to 8, Y from -8 to 8, Z from -8 to 8)
        assertTrue(VulkanTerrainLayer.isBoxInFrustum(planes, -8, -8, -8, 8, 8, 8));
    }

    @Test
    void worldSpaceFrustumAcceptsChunksInFrontAndCullsChunksBehind() {
        // Player at (-22, 4, -158), eye height 1.62. Facing East (yaw -85.9, pitch 23.4).
        double viewX = -22.0;
        double viewY = 4.0;
        double viewZ = -158.0;

        Matrix4f proj = new Matrix4f().perspective((float) Math.toRadians(70.0), 854.0f / 480.0f, 0.05f, 512.0f);
        Matrix4f view = new Matrix4f()
                .rotate((float) Math.toRadians(23.4), 1.0f, 0.0f, 0.0f)
                .rotate((float) Math.toRadians(-85.9 + 180.0), 0.0f, 1.0f, 0.0f)
                .translate(0.0f, -1.62f, 0.0f)
                .translate((float) -viewX, (float) -viewY, (float) -viewZ);

        Matrix4f mvp = proj.mul(view);
        float[] m = new float[16];
        mvp.get(m);

        float[] planes = new float[24];
        VulkanTerrainLayer.extractFrustumPlanes(m, planes);

        // Chunk containing the player: origin (-32, 0, -160)
        assertTrue(VulkanTerrainLayer.isBoxInFrustum(planes, -33, -1, -161, -15, 17, -143),
                "chunk containing the player must not be culled");

        // Chunk directly to the East (in front of the player looking East): origin (-16, 0, -160)
        assertTrue(VulkanTerrainLayer.isBoxInFrustum(planes, -17, -1, -161, 1, 17, -143),
                "chunk in front of the player must not be culled");

        // Chunk far behind the player (to the West): origin (-80, 0, -160)
        assertFalse(VulkanTerrainLayer.isBoxInFrustum(planes, -81, -1, -161, -63, 17, -143),
                "chunk behind the player must be culled");
    }
}
