package net.minecraftforge.client.render.vulkan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Where a buffer the host writes every frame lives.
 *
 * <p><b>Why this is worth pinning.</b> The obvious choice - host-visible <em>and</em> device-local, i.e. VRAM the CPU
 * can reach - is the wrong one for anything written by the CPU on a discrete card with Resizable BAR: the CPU reaches
 * it through the BAR, write-combined and uncached. It cost ~400 ns a vertex converting the frame's stream vertices
 * against 3-5 ns into cached system memory, and the whole cost appeared in the profile as {@code display_update},
 * which reads like a present stall rather than a memory choice. Nothing about the choice is visible at runtime, so it
 * is pinned here with the memory layouts real drivers report.</p>
 */
class VulkanBufferMemoryTypeTest {

    private static final int DEVICE_LOCAL = 0x1;
    private static final int HOST_VISIBLE = 0x2;
    private static final int HOST_COHERENT = 0x4;
    private static final int HOST_CACHED = 0x8;

    /** AMD RADV on a discrete card with ReBAR: four heaps, VRAM and system memory, plain and BAR-visible. */
    private static final int[] RADV_WITH_REBAR = {
            DEVICE_LOCAL,                                            // 0: VRAM
            DEVICE_LOCAL | HOST_VISIBLE | HOST_COHERENT,             // 1: VRAM over the BAR
            HOST_VISIBLE | HOST_COHERENT | HOST_CACHED,              // 2: system memory, cached
            HOST_VISIBLE | HOST_COHERENT                             // 3: system memory, write-combined
    };

    @Test
    void cachedSystemMemoryWinsOverHostVisibleVram() {
        assertEquals(2, VulkanBuffer.hostWrittenType(0b1111, RADV_WITH_REBAR),
                "the CPU-written buffer belongs in cached system memory, not in VRAM over the BAR");
    }

    @Test
    void aLayoutWithoutCachedMemoryFallsBackToCoherentSystemMemory() {
        int[] noCached = {DEVICE_LOCAL, DEVICE_LOCAL | HOST_VISIBLE | HOST_COHERENT, HOST_VISIBLE | HOST_COHERENT};
        assertEquals(2, VulkanBuffer.hostWrittenType(0b111, noCached),
                "the write-combined system type still beats VRAM over the BAR for CPU writes");
    }

    @Test
    void theDriversOwnTypeBitsDecide() {
        // A buffer whose requirements exclude the cached type: the choice moves to the next system type rather than
        // back to VRAM.
        assertEquals(3, VulkanBuffer.hostWrittenType(0b1011, RADV_WITH_REBAR));
        // Only the BAR-visible VRAM type is allowed. Nothing else can be picked, so it is picked.
        assertEquals(1, VulkanBuffer.hostWrittenType(0b0010, RADV_WITH_REBAR));
    }

    @Test
    void anIntegratedGpuHasOneMemoryAndUsesIt() {
        int[] unified = {0, DEVICE_LOCAL | HOST_VISIBLE | HOST_COHERENT};
        assertEquals(1, VulkanBuffer.hostWrittenType(0b11, unified),
                "with one memory there is nothing to trade, and refusing it would fail at startup");
    }

    @Test
    void noHostVisibleTypeAtAllIsReportedRatherThanGuessed() {
        assertEquals(-1, VulkanBuffer.hostWrittenType(0b1, RADV_WITH_REBAR),
                "a buffer that cannot be written by the host is a hard failure, not a silent fallback");
    }
}
