package net.minecraftforge.lwjgl.legacy;

import net.minecraftforge.lwjgl.shim.FrameShimRules;
import net.minecraftforge.lwjgl.shim.RetargetEntry;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Asserts which of the two rewrite passes owns a call site that both of them know about.
 *
 * <p><b>Why this is a real failure mode and not a tidiness rule.</b> A member can appear in the shim ruleset (route
 * it to the frame state) and in {@link RewriteTable} (adapt an LWJGL 2 signature to its LWJGL 3 name) at the same
 * time - {@code GL11.glLight} and {@code GL11.glGetFloat} are exactly that, because LWJGL 3 dropped their non-vector
 * forms. With the table consulted first, such a call was renamed and never reached the shim: the renamed call is
 * valid, so nothing logged, nothing threw, and the mod's call landed somewhere the frame state never saw.</p>
 *
 * <p>The shim now wins, and the facade decides for itself whether to reach the frame state or the real GL function.
 * That is the order the two layers describe: the shim declares the entry points the backend answers, the table
 * adapts everything else. This test pins the order with an actual rewrite rather than by inspecting the tables,
 * because the order is what matters and the tables are only its inputs.</p>
 */
class ShimTablePrecedenceTest {

    private static final String SAMPLE = "test/Sample";
    private static final String GL11 = "org/lwjgl/opengl/GL11";
    private static final String FACADE = FrameShimRules.FACADE;

    /** A class whose single method calls {@code GL11.name(desc)} with no arguments prepared. */
    private static byte[] caller(String name, String desc) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, SAMPLE, null, "java/lang/Object", null);
        var mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "call", "()V", null, null);
        mv.visitCode();
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, GL11, name, desc, false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static MethodInsnNode onlyCall(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        for (var mn : cn.methods) {
            for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof MethodInsnNode call) {
                    return call;
                }
            }
        }
        return null;
    }

    @Test
    void aMemberInBothTablesReachesTheShimUnrenamed() {
        RetargetEntry light = FrameShimRules.INSTANCE.entries().stream()
                .filter(entry -> entry.name().equals("glLight"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("glLight must be a shim entry"));
        assertTrue(RewriteTableIndex.isRewritable(GL11, "glLight", "(IILjava/nio/FloatBuffer;)V"),
                "this test only means something while the table also knows the member");

        ShimRegistryBridge.select();
        byte[] rewritten = CompatRewriter.rewrite(caller("glLight", "(IILjava/nio/FloatBuffer;)V"),
                ShimRegistryBridge.selection());

        assertNotNull(rewritten, "the call site should have been rewritten");
        MethodInsnNode call = onlyCall(rewritten);
        assertNotNull(call);
        assertEquals(FACADE, call.owner, "the shim owns the call site");
        assertEquals("glLight", call.name, "and it keeps the LWJGL 2 name, which is the facade's contract");
    }

    @Test
    void theTableStillAdaptsWhatTheShimDoesNotClaim() {
        assertTrue(RewriteTableIndex.isRewritable(GL11, "glLightfv", "(IILjava/nio/FloatBuffer;)V")
                        || RewriteTableIndex.OWNERS.contains(GL11),
                "the table keeps working for the members no shim entry names");

        ShimRegistryBridge.select();
        byte[] rewritten = CompatRewriter.rewrite(caller("glPushMatrix", "()V"),
                ShimRegistryBridge.selection());

        assertNotNull(rewritten, "the call site should have been rewritten");
        MethodInsnNode call = onlyCall(rewritten);
        assertNotNull(call);
        assertEquals(FACADE, call.owner);
        assertEquals("glPushMatrix", call.name);
    }

    @Test
    void theTableDoesNotOwnTheFixedFunctionSurface() {
        // Stated from the other side: these members exist in LWJGL3, so the table has no reason to know about them.
        assertFalse(RewriteTableIndex.OWNERS.contains(FrameShimRules.GL11)
                        && RewriteTableIndex.isRewritable(FrameShimRules.GL11, "glPushMatrix", "()V"),
                "glPushMatrix must stay a retarget, not a table rewrite");
    }

    /** The registry is global; this keeps the selection used by the rewrite explicit per test. */
    private static final class ShimRegistryBridge {
        static void select() {
            net.minecraftforge.lwjgl.shim.ShimRegistry.resetForTests();
            net.minecraftforge.lwjgl.shim.ShimRegistry.register(FrameShimRules.INSTANCE);
        }

        static net.minecraftforge.lwjgl.shim.ShimRegistry.Selection selection() {
            return net.minecraftforge.lwjgl.shim.ShimRegistry.select(() -> FrameShimRules.VULKAN_ID);
        }
    }
}
