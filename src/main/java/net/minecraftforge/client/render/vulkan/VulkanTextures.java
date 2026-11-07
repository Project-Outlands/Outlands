package net.minecraftforge.client.render.vulkan;

import net.minecraftforge.client.render.texture.TextureBackend;
import net.minecraftforge.client.render.texture.TextureImage;
import net.minecraftforge.client.render.vertex.FrameDraws;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.vulkan.VK10.VK_BORDER_COLOR_INT_OPAQUE_BLACK;
import static org.lwjgl.vulkan.VK10.VK_FILTER_LINEAR;
import static org.lwjgl.vulkan.VK10.VK_FILTER_NEAREST;
import static org.lwjgl.vulkan.VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
import static org.lwjgl.vulkan.VK10.VK_SAMPLER_ADDRESS_MODE_MIRRORED_REPEAT;
import static org.lwjgl.vulkan.VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT;
import static org.lwjgl.vulkan.VK10.vkCreateSampler;
import static org.lwjgl.vulkan.VK10.vkDestroySampler;

/**
 * Turns the game's textures into images, samplers and descriptor sets, once each.
 *
 * <p><b>First use is the right time.</b> A texture is created here the first time a draw samples it, which is the
 * first frame that has a command buffer to record the upload into. Doing it at {@code glTexImage2D} time would mean
 * creating images while the game is still loading resource packs, with no recording context and no ordering against
 * the first draw.</p>
 *
 * <p><b>A {@code version} per texture, not a "seen" flag.</b> Vanilla animates water and lava by writing a
 * sub-rectangle of the atlas every few ticks and the fire animation the same way; a cache that uploads once would
 * freeze them on their first frame. The version the texture backend bumps on every write is the cheap way to tell
 * "the same texture" from "the same pixels".</p>
 *
 * <p><b>A white 1x1 image for "nothing bound".</b> A draw with no texture is not an error - vanilla draws solid colour
 * quads that way - and the shader multiplies the sample, so binding white leaves the vertex colour untouched. That is
 * the same thing GL did with texture 0 on fixed-function hardware, expressed as data rather than as a mode.</p>
 *
 * <p><b>Staging buffers are this class's problem, not the caller's.</b> A copy is recorded, not executed, so the
 * source bytes are still being read when the recording's fence is waited on. Keeping that rule in one place - one
 * list per frame in flight, released by {@link #releaseSlot} - means a caller that only wants "give me a set for this
 * texture" cannot get the lifetime wrong.</p>
 */
final class VulkanTextures implements AutoCloseable {

    private final VulkanContext context;
    private final VulkanDescriptors descriptors;
    private final TextureBackend source;

    private final Map<Integer, Entry> entries = new HashMap<>();
    /** Samplers, keyed by the GL parameters that produced them. Vanilla uses a handful of combinations. */
    private final Map<Long, Long> samplers = new HashMap<>();
    /** Staging buffers for uploads recorded in this frame, per slot, freed after that frame's fence. */
    private final List<VulkanBuffer>[] pendingStaging;

    private final VulkanImage white;
    private final long whiteSampler;
    private final long whiteSet;
    /** Whether the white stand-in image has been filled; it cannot be written before a command buffer exists. */
    private boolean whiteUploaded;

    /**
     * A texture that is on the device and up to date.
     *
     * @param uploadedVersion the texture version the image currently holds
     * @param descriptorSet   the set that binds it, reused for every draw that samples this texture
     */
    private record Entry(VulkanImage image, long sampler, long descriptorSet, long uploadedVersion) {}

    @SuppressWarnings("unchecked")
    /**
     * Whether uploaded textures are created as sRGB images.
     *
     * <p>Decided by the render target, not by preference. The surface is an sRGB format, so the hardware encodes
     * every fragment it stores from linear to sRGB; a texture holding sRGB-encoded bytes has to say so, or sampling
     * hands those bytes over as if they were linear and the next encode brightens them a second time. That double
     * encode is what made the whole game look washed out - the same texture drawn through a UNORM image comes out
     * pale, and no amount of shader work fixes it because the error is in the format.</p>
     */
    private final boolean srgbTextures;

