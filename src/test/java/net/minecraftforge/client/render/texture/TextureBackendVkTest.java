package net.minecraftforge.client.render.texture;

import org.junit.jupiter.api.Test;

import java.nio.IntBuffer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The texture registry's behaviour, including the one idiom that is easy to break.
 */
class TextureBackendVkTest {

    private static IntBuffer pixels(int count) {
        IntBuffer buffer = IntBuffer.allocate(count);
        for (int i = 0; i < count; i++) {
            // 0xAARRGGBB, the layout vanilla hands over.
            buffer.put(0xFF000000 | i);
        }
        buffer.rewind();
        return buffer;
    }

    @Test
    void idsStartAtOneAndAreNeverReused() {
        TextureBackend backend = new TextureBackendVk();

        int first = backend.create();
        int second = backend.create();

        assertEquals(1, first);
        assertEquals(2, second, "a deleted handle must not be handed out again");
    }

    @Test
    void aDeletedTextureCanStillBeUsed() {
        // TextureUtil.allocateTextureImpl does deleteTexture(id); bindTexture(id); glTexParameteri(...);
        // glTexImage2D(...). GL revives a deleted name on the next upload. A backend that dropped the handle would
        // fail on the very next line, which is how this contract was discovered.
        TextureBackend backend = new TextureBackendVk();
        int id = backend.create();
        backend.upload(id, 0, 2, 2, pixels(4));

        backend.delete(id);
        assertEquals(0, backend.pendingDeviceUploads(), "delete drops the data");

        assertDoesNotThrow(() -> backend.parameter(id, 10241, 9987), "parameters on the same id must still work");
        backend.upload(id, 0, 2, 2, pixels(4));
        assertEquals(1, backend.pendingDeviceUploads(), "re-uploading revives it");
    }

    @Test
    void allocationWithoutDataRecordsTheSize() {
        TextureBackend backend = new TextureBackendVk();
        int id = backend.create();

        backend.upload(id, 0, 4, 4, null);

        // subUpload needs the size to compute the row stride; without it the write has nowhere to land.
        assertDoesNotThrow(() -> backend.subUpload(id, 0, 1, 1, 2, 2, pixels(4)),
                "the allocation-without-data path is exactly what TextureUtil uses for mip levels");
    }

    @Test
    void aSubUploadOutsideTheAllocationIsLoud() {
        TextureBackend backend = new TextureBackendVk();
        int id = backend.create();
        backend.upload(id, 0, 2, 2, null);

        assertThrows(IllegalStateException.class, () -> backend.subUpload(id, 0, 1, 1, 2, 2, pixels(4)),
                "a 2x2 write at 1,1 does not fit a 2x2 level");
    }

    @Test
    void aRealTextureReportsItsSize() {
        // CloudRenderer binds the cloud texture and reads width and height to size its quads. Answering from the
        // stored level is the whole point of keeping sizes separately from the pixels.
        TextureBackend backend = new TextureBackendVk();
        int id = backend.create();
        backend.upload(id, 0, 64, 32, null);

        assertEquals(64, backend.levelParameter(id, 0, 3553, 4096), "GL_TEXTURE_WIDTH");
        assertEquals(32, backend.levelParameter(id, 0, 3553, 4097), "GL_TEXTURE_HEIGHT");
    }

    @Test
    void theProxyTargetIsRejectedRatherThanGuessed() {
        // The proxy target is the maximum-size probe, which is answered by the backend capability instead. A
        // plausible number here would silently cap every atlas without saying so.
        TextureBackend backend = new TextureBackendVk();
        int id = backend.create();
        backend.upload(id, 0, 64, 64, null);

        assertThrows(UnsupportedOperationException.class, () -> backend.levelParameter(id, 0, 32868, 4096));
        assertThrows(UnsupportedOperationException.class, () -> backend.levelParameter(id, 0, 3553, 4099),
                "an unmodelled property is rejected, not defaulted");
    }

    @Test
    void anUnknownIdIsLoud() {
        TextureBackend backend = new TextureBackendVk();
        assertThrows(IllegalStateException.class, () -> backend.parameter(99, 10241, 9987));
    }

