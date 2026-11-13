package org.lwjgl.opengl;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextCapabilitiesTest {

    @Test
    void explicitNamesEnableOnlyTheRequestedLegacyFlags() {
        ContextCapabilities capabilities = new ContextCapabilities(Set.of("OpenGL33", "GL_ARB_sync", "unknown_extension"));
        assertTrue(capabilities.OpenGL33);
        assertTrue(capabilities.GL_ARB_sync);
        assertFalse(capabilities.OpenGL46);
        assertFalse(capabilities.GL_ARB_compute_shader);

        ContextCapabilities empty = new ContextCapabilities(Set.of());
        assertFalse(empty.OpenGL33);
        assertFalse(empty.GL_ARB_sync);
    }

    /**
     * Guards the fix for a crash that had nothing to do with rendering.
     *
     * <p>Measured: {@code Display.create} → {@code GLContext.refreshCapabilities} → {@code new ContextCapabilities()}
     * threw {@code NoSuchFieldError: GLCapabilities does not have member field 'boolean
     * GL_APPLE_aux_depth_stencil'} on an Android launcher, because its LWJGL build declares fewer extension fields
     * than the one this module compiles against. A compiled {@code cap.GL_APPLE_aux_depth_stencil} is a link-time
     * dependency on that exact field and cannot be satisfied; reading the flags reflectively into a name set can,
     * and a flag the build does not declare then simply means "off".</p>
     *
     * <p>Stated as a bytecode property rather than by inspection, because the failure is invisible at compile time
     * and only appears on a build with a different field set - the one thing a developer's machine is unlikely to
     * be.</p>
     */
    @Test
    void noFlagIsACompileTimeFieldReadOfGLCapabilities() throws Exception {
        ClassNode node = new ClassNode();
        try (InputStream in = ContextCapabilities.class.getResourceAsStream("ContextCapabilities.class")) {
            assertNotNull(in, "the class file of the class under test must be on the test classpath");
            new ClassReader(in).accept(node, 0);
        }

        List<String> direct = new ArrayList<>();
        boolean readsFieldsReflectively = false;
        for (var method : node.methods) {
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof FieldInsnNode field && field.owner.equals("org/lwjgl/opengl/GLCapabilities")) {
                    direct.add(method.name + " -> " + field.name);
                }
                if (insn instanceof MethodInsnNode call && call.owner.equals("java/lang/Class")
                        && call.name.equals("getFields")) {
                    readsFieldsReflectively = true;
                }
            }
        }

        assertEquals(List.of(), direct, "a field read of GLCapabilities is a link-time dependency on the LWJGL "
                + "build this module compiled against; read the flags into a name set instead");
        assertTrue(readsFieldsReflectively, "the flags are expected to come from GLCapabilities.class.getFields()");
    }
}
