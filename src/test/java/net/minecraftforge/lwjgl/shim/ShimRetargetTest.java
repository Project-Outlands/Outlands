package net.minecraftforge.lwjgl.shim;

import net.minecraftforge.lwjgl.legacy.CompatRewriter;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
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
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the shim retarget pass: that an owner-only rewrite keeps the class verifiable, that the frozen
 * selection is honoured, and that exemptions keep a provider's own classes untouched.
 */
class ShimRetargetTest {

    private static final String SAMPLE = "test/Sample";
    private static final String GL11 = "org/lwjgl/opengl/GL11";
    private static final String FACADE = "top/outlands/render/shim/ShimGl";

    /** A class whose single method calls {@code GL11.glEnable(I)V}. */
    private static byte[] caller(String internalName, String owner) {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, internalName, null, "java/lang/Object", null);
        var mv = cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "call", "(I)V", null, null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ILOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, owner, "glEnable", "(I)V", false);
        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static MethodInsnNode onlyCall(byte[] bytes) {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        for (MethodNode mn : cn.methods) {
            for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof MethodInsnNode call) {
                    return call;
                }
            }
        }
        return null;
    }

    private static void assertVerifies(byte[] bytes) throws Exception {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        for (MethodNode mn : cn.methods) {
            Analyzer<BasicValue> analyzer = new Analyzer<>(new BasicVerifier());
            analyzer.analyze(cn.name, mn);
        }
        StringWriter diagnostics = new StringWriter();
        CheckClassAdapter.verify(new ClassReader(bytes), false, new PrintWriter(diagnostics));
        assertTrue(diagnostics.toString().isEmpty(), diagnostics::toString);
    }

    private static ShimRegistry.Selection selectionFor(String from, String to, Set<String> exempt) {
        ShimRegistry.resetForTests();
        ShimRegistry.register(new ShimRewriteRules() {
            @Override
            public String id() {
                return "test";
            }

            @Override
            public boolean appliesTo(ShimEnvironment env) {
                return true;
            }

            @Override
            public List<RetargetEntry> entries() {
                return List.of(new RetargetEntry(from, "glEnable", "(I)V", to));
            }

            @Override
            public Set<String> exemptPrefixes() {
                return exempt;
            }
        });
        return ShimRegistry.select(() -> "vulkan");
    }

    @Test
    void ownerOnlyRetargetKeepsClassVerifiable() throws Exception {
        ShimRegistry.Selection selection = selectionFor(GL11, FACADE, Set.of());

        byte[] rewritten = CompatRewriter.rewrite(caller(SAMPLE, GL11), selection);

        assertNotNull(rewritten, "the call site should have been rewritten");
        MethodInsnNode call = onlyCall(rewritten);
        assertNotNull(call);
        assertEquals(FACADE, call.owner);
        assertEquals("glEnable", call.name, "name must not change");
        assertEquals("(I)V", call.desc, "descriptor must not change");
        assertVerifies(rewritten);
    }

    @Test
    void withoutSelectionNothingChanges() {
        ShimRegistry.resetForTests();
        byte[] original = caller(SAMPLE, GL11);
        assertNull(CompatRewriter.rewrite(original, null),
                "no selection must leave the class untouched");
    }

    @Test
    void exemptPrefixIsLeftAlone() {
        ShimRegistry.Selection selection = selectionFor(GL11, FACADE, Set.of("com/example/render/"));

        assertTrue(selection.isExempt("com/example/render/MyRenderer"));
        assertTrue(selection.isExempt("com/example/render/sub/Inner"));
        assertTrue(!selection.isExempt("com/example/other/Thing"));
    }

    @Test
    void retargetOnlyMatchesExactDescriptor() {
        ShimRegistry.Selection selection = selectionFor(GL11, FACADE, Set.of());

        assertEquals(FACADE, selection.retarget(GL11, "glEnable", "(I)V"));
        assertNull(selection.retarget(GL11, "glEnable", "(J)V"), "a different descriptor is a different member");
        assertNull(selection.retarget(GL11, "glDisable", "(I)V"), "a different name is a different member");
    }

    @Test
    void selectionIsFrozenAfterFirstResolve() {
        ShimRegistry.Selection first = selectionFor(GL11, FACADE, Set.of());
        ShimRegistry.Selection second = ShimRegistry.select(() -> "vanilla-gl");

        assertEquals(first.rulesetId(), second.rulesetId(), "the first resolution must win");
        assertTrue(second == first);
    }

    @Test
    void lateRegistrationIsIgnored() {
        selectionFor(GL11, FACADE, Set.of());
        ShimRegistry.register(new ShimRewriteRules() {
            @Override
            public String id() {
                return "late";
            }

            @Override
            public boolean appliesTo(ShimEnvironment env) {
                return true;
            }

            @Override
            public List<RetargetEntry> entries() {
                return List.of();
            }

            @Override
            public Set<String> exemptPrefixes() {
                return Set.of();
            }
        });

        assertEquals("test", ShimRegistry.selection().rulesetId(),
                "a ruleset registered after the freeze must not replace the selection");
    }

    @Test
    void registeredExemptionJoinsTheSelection() {
        ShimRegistry.resetForTests();
        ShimRegistry.exempt("com/example/render/");
        ShimRegistry.register(new ShimRewriteRules() {
            @Override
            public String id() {
                return "test";
            }

            @Override
            public boolean appliesTo(ShimEnvironment env) {
                return true;
            }

            @Override
            public List<RetargetEntry> entries() {
                return List.of(new RetargetEntry(GL11, "glEnable", "(I)V", FACADE));
            }

            @Override
            public Set<String> exemptPrefixes() {
                return Set.of("com/example/other/");
            }
        });
        ShimRegistry.Selection selection = ShimRegistry.select(() -> "vulkan");

        assertTrue(selection.isExempt("com/example/render/MyRenderer"),
                "a renderer's own declaration must be honoured even though the ruleset never listed it");
        assertTrue(selection.isExempt("com/example/other/Thing"), "the ruleset's own list still applies");
        assertFalse(selection.isExempt("com/example/third/Thing"));
    }

    @Test
    void exemptAfterFreezeIsRejected() {
        selectionFor(GL11, FACADE, Set.of());
        assertThrows(IllegalStateException.class, () -> ShimRegistry.exempt("com/example/render/"),
                "exempting after the freeze would be silently ineffective, so it must fail loudly");
    }

    @Test
    void exemptRejectsDottedPrefix() {
        ShimRegistry.resetForTests();
        assertThrows(IllegalArgumentException.class, () -> ShimRegistry.exempt("com.example.render"));
    }

    @Test
    void retargetEntryRejectsSameOwner() {
        assertThrows(IllegalArgumentException.class,
                () -> new RetargetEntry(GL11, "glEnable", "(I)V", GL11));
    }

    @Test
    void retargetEntryRejectsDottedNames() {
        assertThrows(IllegalArgumentException.class,
                () -> new RetargetEntry("org.lwjgl.opengl.GL11", "glEnable", "(I)V", FACADE));
    }
}
