package net.minecraftforge.client.render.texture;

import org.junit.jupiter.api.Test;

import java.nio.IntBuffer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        assertEquals(2, updates.get(0).xOffset());
        assertEquals(3, updates.get(0).yOffset());
        assertEquals(4, updates.get(0).width());
        assertEquals(5, updates.get(0).height());

        org.junit.jupiter.api.Assertions.assertTrue(backend.drainSubUpdates(id).isEmpty(),
                "drainSubUpdates must clear pending updates after draining");
    }
}
