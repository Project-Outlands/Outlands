package net.minecraftforge.client.render.vulkan;

import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

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

    private final Int2ObjectMap<Entry> entries = new Int2ObjectOpenHashMap<>();

    /**
     * Textures whose image is owned elsewhere, by a render target.
     *
     * <p>A render target's colour attachment is written by drawing into it, not by uploading pixels, so it has no
     * {@code TextureImage} to stage from - and copying it into a texture of our own would double the memory and add a
     * copy per frame. It is registered here by view instead, and resolved like any other texture: the handle the game
     * binds is the key.</p>
     *
     * <p>The descriptor set is allocated once per image and reused, because a target's view does not change unless it
     * is resized.</p>
     */
    private final Int2ObjectMap<External> externals = new Int2ObjectOpenHashMap<>();
    /** Samplers, keyed by the GL parameters that produced them. Vanilla uses a handful of combinations. */
    private final Long2LongMap samplers = new Long2LongOpenHashMap();
    /** Staging buffers for uploads recorded in this frame, per slot, freed after that frame's fence. */
    private final List<VulkanBuffer>[] pendingStaging;
    private final List<VulkanBuffer> stagingPool = new ArrayList<>();

    private final VulkanImage white;
    private final long whiteSampler;
    private final long whiteSet;
    /** Whether the white stand-in image has been filled; it cannot be written before a command buffer exists. */
    private boolean whiteUploaded;

    /**
     * Whether this cache has been shut down.
     *
     * <p>Render targets outlive the renderer: the game deletes its framebuffers after the display is gone. Their
     * handles and images are gone at that point, so releasing one is a no-op rather than a use-after-free.</p>
     */
    private boolean closed;

    /**
     * A texture that is on the device and up to date.
     *
     * @param uploadedVersion the texture version the image currently holds
     * @param descriptorSet   the set that binds it, reused for every draw that samples this texture
     */
    private record Entry(VulkanImage image, long sampler, long descriptorSet, long uploadedVersion) {}

    /**
     * A texture backed by an image this class does not own.
     *
     * @param view     the colour attachment's view
     * @param sampler  the sampler the game's filter settings produced
     * @param set      the descriptor set that binds view and sampler together
     */
    private record External(long view, long sampler, long set, int width, int height) {}

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

        this.samplers.defaultReturnValue(0L);
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
            stagingFull(commandBuffer, slot, white,
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
        External external = externals.get(textureId);
        if (external != null) {
            // A render target: its image is written by drawing, so there is nothing to upload and nothing to stage.
            return external.set();
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
            source.drainSubUpdates(textureId);
            stagingFull(commandBuffer, slot, entry.image(), image);
        } else if (entry.image().width() != image.width() || entry.image().height() != image.height()) {
            // A texture cannot be resized in place, and GL did allow it: glTexImage2D on an existing name replaces
            // the level at a possibly different size. The sampler survives, so only the image and its set change.
            entry.image().close();
            VulkanImage created = VulkanImage.create(context.device(), context, image.width(), image.height(), format);
            long set = descriptors.allocate(created.view(), entry.sampler());
            entry = new Entry(created, entry.sampler(), set, -1L);
            entries.put(textureId, entry);
            source.drainSubUpdates(textureId);
            stagingFull(commandBuffer, slot, entry.image(), image);
        } else {
            java.util.List<TextureBackend.SubUpdate> subUpdates = source.drainSubUpdates(textureId);
            if (!subUpdates.isEmpty()) {
                stagingSub(commandBuffer, slot, entry.image(), image, subUpdates);
            } else {
                stagingFull(commandBuffer, slot, entry.image(), image);
            }
        }

        entries.put(textureId, new Entry(entry.image(), entry.sampler(), entry.descriptorSet(), image.version()));
        return entry.descriptorSet();
    }

    private VulkanBuffer acquireStaging(long byteSize) {
        for (int i = 0; i < stagingPool.size(); i++) {
            VulkanBuffer candidate = stagingPool.get(i);
            if (candidate.size() >= byteSize) {
                return stagingPool.remove(i);
            }
        }
        return VulkanBuffer.createStaging(context.device(), context, byteSize);
    }

    private void stagingFull(VkCommandBuffer commandBuffer, int slot, VulkanImage target, TextureImage image) {
        int[] argb = image.argb();
        int byteSize = argb.length * 4;
        VulkanBuffer buffer = acquireStaging(byteSize);
        org.lwjgl.system.MemoryUtil.memIntBuffer(buffer.mappedAddress(), argb.length).put(argb);
        pendingStaging[slot].add(buffer);
        target.upload(commandBuffer, buffer.handle());
    }

    private void stagingSub(VkCommandBuffer commandBuffer, int slot, VulkanImage target, TextureImage image,
                            java.util.List<TextureBackend.SubUpdate> subUpdates) {
        long totalBytes = 0L;
        for (TextureBackend.SubUpdate sub : subUpdates) {
            totalBytes += (long) sub.width() * sub.height() * 4L;
        }
        if (totalBytes <= 0L) {
            return;
        }
        VulkanBuffer buffer = acquireStaging(totalBytes);
        int[] argb = image.argb();
        int imageWidth = image.width();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            org.lwjgl.vulkan.VkBufferImageCopy.Buffer regions =
                    org.lwjgl.vulkan.VkBufferImageCopy.calloc(subUpdates.size(), stack);

            long currentByteOffset = 0L;
            for (int i = 0; i < subUpdates.size(); i++) {
                TextureBackend.SubUpdate sub = subUpdates.get(i);
                int subPixels = sub.width() * sub.height();

                java.nio.IntBuffer mapped = org.lwjgl.system.MemoryUtil.memIntBuffer(
                        buffer.mappedAddress() + currentByteOffset, subPixels);
                for (int row = 0; row < sub.height(); row++) {
                    mapped.position(row * sub.width());
                    mapped.put(argb, (sub.yOffset() + row) * imageWidth + sub.xOffset(), sub.width());
                }

                regions.get(i)
                        .bufferOffset(currentByteOffset)
                        .bufferRowLength(0)
                        .bufferImageHeight(0);
                regions.get(i).imageOffset().x(sub.xOffset()).y(sub.yOffset()).z(0);
                regions.get(i).imageExtent().width(sub.width()).height(sub.height()).depth(1);
                regions.get(i).imageSubresource().aspectMask(org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                        .mipLevel(0).baseArrayLayer(0).layerCount(1);

                currentByteOffset += (long) subPixels * 4L;
            }

            pendingStaging[slot].add(buffer);
            target.subUpload(commandBuffer, buffer.handle(), regions);
        }
    }

    /**
     * Releases this slot's staging buffers back into the pool.
     *
     * <p>Called once the fence for {@code slot} has been waited on, which is the same point the stream frees its
     * vertex buffer. Calling it earlier would free memory a pending copy still reads.</p>
     */
    void releaseSlot(int slot) {
        stagingPool.addAll(pendingStaging[slot]);
        pendingStaging[slot].clear();
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
     * Reserves a texture handle for a render target, before its image exists.
     *
     * <p>The handle comes from the texture backend's own namespace, so it cannot collide with a texture the game
     * creates later - which a hand-picked number could. Until {@link #attachExternal} is called, sampling it gives the
     * white stand-in, which is also what a GL framebuffer texture with no storage does.</p>
     */
    int createExternal() {
        return source.create();
    }

    /**
     * Points a reserved handle at a render target's colour attachment.
     *
     * <p>Called again on resize (new view, same handle) and when the game changes the filter (new sampler, same view);
     * the set is reallocated in both cases because the old one would still name the old image or sampler. Descriptor
     * sets are not freed individually - the pool owns them - so this leaks one set per resize, which is bounded by how
     * often the game resizes a framebuffer.</p>
     */
    void attachExternal(int textureId, long view, int width, int height, long sampler) {
        if (closed) {
            return;
        }
        long set = descriptors.allocate(view, sampler);
        externals.put(textureId, new External(view, sampler, set, width, height));
    }

    /**
     * Releases a reserved handle.
     *
     * <p>{@code source.delete} is called as well: the id came from that backend's allocator, and leaving it registered
     * would make the game's own {@code deleteTexture} on a resurrected id mean something it does not.</p>
     */
    void releaseExternal(int textureId) {
        if (closed) {
            return;
        }
        externals.remove(textureId);
        source.delete(textureId);
    }

    /** Whether a handle is registered as a render target's colour attachment. */
    boolean hasExternal(int textureId) {
        return externals.containsKey(textureId);
    }

    /**
     * A sampler for a render target, from the GL filter the game asked for.
     *
     * <p>Only the filter matters here: a framebuffer texture is sampled at its own size with no mip chain, and
     * clamping is what stops a pass reading past the edge of the image it is processing.</p>
     */
    long renderTargetSampler(int glFilter) {
        return sampler(glFilter, glFilter, 33071, 33071);
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
        long existing = samplers.get(key);
        if (existing != 0L) {
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
        closed = true;
        for (int slot = 0; slot < pendingStaging.length; slot++) {
            releaseSlot(slot);
        }
        for (VulkanBuffer buffer : stagingPool) {
            buffer.close();
        }
        stagingPool.clear();
        for (Entry entry : entries.values()) {
            entry.image().close();
        }
        entries.clear();
        // The images behind these belong to render targets, which destroy them; only the sets go with the pool.
        externals.clear();
        for (long sampler : samplers.values()) {
            vkDestroySampler(context.device(), sampler, null);
        }
        samplers.clear();
        white.close();
        // whiteSampler is not destroyed separately: it came out of sampler(), so it is already in the map above and
        // destroying it twice is what the validation layers reported as "Couldn't find VkSampler Object".
    }
}
