package com.cleanroommc.lwjgl.legacy;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Generates classes with ASM, rewrites them and asserts the resulting call sites. */
class CompatRewriterTest {

    private static final String SAMPLE = "com/cleanroommc/lwjgl/legacy/test/Sample";

    private static byte[] sample(String methodDesc, Body body) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, SAMPLE, null, "java/lang/Object", null);
        final org.objectweb.asm.MethodVisitor mv = cw.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "call", methodDesc, null, null);
        mv.visitCode();
        body.emit(mv);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private interface Body {
        void emit(org.objectweb.asm.MethodVisitor mv);
    }

    private static MethodNode onlyMethod(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        assertEquals(1, cn.methods.size());
        return cn.methods.get(0);
    }

    private static MethodInsnNode findCall(MethodNode mn, String name) {
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode && ((MethodInsnNode) insn).name.equals(name)) {
                return (MethodInsnNode) insn;
            }
        }
        return null;
    }

    private static int indexOf(MethodNode mn, AbstractInsnNode target) {
        int i = 0;
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext(), i++) {
            if (insn == target) {
                return i;
            }
        }
        return -1;
    }

    /** Catches wrong stack shapes and local slot widths; the rewritten methods are branch-free so no hierarchy is needed. */
    private static void assertStackConsistent(MethodNode mn) {
        try {
            new Analyzer<>(new BasicVerifier()).analyze(SAMPLE, mn);
        } catch (Exception e) {
            fail("rewritten method fails data-flow verification: " + e, e);
        }
    }

    @Test
    void renamesFamilySplitMethods() {
        byte[] original = sample("(I)V", mv -> {
            mv.visitVarInsn(Opcodes.ILOAD, 0);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL11", "glGetFloat",
                    "(ILjava/nio/FloatBuffer;)V", false);
        });

        byte[] rewritten = CompatRewriter.rewrite(original);
        assertNotNull(rewritten, "RENAME should have been applied");

        MethodNode mn = onlyMethod(rewritten);
        MethodInsnNode call = findCall(mn, "glGetFloatv");
        assertNotNull(call, "glGetFloat(...) should become glGetFloatv(...)");
        assertEquals("(ILjava/nio/FloatBuffer;)V", call.desc);
        assertNull(findCall(mn, "glGetFloat"));
        assertStackConsistent(mn);
    }

    @Test
    void insertsInferredTypeConstant() {
        byte[] original = sample("()V", mv -> {
            mv.visitInsn(Opcodes.ICONST_3);
            mv.visitIntInsn(Opcodes.BIPUSH, 12);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL11", "glVertexPointer",
                    "(IILjava/nio/FloatBuffer;)V", false);
        });

        byte[] rewritten = CompatRewriter.rewrite(original);
        assertNotNull(rewritten, "CONVERT(insConst) should have been applied");

        MethodNode mn = onlyMethod(rewritten);
        MethodInsnNode call = findCall(mn, "glVertexPointer");
        assertNotNull(call);
        assertEquals("(IIILjava/nio/FloatBuffer;)V", call.desc);

        FieldInsnNode glFloat = null;
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof FieldInsnNode && ((FieldInsnNode) insn).name.equals("GL_FLOAT")) {
                glFloat = (FieldInsnNode) insn;
            }
        }
        assertNotNull(glFloat);
        assertEquals("org/lwjgl/opengl/GL11", glFloat.owner);
        assertTrue(indexOf(mn, glFloat) < indexOf(mn, call));
        assertStackConsistent(mn);
    }

    @Test
    void convertsBufferToAddress() {
        byte[] original = sample("()V", mv -> {
            mv.visitInsn(Opcodes.ICONST_4);
            mv.visitIntInsn(Opcodes.SIPUSH, 300);
            mv.visitIntInsn(Opcodes.SIPUSH, 5123);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL11", "glDrawElements",
                    "(IIILjava/nio/ByteBuffer;)V", false);
        });

        byte[] rewritten = CompatRewriter.rewrite(original);
        assertNotNull(rewritten, "CONVERT(addr) should have been applied");

        MethodNode mn = onlyMethod(rewritten);
        MethodInsnNode call = findCall(mn, "glDrawElements");
        assertNotNull(call);
        assertEquals("(IIIJ)V", call.desc);

        MethodInsnNode memAddress = findCall(mn, "memAddress");
        assertNotNull(memAddress);
        assertEquals("org/lwjgl/system/MemoryUtil", memAddress.owner);
        assertEquals("(Ljava/nio/Buffer;)J", memAddress.desc);
        assertStackConsistent(mn);
    }

    @Test
    void convertsUnsignedFlagToTypeConstant() {
        byte[] original = sample("()V", mv -> {
            mv.visitInsn(Opcodes.ICONST_4);
            mv.visitInsn(Opcodes.ICONST_1);
            mv.visitInsn(Opcodes.ICONST_0);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL11", "glColorPointer",
                    "(IZILjava/nio/ByteBuffer;)V", false);
        });

        byte[] rewritten = CompatRewriter.rewrite(original);
        assertNotNull(rewritten, "CONVERT(typeFromUnsigned) should have been applied");

        MethodNode mn = onlyMethod(rewritten);
        MethodInsnNode call = findCall(mn, "glColorPointer");
        assertNotNull(call);
        assertEquals("(IIILjava/nio/ByteBuffer;)V", call.desc);

        FieldInsnNode glByte = null;
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof FieldInsnNode && ((FieldInsnNode) insn).name.equals("GL_BYTE")) {
                glByte = (FieldInsnNode) insn;
            }
        }
        assertNotNull(glByte, "GL_BYTE + unsigned must be pushed");
        assertNull(findCall(mn, "memAddress"), "typeFromUnsigned must stay in place");
        assertStackConsistent(mn);
    }

    @Test
    void rewriteIsIdempotentAndSkipsIrrelevantClasses() {
        byte[] original = sample("()V", mv -> {
            mv.visitInsn(Opcodes.ICONST_3);
            mv.visitIntInsn(Opcodes.BIPUSH, 12);
            mv.visitInsn(Opcodes.ACONST_NULL);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/lwjgl/opengl/GL11", "glVertexPointer",
                    "(IILjava/nio/FloatBuffer;)V", false);
        });

        byte[] once = CompatRewriter.rewrite(original);
        assertNotNull(once);
        assertNull(CompatRewriter.rewrite(once), "already rewritten class must be a no-op");

        byte[] clean = sample("()V", mv -> mv.visitInsn(Opcodes.ICONST_0));
        assertNull(CompatRewriter.rewrite(clean));
        assertFalse(ConstantPoolRefs.hasLwjgl2MemberRef(clean));
        assertTrue(ConstantPoolRefs.hasLwjgl2MemberRef(original));
    }
}
