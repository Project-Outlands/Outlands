package net.minecraftforge.lwjgl.shim;

import net.minecraftforge.client.render.shim.FrameShim;
import net.minecraftforge.client.render.shim.ShimGl;
import net.minecraftforge.client.render.vulkan.VulkanBackend;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

        assertEquals(24, entries.size(), "the measured surface of the pack: IC2C's eighteen plus JEI's five and Baubles' one");
        for (RetargetEntry entry : entries) {
            assertEquals(FrameShimRules.GL11, entry.fromOwner(), entry.name());
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

    private static String signature(Method method) {
        return method.getName() + org.objectweb.asm.Type.getMethodDescriptor(method);
    }
}
