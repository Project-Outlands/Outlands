package net.minecraftforge.lwjgl.legacy;

import net.minecraftforge.lwjgl.shim.FrameShimRules;
import net.minecraftforge.lwjgl.shim.RetargetEntry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asserts that no shim entry is also a {@link RewriteTable} entry.
 *
 * <p><b>Why this is a real failure mode and not a tidiness rule.</b> The two passes run in a fixed order -
 * {@code CompatRewriter} consults the table first and only retargets a call site the table did not change - so a
 * member that appears in both is rewritten by the table and never reaches the shim. Nothing logs, nothing
 * throws: the mod's call lands somewhere in the table's adapters, the frame state never sees it, and the symptom
 * is a picture that is wrong in a way no stack trace explains.</p>
 *
 * <p>The overlap is plausible, too: the obvious way to fix "this call does nothing on the Vulkan backend" is to
 * add a table entry, and that is exactly the wrong layer for a call site that must reach the frame state.</p>
 */
class ShimTableDisjointnessTest {

    @Test
    void noShimEntryIsAlsoATableEntry() {
        for (RetargetEntry entry : FrameShimRules.INSTANCE.entries()) {
            assertFalse(RewriteTableIndex.isRewritable(entry.fromOwner(), entry.name(), entry.desc()),
                    () -> entry.name() + entry.desc() + " is in RewriteTable, so the table would win and the shim "
                            + "would never see the call site");
        }
    }

    @Test
    void theTableDoesNotOwnTheFixedFunctionSurface() {
        // The same fact from the other side, stated once: these members exist in LWJGL3, so the table has no reason
        // to know about them. If this ever changes, the retarget path silently stops working for them.
        assertFalse(RewriteTableIndex.OWNERS.contains(FrameShimRules.GL11)
                        && RewriteTableIndex.isRewritable(FrameShimRules.GL11, "glPushMatrix", "()V"),
                "glPushMatrix must stay a retarget, not a table rewrite");
        assertTrue(RewriteTableIndex.OWNERS.contains("org/lwjgl/opengl/GL11"),
                "the table does act on GL11 for genuinely missing members, which is why the owner alone is not a "
                        + "usable answer");
    }
}