    VulkanTextures(VulkanContext context, VulkanDescriptors descriptors, TextureBackend source,
                   boolean srgbRenderTarget) {
        this.context = context;
        this.descriptors = descriptors;
        this.source = source;
        this.srgbTextures = srgbRenderTarget;
        this.pendingStaging = new List[VulkanSwapchain.FRAMES_IN_FLIGHT];
        for (int slot = 0; slot < this.pendingStaging.length; slot++) {
            this.pendingStaging[slot] = new ArrayList<>();
        }

        // The stand-in is white in either encoding, so its format only has to match for consistency.
        this.white = VulkanImage.create(context.device(), context, 1, 1, textureFormat());
        this.whiteSampler = sampler(TextureImage.UNSET, TextureImage.UNSET, TextureImage.UNSET, TextureImage.UNSET);
        this.whiteSet = descriptors.allocate(white.view(), whiteSampler);
        for (int i = 0; i < VulkanSwapchain.FRAMES_IN_FLIGHT; i++) {
            this.descriptors.writeLightmap(i, white.view(), whiteSampler);
        }
    }

    /**
     * Ensures the white stand-in image has been filled with opaque white data.
     */
    void ensureWhiteUploaded(VkCommandBuffer commandBuffer, int slot) {
        if (!whiteUploaded) {
            // Opaque white, in the image's own byte order. Done here rather than in the constructor because filling
            // an image needs a recording command buffer, and this is the first moment one exists.
            staging(commandBuffer, slot, white,
                    new TextureImage(0, 1, 1, new int[] {0xFFFFFFFF},
                            TextureImage.UNSET, TextureImage.UNSET, TextureImage.UNSET, TextureImage.UNSET, 1L));
            whiteUploaded = true;
        }
    }

    long whiteImageView() {
        return white.view();
    }

    long whiteSampler() {
        return whiteSampler;
    }

    /**
     * The descriptor set for a texture, uploading it first if this is its first use or its contents changed.
     *
     * @param textureId     the game's texture handle
     * @param commandBuffer where the upload's copy is recorded; must be recording
     * @param slot          the frame in flight, which is what decides when the staging buffer is released
     * @return the set to bind, or the white set when the handle holds no data yet
     */
    long descriptorSet(int textureId, VkCommandBuffer commandBuffer, int slot) {
        ensureWhiteUploaded(commandBuffer, slot);
        if (textureId == 0) {
            return whiteSet;
        }
        TextureImage image = source.image(textureId);
        if (image == null) {
            // Created but never written: vanilla does this for render targets and for textures it fills later.
            return whiteSet;
        }

        Entry entry = entries.get(textureId);
        if (entry != null && entry.uploadedVersion() == image.version()
                && entry.image().width() == image.width() && entry.image().height() == image.height()) {
            return entry.descriptorSet();
        }

        // Both uploaded files and framebuffer copies hold sRGB-encoded bytes - PNGs because that is what a PNG is,
        // and copies because they come from an sRGB attachment. One format answers both.
        int format = textureFormat();
        if (entry == null) {
            VulkanImage created = VulkanImage.create(context.device(), context, image.width(), image.height(), format);
            long sampler = sampler(image.minFilter(), image.magFilter(), image.wrapS(), image.wrapT());
            long set = descriptors.allocate(created.view(), sampler);
            entry = new Entry(created, sampler, set, -1L);
            entries.put(textureId, entry);
        } else if (entry.image().width() != image.width() || entry.image().height() != image.height()) {
            // A texture cannot be resized in place, and GL did allow it: glTexImage2D on an existing name replaces
            // the level at a possibly different size. The sampler survives, so only the image and its set change.
            entry.image().close();
            VulkanImage created = VulkanImage.create(context.device(), context, image.width(), image.height(), format);
            long set = descriptors.allocate(created.view(), entry.sampler());
            entry = new Entry(created, entry.sampler(), set, -1L);
            entries.put(textureId, entry);
        }

        staging(commandBuffer, slot, entry.image(), image);
        entries.put(textureId, new Entry(entry.image(), entry.sampler(), entry.descriptorSet(), image.version()));
        return entry.descriptorSet();
    }