    @Test
    void texture2dStateIsTrackedPerUnit() {
        TextureBackend backend = new TextureBackendVk();
        // Unit 0 starts enabled
        org.junit.jupiter.api.Assertions.assertTrue(backend.isTexture2DEnabled());
        org.junit.jupiter.api.Assertions.assertTrue(backend.isTexture2DEnabled(0));
        org.junit.jupiter.api.Assertions.assertFalse(backend.isTexture2DEnabled(1));

        // Switch to unit 1, enable it
        backend.activeUnit(1);
        org.junit.jupiter.api.Assertions.assertFalse(backend.isTexture2DEnabled());
        backend.setTexture2DEnabled(true);
        org.junit.jupiter.api.Assertions.assertTrue(backend.isTexture2DEnabled());
        org.junit.jupiter.api.Assertions.assertTrue(backend.isTexture2DEnabled(1));

        // Disable unit 1
        backend.setTexture2DEnabled(false);
        org.junit.jupiter.api.Assertions.assertFalse(backend.isTexture2DEnabled());

        // Switch back to unit 0 - it must still be enabled!
        backend.activeUnit(0);
        org.junit.jupiter.api.Assertions.assertTrue(backend.isTexture2DEnabled());
    }

    @Test
    void subUploadRecordsDirtyRegionAndDrainsCleanly() {
        TextureBackend backend = new TextureBackendVk();
        int id = backend.create();
        backend.upload(id, 0, 16, 16, pixels(256));

        org.junit.jupiter.api.Assertions.assertTrue(backend.drainSubUpdates(id).isEmpty(),
                "full upload clears pending sub-updates");

        backend.subUpload(id, 0, 2, 3, 4, 5, pixels(20));
        java.util.List<TextureBackend.SubUpdate> updates = backend.drainSubUpdates(id);
        assertEquals(1, updates.size());
        assertEquals(0, updates.get(0).level());
        assertEquals(2, updates.get(0).xOffset());
        assertEquals(3, updates.get(0).yOffset());
        assertEquals(4, updates.get(0).width());
        assertEquals(5, updates.get(0).height());

        org.junit.jupiter.api.Assertions.assertTrue(backend.drainSubUpdates(id).isEmpty(),
                "drainSubUpdates must clear pending updates after draining");
    }

    @Test
    void subUploadAppliesDataForBothHeapAndDirectBuffers() {
        TextureBackend backend = new TextureBackendVk();
        int id = backend.create();
        backend.upload(id, 0, 4, 4, null); // 4x4 blank

        // 1. Heap buffer with sub-rectangle 2x2 at (1, 1)
        IntBuffer heapBuf = IntBuffer.allocate(4);
        heapBuf.put(new int[] { 0x11111111, 0x22222222, 0x33333333, 0x44444444 });
        heapBuf.flip();
        backend.subUpload(id, 0, 1, 1, 2, 2, heapBuf);

        int[] argb = backend.image(id).argb();
        assertEquals(0x11111111, argb[1 * 4 + 1]);
        assertEquals(0x22222222, argb[1 * 4 + 2]);
        assertEquals(0x33333333, argb[2 * 4 + 1]);
        assertEquals(0x44444444, argb[2 * 4 + 2]);
        assertEquals(0, argb[0]); // Untouched

        // 2. Direct buffer with full contiguous row overwrite 4x1 at (0, 0)
        java.nio.ByteBuffer directBytes = java.nio.ByteBuffer.allocateDirect(16)
                .order(java.nio.ByteOrder.nativeOrder());
        IntBuffer directBuf = directBytes.asIntBuffer();
        directBuf.put(new int[] { 0xAAAAAAAA, 0xBBBBBBBB, 0xCCCCCCCC, 0xDDDDDDDD });
        directBuf.flip();
        backend.subUpload(id, 0, 0, 0, 4, 1, directBuf);

        argb = backend.image(id).argb();
        assertEquals(0xAAAAAAAA, argb[0]);
        assertEquals(0xBBBBBBBB, argb[1]);
        assertEquals(0xCCCCCCCC, argb[2]);
        assertEquals(0xDDDDDDDD, argb[3]);
        assertEquals(0x11111111, argb[1 * 4 + 1]); // Previous subUpload still intact
    }

