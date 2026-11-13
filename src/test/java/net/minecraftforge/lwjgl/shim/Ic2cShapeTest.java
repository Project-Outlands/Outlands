package net.minecraftforge.lwjgl.shim;

import net.minecraftforge.lwjgl.legacy.CompatRewriter;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.BasicVerifier;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rewrites one class that calls the whole measured surface, in the shape IC2Classic calls it.
 *
 * <p><b>Why a synthetic class and not the jar.</b> The jar test needs a mod install to exist and is therefore
 * opt-in ({@code Ic2cJarTest}); this one runs everywhere and pins the two properties that matter: every call
 * site is retargeted, and the result still verifies. The sequence is copied from
 * {@code RenderPersonalChest.render} - push, enable rescale-normal, set colour, translate, scale, rotate,
 * disable, pop - plus the current-normal call from {@code RenderCrossed}, which is the mod's only other
 * fixed-function state.</p>
 */
class Ic2cShapeTest {

    private static final String SAMPLE = "test/Ic2cShape";
    private static final String GL11 = "org/lwjgl/opengl/GL11";

    /** The calls IC2C makes, in the order one of its renderers makes them. */
    private static final String[][] CALLS = {
            {"glPushMatrix", "()V"},
            {"glEnable", "(I)V"},
            {"glColor4f", "(FFFF)V"},
            {"glTranslatef", "(FFF)V"},
            {"glScalef", "(FFF)V"},
            {"glRotatef", "(FFFF)V"},
            {"glDisable", "(I)V"},
            {"glPopMatrix", "()V"},
            {"glNormal3f", "(FFF)V"},
            {"glDepthFunc", "(I)V"},
            {"glAlphaFunc", "(IF)V"},
            {"glViewport", "(IIII)V"},
            {"glClear", "(I)V"},
            {"glFinish", "()V"},
    };

    /** Pushes a zero of the right kind for every argument in the descriptor. */
    private static void pushZeros(org.objectweb.asm.MethodVisitor mv, String desc) {
        for (Type argument : Type.getArgumentTypes(desc)) {
            switch (argument.getSort()) {
                case Type.INT, Type.SHORT, Type.BYTE, Type.CHAR, Type.BOOLEAN -> mv.visitInsn(Opcodes.ICONST_0);
                case Type.FLOAT -> mv.visitInsn(Opcodes.FCONST_0);
                case Type.DOUBLE -> mv.visitInsn(Opcodes.DCONST_0);
                case Type.LONG -> mv.visitInsn(Opcodes.LCONST_0);
                default -> throw new IllegalArgumentException("unsupported argument: " + argument);
            }
        }
    }

    private static byte[] ic2cShape() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, SAMPLE, null, "java/lang/Object", null);
        var mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "render", "()V", null, null);
        mv.visitCode();
        for (String[] call : CALLS) {
            pushZeros(mv, call[1]);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, GL11, call[0], call[1], false);
        }
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** The production entries, made applicable regardless of which backend the test JVM resolved. */
    private static ShimRegistry.Selection productionEntries() {
        ShimRegistry.resetForTests();
        ShimRegistry.register(new ShimRewriteRules() {
            @Override
            public String id() {
                return "shape-test";
            }

            @Override
            public boolean appliesTo(ShimEnvironment env) {
                return true;
            }

            @Override
            public List<RetargetEntry> entries() {
                return FrameShimRules.INSTANCE.entries();
            }

            @Override
            public java.util.Set<String> exemptPrefixes() {
                return FrameShimRules.INSTANCE.exemptPrefixes();
            }
        });
        return ShimRegistry.select(() -> FrameShimRules.VULKAN_ID);
    }

    private static List<MethodInsnNode> calls(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        List<MethodInsnNode> found = new ArrayList<>();
        for (MethodNode mn : cn.methods) {
            for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof MethodInsnNode call) {
                    found.add(call);
                }
            }
        }
        return found;
    }

    private static void assertVerifies(byte[] bytes) throws Exception {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        for (MethodNode mn : cn.methods) {
            new Analyzer<BasicValue>(new BasicVerifier()).analyze(cn.name, mn);
        }
        StringWriter diagnostics = new StringWriter();
        CheckClassAdapter.verify(new ClassReader(bytes), false, new PrintWriter(diagnostics));
        assertTrue(diagnostics.toString().isEmpty(), diagnostics::toString);
    }

    @Test
    void everyCallSiteIsRetargetedAndTheClassStillVerifies() throws Exception {
        byte[] rewritten = CompatRewriter.rewrite(ic2cShape(), productionEntries());

        assertNotNull(rewritten, "the whole sequence should have been rewritten");
        List<MethodInsnNode> calls = calls(rewritten);
        assertEquals(CALLS.length, calls.size(), "no call may be added or removed");
        for (int i = 0; i < CALLS.length; i++) {
            MethodInsnNode call = calls.get(i);
            assertEquals(FrameShimRules.FACADE, call.owner, CALLS[i][0] + " should go through the facade");
            assertEquals(CALLS[i][0], call.name, "the name must not change");
            assertEquals(CALLS[i][1], call.desc, "the descriptor must not change");
        }
        assertVerifies(rewritten);
    }
}