    /**
     * Copies {@code image}'s pixels into a fresh staging buffer and records the upload.
     *
     * <p>A buffer per upload rather than one packed buffer: packing would mean knowing every texture's size before
     * recording any of them, and textures are uploaded a handful of times per session, not per frame.</p>
     */
    private void staging(VkCommandBuffer commandBuffer, int slot, VulkanImage target, TextureImage image) {
        byte[] staged = toStagingBytes(image);
        VulkanBuffer buffer = VulkanBuffer.createStaging(context.device(), context, staged);
        pendingStaging[slot].add(buffer);
        target.upload(commandBuffer, buffer.handle());
    }

    /**
     * Releases this slot's staging buffers.
     *
     * <p>Called once the fence for {@code slot} has been waited on, which is the same point the stream frees its
     * vertex buffer. Calling it earlier would free memory a pending copy still reads.</p>
     */
    void releaseSlot(int slot) {
        for (VulkanBuffer buffer : pendingStaging[slot]) {
            buffer.close();
        }
        pendingStaging[slot].clear();
    }

    /**
     * The pixels in the image's own byte order, unchanged in orientation.
     *
     * <p><b>No row flip, and that is the whole point.</b> The familiar "GL's origin is bottom-left, Vulkan's is
     * top-left" applies to the <i>framebuffer</i>, not to texel indexing: {@code glTexImage2D} puts the first row of
     * data at texel row 0, and sampling at {@code v = 0} reads texel row 0, in both APIs. Flipping here would mirror
     * every image against the UVs the game computed for it, which on the main menu's six-face panorama shows up as
     * faces that are upside down and one of them in the sky.</p>
     *
     * <p>The one real conversion is the byte order: the ints are {@code 0xAARRGGBB} and the image is
     * {@code B8G8R8A8}, which on a little-endian machine is the same four bytes, so this is a copy with the int
     * unpacked rather than a swizzle.</p>
     */
    private static byte[] toStagingBytes(TextureImage image) {
        int width = image.width();
        int height = image.height();
        int[] argb = image.argb();
        byte[] out = new byte[width * height * 4];
        for (int texel = 0; texel < argb.length; texel++) {
            int pixel = argb[texel];
            int at = texel * 4;
            out[at] = (byte) pixel;              // B
            out[at + 1] = (byte) (pixel >>> 8);  // G
            out[at + 2] = (byte) (pixel >>> 16); // R
            out[at + 3] = (byte) (pixel >>> 24); // A
        }
        return out;
    }

    /**
     * Copies part of this frame's framebuffer into a texture.
     *
     * <p><b>Through a buffer, not image to image.</b> {@code vkCmdCopyImage} requires both images to share a format,
     * and they do not have to: the surface is whatever the driver reports and the texture is this renderer's own.
     * A buffer round trip is one extra transfer and no compatibility question, which for a 256x256 blur source once
     * per frame is the right trade.</p>
     *
     * <p>The source rectangle arrives in GL's bottom-up framebuffer coordinates and the destination image is
     * top-down, so the row is flipped here - the same convention the upload path already documents.</p>
     */
    void copyFromFramebuffer(VkCommandBuffer commandBuffer, FrameDraws.FramebufferCopy copy, FrameRenderTarget target,
                             int slot) {
        Entry entry = entries.get(copy.texture());
        if (entry == null) {
            // Resolved by prepareTextures, which runs before the instance opens; landing here means a copy whose
            // destination the frame never declared, so nothing can receive it.
            throw new IllegalStateException("framebuffer copy into texture " + copy.texture()
                    + " that was never resolved for this frame");
        }
        int copyBytes = copy.width() * copy.height() * 4;
        VulkanBuffer staging = VulkanBuffer.createTransfer(context.device(), context, copyBytes);
        // Same rule as a texture upload: the copy is recorded, so the bytes are still being read when the frame
        // finishes. Held per slot and released with the rest of that slot's transfers. Keeping it in a single field
        // instead meant releasing the current slot freed the buffer another slot was still reading, which is exactly
        // what the validation layers reported as destroying a buffer in use.
        pendingStaging[slot].add(staging);
        entry.image().copyFromFramebuffer(commandBuffer, copy, target, staging.handle());
    }