    @Test
    void theMipChainIsTheLevelsTheGameAllocated() {
        // TextureUtil.allocateTextureImpl's shape: every level allocated with a null pointer, then filled by
        // glTexSubImage2D. A backend that reported only level 0 sampled aliased textures at distance while the
        // chain it should have read sat in its own memory.
        TextureBackend backend = new TextureBackendVk();
        int id = backend.create();
        for (int level = 0; level < 4; level++) {
            backend.upload(id, level, Math.max(1, 8 >> level), Math.max(1, 8 >> level), null);
        }
        for (int level = 0; level < 4; level++) {
            int size = Math.max(1, 8 >> level);
            IntBuffer data = IntBuffer.allocate(size * size);
            for (int i = 0; i < size * size; i++) {
                // The level number in the high byte of the colour, so a chain that reported level 0 four times would
                // be caught rather than passing on a size check alone.
                data.put(0xFF000000 | (level << 16) | i);
            }
            data.flip();
            backend.subUpload(id, level, 0, 0, size, size, data);
        }

        TextureImage image = backend.image(id);
        assertEquals(4, image.levelCount());
        assertEquals(8, image.width());
        assertEquals(8, image.levelWidth(0));
        assertEquals(4, image.levelWidth(1));
        assertEquals(2, image.levelHeight(2));
        assertEquals(1, image.levelWidth(3));
        // Every level's own pixels, not level 0's reused four times.
        assertEquals(0xFF000000, image.level(0)[0]);
        assertEquals(0xFF010000, image.level(1)[0]);
        assertEquals(0xFF020000, image.level(2)[0]);
        assertEquals(0xFF030000, image.level(3)[0]);
        assertEquals(1, image.level(3).length);
    }

    @Test
    void aChainStopsWhereALevelIsMissingOrDoesNotHalve() {
        TextureBackend backend = new TextureBackendVk();
        int id = backend.create();
        backend.upload(id, 0, 8, 8, null);
        // Level 1 skipped; a Vulkan image cannot have a hole, so the chain is level 0 alone.
        backend.upload(id, 2, 2, 2, null);
        assertEquals(1, backend.image(id).levelCount());

        // A level that is not half of the one above it is equally unusable: GL allowed any size per level, a
        // device image does not.
        int other = backend.create();
        backend.upload(other, 0, 8, 8, null);
        backend.upload(other, 1, 8, 4, null);
        assertEquals(1, backend.image(other).levelCount());
    }

    @Test
    void anAnimatedSpriteRecordsEveryLevelItRewrites() {
        // TextureAtlasSprite.updateAnimation hands the whole chain to TextureUtil.uploadTextureMipmap, so a level
        // whose update went unrecorded would force the whole texture to be re-uploaded from level 0 - 32 MB of atlas
        // per animation tick.
        TextureBackend backend = new TextureBackendVk();
        int id = backend.create();
        backend.upload(id, 0, 8, 8, null);
        backend.upload(id, 1, 4, 4, null);
        backend.drainSubUpdates(id);

        backend.subUpload(id, 1, 1, 1, 2, 2, pixels(4));
        java.util.List<TextureBackend.SubUpdate> updates = backend.drainSubUpdates(id);
        assertEquals(1, updates.size());
        assertEquals(1, updates.get(0).level());
    }

    @Test
    void theSameRectangleWrittenTwiceIsOneUpload() {
        // An animated sprite rewrites its own rectangle of every level on each animation tick. The pixels are read
        // from this texture's copy at drain time, so only the fact that the rectangle is dirty matters - not how
        // often it was written. A list grew with the tick count instead: the block atlas is off screen while a world
        // loads, and enough ticks made the drain ask for an array of tens of thousands of regions.
        TextureBackend backend = new TextureBackendVk();
        int id = backend.create();
        backend.upload(id, 0, 16, 16, null);

        for (int tick = 0; tick < 1000; tick++) {
            backend.subUpload(id, 0, 4, 4, 8, 8, pixels(64));
        }

        java.util.List<TextureBackend.SubUpdate> updates = backend.drainSubUpdates(id);
        assertEquals(1, updates.size(), "the same rectangle a thousand times is one dirty rectangle");
        assertEquals(4, updates.get(0).xOffset());
    }

    @Test
    void tooManyDistinctRectanglesBecomeAFullUpload() {
        // A mod may write a different place every frame, which no deduplication can collapse. Past the bound the
        // answer is the full upload: an empty drain is how the caller is told to send the whole chain instead.
        TextureBackend backend = new TextureBackendVk();
        int id = backend.create();
        backend.upload(id, 0, 128, 128, null);
        for (int i = 0; i < 5000; i++) {
            backend.subUpload(id, 0, i % 126, (i / 126) % 126, 1, 1, pixels(1));
        }

        assertTrue(backend.drainSubUpdates(id).isEmpty(),
                "past the rectangle bound the answer is 'upload everything', which an empty drain means");

        // And the flag is spent, so the next writes are collected again rather than silently dropped.
        backend.subUpload(id, 0, 1, 1, 2, 2, pixels(4));
        assertEquals(1, backend.drainSubUpdates(id).size());
    }
}
