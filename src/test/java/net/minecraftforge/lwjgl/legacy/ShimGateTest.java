package net.minecraftforge.lwjgl.legacy;

import net.minecraftforge.lwjgl.shim.RetargetEntry;
import net.minecraftforge.lwjgl.shim.ShimEnvironment;
import net.minecraftforge.lwjgl.shim.ShimRegistry;
import net.minecraftforge.lwjgl.shim.ShimRewriteRules;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the constant-pool gate, i.e. the check that decides whether a class is worth rewriting at all.
 *
 * <p><b>The bug this is written against.</b> The gate used to answer "is there anything to rewrite here?" with
 * {@link RewriteTable} alone. A retargeted call site is not a table entry - {@code GL11.glPushMatrix} still
 * exists in LWJGL3, so nothing about it is a missing member - so a class whose entire GL surface is the
 * fixed-function subset was dropped before the rewriter ran. Measured on IC2Classic: sixty-three call sites, all
 * of them silently reaching real GL instead of the frame state. The first test below fails on the old code and
 * passes on the new.</p>
 */
class ShimGateTest {

    private static final String GL11 = "org/lwjgl/opengl/GL11";
    private static final String FACADE = "net/minecraftforge/client/render/shim/ShimGl";

    /** A class whose only LWJGL reference is {@code GL11.glPushMatrix()V} and {@code GL11.glPopMatrix()V}. */
    private static byte[] fixedFunctionOnlyClass() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, "test/FixedFunctionOnly", null,
                "java/lang/Object", null);
        var mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "draw", "()V", null, null);
        mv.visitCode();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, GL11, "glPushMatrix", "()V", false);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, GL11, "glPopMatrix", "()V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static List<MethodInsnNode> calls(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        List<MethodInsnNode> found = new java.util.ArrayList<>();
        for (MethodNode mn : cn.methods) {
            for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof MethodInsnNode call) {
                    found.add(call);
                }
            }
        }
        return found;
    }

    private static ShimRewriteRules ruleset() {
        return new ShimRewriteRules() {
            @Override
            public String id() {
                return "gate-test";
            }

            @Override
            public boolean appliesTo(ShimEnvironment env) {
                return true;
            }

            @Override
            public List<RetargetEntry> entries() {
                return List.of(
                        new RetargetEntry(GL11, "glPushMatrix", "()V", FACADE),
                        new RetargetEntry(GL11, "glPopMatrix", "()V", FACADE));
            }

            @Override
            public Set<String> exemptPrefixes() {
                return Set.of();
            }
        };
    }

    @Test
    void theTableAloneHasNothingToRewriteHere() {
        byte[] bytes = fixedFunctionOnlyClass();

        assertTrue(ConstantPoolRefs.mentionsLwjgl(bytes), "the cheap first gate should already pass");
        assertFalse(ConstantPoolRefs.hasLwjgl2MemberRef(bytes, null),
                "the table alone has nothing to rewrite here: these members exist in LWJGL3");
    }

    @Test
    void anEmptySelectionStillRejects() {
        ShimRegistry.resetForTests();
        ShimRegistry.Selection empty = ShimRegistry.select(() -> "vulkan");

        assertTrue(empty.isEmpty());
        assertFalse(ConstantPoolRefs.hasLwjgl2MemberRef(fixedFunctionOnlyClass(), empty));
    }

    @Test
    void aRetargetingRulesetLetsTheClassThrough() {
        ShimRegistry.resetForTests();
        ShimRegistry.register(ruleset());
        ShimRegistry.Selection selection = ShimRegistry.select(() -> "vulkan");

        assertTrue(ConstantPoolRefs.hasLwjgl2MemberRef(fixedFunctionOnlyClass(), selection),
                "with a ruleset that retargets these two calls, the class must reach the rewriter");
    }

    @Test
    void theTransformerLeavesAnUnrelatedClassAlone() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, "test/Unrelated", null,
                "java/lang/Object", null);
        var mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "call", "()V", null, null);
        mv.visitCode();
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL11", "glGetError", "()I", false);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        byte[] bytes = cw.toByteArray();

        ShimRegistry.resetForTests();
        ShimRegistry.register(ruleset());
        Lwjgl2CompatTransformer.resetSelectionForTests();

        assertSame(bytes, new Lwjgl2CompatTransformer().transform("test.Unrelated", "test.Unrelated", bytes),
                "a class whose only LWJGL reference is outside the ruleset must come back untouched");
    }

    @Test
    void theTransformerRewritesTheClassTheGateUsedToDrop() {
        ShimRegistry.resetForTests();
        ShimRegistry.register(ruleset());
        Lwjgl2CompatTransformer.resetSelectionForTests();

        byte[] bytes = fixedFunctionOnlyClass();
        byte[] rewritten = new Lwjgl2CompatTransformer().transform(
                "test.FixedFunctionOnly", "test.FixedFunctionOnly", bytes);

        assertNotNull(rewritten);
        assertNotSame(bytes, rewritten, "the class should have been rewritten");
        List<MethodInsnNode> calls = calls(rewritten);
        assertEquals(2, calls.size());
        for (MethodInsnNode call : calls) {
            assertEquals(FACADE, call.owner, call.name);
            assertTrue(call.name.equals("glPushMatrix") || call.name.equals("glPopMatrix"), call.name);
            assertEquals("()V", call.desc, "the descriptor must not change");
        }
    }
}
