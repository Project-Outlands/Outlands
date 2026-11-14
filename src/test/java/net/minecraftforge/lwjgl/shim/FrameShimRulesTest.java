package net.minecraftforge.lwjgl.shim;

import net.minecraftforge.client.render.shim.FrameShim;
import net.minecraftforge.client.render.shim.ShimGl;
import net.minecraftforge.client.render.vulkan.VulkanBackend;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the pairing between the ruleset's entries, the facade's methods and the implementation.
 *
 * <p><b>Why this test carries more weight than its size suggests.</b> A retarget changes the owner of a call and
 * nothing else, so the compiler never sees the pair: if an entry names a method the facade does not declare, the
 * class still loads and the failure appears as a {@code NoSuchMethodError} on the first execution - inside a
 * render loop, with no stack frame pointing at the ruleset. Checking the bijection here turns that into a failing
 * test at build time.</p>
 */
class FrameShimRulesTest {

    /** Facade methods that are API for the backend, not GL entry points. */
    private static final Set<String> NOT_GL = Set.of("setImpl", "impl", "isRealGl");

    @Test
    void entriesAreTheMeasuredSurface() {
        List<RetargetEntry> entries = FrameShimRules.INSTANCE.entries();

        assertEquals(93, entries.size(), "the measured surface: the pack, Immersive Engineering's queries and"
                + " raster/stencil entries, one boolean query, the ARB shader block, the state setters the whole"
                + " instance was scanned for, the display-list calls, the client arrays with their buffers, and the"
                + " attribute stack, stencil clear and readback family");
        Set<String> owners = Set.of(FrameShimRules.GL11, FrameShimRules.ARB_SHADER_OBJECTS,
                FrameShimRules.ARB_BUFFER_OBJECT);
        for (RetargetEntry entry : entries) {
            assertTrue(owners.contains(entry.fromOwner()), entry.name() + " from " + entry.fromOwner());
            assertEquals(FrameShimRules.FACADE, entry.toOwner(), entry.name());
        }
    }

    @Test
    void everyEntryHasAFacadeMethodAndEveryFacadeMethodIsAnEntry() throws Exception {
        Set<String> entries = FrameShimRules.INSTANCE.entries().stream()
                .map(entry -> entry.name() + entry.desc())
                .collect(Collectors.toCollection(TreeSet::new));

        Set<String> declared = Arrays.stream(ShimGl.class.getDeclaredMethods())
                .filter(method -> Modifier.isStatic(method.getModifiers()))
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> !NOT_GL.contains(method.getName()))
                .map(FrameShimRulesTest::signature)
                .collect(Collectors.toCollection(TreeSet::new));

        assertEquals(entries, declared, "the ruleset and the facade must describe the same call sites");
    }

    @Test
    void theFacadeIsFullyImplemented() throws Exception {
        Set<String> contract = Arrays.stream(ShimGl.Impl.class.getDeclaredMethods())
                .map(FrameShimRulesTest::signature)
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> implemented = Arrays.stream(FrameShim.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(FrameShimRulesTest::signature)
                .collect(Collectors.toCollection(TreeSet::new));

        assertTrue(implemented.containsAll(contract), () -> "not implemented: " + contract.stream()
                .filter(signature -> !implemented.contains(signature)).toList());
        // Anything extra has to be diagnostics, not a GL entry point nobody routes to.
        Set<String> extras = new TreeSet<>(implemented);
        extras.removeAll(contract);
        assertEquals(Set.of("routedCalls()J"), extras);
    }

    @Test
    void appliesOnlyToTheVulkanBackend() {
        assertTrue(FrameShimRules.INSTANCE.appliesTo(() -> FrameShimRules.VULKAN_ID));
        assertFalse(FrameShimRules.INSTANCE.appliesTo(() -> "vanilla-gl"));
        assertFalse(FrameShimRules.INSTANCE.appliesTo(() -> ""));
    }

    @Test
    void theBackendIdConstantAgreesWithTheBackend() {
        // Duplicated on purpose: the shim layer must not import the renderer package to read one string. This is
        // the only place the two are compared.
        assertEquals(VulkanBackend.ID, FrameShimRules.VULKAN_ID);
    }

    @Test
    void theFacadePackageIsExempt() {
        // Not a style check: the facade's real-GL implementation calls GL11 from inside this package, so without
        // the exemption the rewrite would send those calls back into the facade.
        assertTrue(FrameShimRules.INSTANCE.exemptPrefixes().contains("net/minecraftforge/client/render/shim/"));
    }

    @Test
    void theArbEntriesRewriteTheirCallSites() throws Exception {
        // The entries existing is not the same as the rewrite reaching them: a ruleset is keyed by owner, and the
        // ARB block is the first thing in this project that does not come from GL11. This drives the production
        // ruleset over a class that calls the extension and checks the owner moved.
        ShimRegistry.resetForTests();
        FrameShimRules.registerBuiltin();
        ShimRegistry.Selection selection = ShimRegistry.select(() -> FrameShimRules.VULKAN_ID);

        byte[] caller = arbCaller("test/ArbCaller", "glCreateProgramObjectARB");
        byte[] rewritten = net.minecraftforge.lwjgl.legacy.CompatRewriter.rewrite(caller, selection);

        assertNotNull(rewritten, "an ARB shader call site must be rewritten too");
        ClassNode node = new ClassNode();
        new ClassReader(rewritten).accept(node, 0);
        MethodInsnNode call = node.methods.stream()
                .flatMap(method -> java.util.stream.Stream.of(method.instructions.toArray()))
                .filter(instruction -> instruction instanceof MethodInsnNode)
                .map(MethodInsnNode.class::cast)
                .findFirst()
                .orElseThrow();
        assertEquals(FrameShimRules.FACADE, call.owner);
        assertEquals("glCreateProgramObjectARB", call.name, "name must not change");
        assertEquals("()I", call.desc, "descriptor must not change");
    }

    /** A class whose single method calls {@code ARBShaderObjects.<name>()I}. */
    private static byte[] arbCaller(String internalName, String name) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, internalName, null, "java/lang/Object", null);
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "call", "()I", null, null);
        method.visitCode();
        method.visitMethodInsn(Opcodes.INVOKESTATIC, FrameShimRules.ARB_SHADER_OBJECTS, name, "()I", false);
        method.visitInsn(Opcodes.IRETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static String signature(Method method) {
        return method.getName() + org.objectweb.asm.Type.getMethodDescriptor(method);
    }
}