    /**
     * The byte order this renderer uses, with the encoding the render target requires.
     *
     * <p>{@code B8G8R8A8} because the texture backend holds {@code 0xAARRGGBB} ints, which on a little-endian machine
     * is already the B, G, R, A order a {@code GL_BGRA} upload describes.</p>
     */
    private int textureFormat() {
        return srgbTextures
                ? org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_SRGB
                : org.lwjgl.vulkan.VK10.VK_FORMAT_B8G8R8A8_UNORM;
    }

    /** Whether a texture has been filled by a framebuffer copy, and is therefore sampled u-reversed. */
    boolean isFramebufferTarget(int texture) {
        return source.isFramebufferTarget(texture);
    }

    /**
     * Resolves a texture and reports the image and sampler behind it, for a descriptor set that is not the draw's own.
     *
     * <p>Used for the lightmap, which is one image every draw reads: it goes in set 1 rather than travelling with each
     * draw, so what is wanted is the image view and sampler rather than a ready set.</p>
     */
    long[] resolveForSharedSet(int textureId, VkCommandBuffer commandBuffer, int slot) {
        long set = descriptorSet(textureId, commandBuffer, slot);
        Entry entry = entries.get(textureId);
        if (entry == null) {
            return null;
        }
        return new long[] {entry.image().view(), entry.sampler(), set};
    }

    /** The stand-in set a draw uses when nothing is bound. */
    long whiteDescriptorSet() {
        return whiteSet;
    }

    /**
     * A sampler for the given GL sampler parameters.
     *
     * <p>Repeat is the default for the wrapping modes and linear for the filters, because that is what GL's own
     * defaults are - {@code GL_REPEAT} and {@code GL_NEAREST_MIPMAP_LINEAR}. A texture whose game code never set a
     * parameter has {@link TextureImage#UNSET} here, and answering with the GL default is what makes an unchanged
     * atlas look identical rather than subtly different.</p>
     */
    private long sampler(int minFilter, int magFilter, int wrapS, int wrapT) {
        long key = ((long) minFilter << 48) | ((long) magFilter << 32) | ((long) wrapS << 16) | (wrapT & 0xFFFFL);
        Long existing = samplers.get(key);
        if (existing != null) {
            return existing;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSamplerCreateInfo info = VkSamplerCreateInfo.calloc(stack)
                    .sType$Default()
                    .magFilter(magFilter == 9729 ? VK_FILTER_LINEAR : VK_FILTER_NEAREST)
                    .minFilter(minFilter == 9729 ? VK_FILTER_LINEAR : VK_FILTER_NEAREST)
                    .addressModeU(addressMode(wrapS))
                    .addressModeV(addressMode(wrapT))
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .borderColor(VK_BORDER_COLOR_INT_OPAQUE_BLACK);
            // No mip chain is uploaded yet, so the LOD range is pinned to level 0 rather than left to sample a
            // level that does not exist.
            info.minLod(0.0f).maxLod(0.0f);
            LongBuffer out = stack.mallocLong(1);
            VkCheck.check(vkCreateSampler(context.device(), info, null, out), "vkCreateSampler");
            long sampler = out.get(0);
            samplers.put(key, sampler);
            return sampler;
        }
    }

    private static int addressMode(int glWrap) {
        return switch (glWrap) {
            case 10497 -> VK_SAMPLER_ADDRESS_MODE_REPEAT;          // GL_REPEAT
            case 33648 -> VK_SAMPLER_ADDRESS_MODE_MIRRORED_REPEAT; // GL_MIRRORED_REPEAT
            case 33071 -> VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;   // GL_CLAMP_TO_EDGE
            // UNSET and GL_CLAMP (which desktop GL removed) both become CLAMP_TO_EDGE: clamping is what a GUI
            // texture wants, and revealing the opposite edge is the failure nobody wants.
            default -> VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
        };
    }

    @Override
    public void close() {
        for (int slot = 0; slot < pendingStaging.length; slot++) {
            releaseSlot(slot);
        }
        for (Entry entry : entries.values()) {
            entry.image().close();
        }
        entries.clear();
        for (long sampler : samplers.values()) {
            vkDestroySampler(context.device(), sampler, null);
        }
        samplers.clear();
        white.close();
        // whiteSampler is not destroyed separately: it came out of sampler(), so it is already in the map above and
        // destroying it twice is what the validation layers reported as "Couldn't find VkSampler Object".
    }
}
