package net.minecraftforge.lwjgl.shim;

import net.minecraftforge.lwjgl.legacy.CompatRewriter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.BasicVerifier;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the real IC2Classic jar through the rewrite pass, without starting the game.
 *
 * <p><b>Why opt-in.</b> The jar lives in a mod installation, not in this repository, so the test asks for it:
 * {@code -Dic2c.jar=/path/to/IC2Classic-1.12-1.5.8.jar}. It is the only check that answers "how many of this
 * mod's call sites actually get routed" with a number instead of a screenshot, and it is cheap enough to run on
 * every change to the ruleset.</p>
 *
 * <p><b>What it does not do.</b> It does not load the classes or call the facade - that needs the game's
 * classloader and a frame. It rewrites bytes, verifies them, and counts.</p>
 */
class Ic2cJarTest {

    private static final Path JAR = Path.of(System.getProperty("ic2c.jar", ""));

    private static ShimRegistry.Selection productionEntries() {
        ShimRegistry.resetForTests();
        ShimRegistry.register(new ShimRewriteRules() {
            @Override
            public String id() {
                return "jar-test";
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
            public Set<String> exemptPrefixes() {
                return FrameShimRules.INSTANCE.exemptPrefixes();
            }
        });
        return ShimRegistry.select(() -> FrameShimRules.VULKAN_ID);
    }

    private static Set<String> entrySignatures() {
        Set<String> signatures = new HashSet<>();
        for (RetargetEntry entry : FrameShimRules.INSTANCE.entries()) {
            signatures.add(entry.name() + entry.desc());
        }
        return signatures;
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

    @Test
    @EnabledIfSystemProperty(named = "ic2c.jar", matches = ".+")
    void theModsFixedFunctionSurfaceIsRouted() throws Exception {
        assertTrue(Files.isRegularFile(JAR), "no jar at " + JAR);
        ShimRegistry.Selection selection = productionEntries();
        Set<String> allowed = entrySignatures();

        int classes = 0;
        int rewrittenClasses = 0;
        int retargeted = 0;
        TreeMap<String, Integer> byMethod = new TreeMap<>();
        List<String> verifyFailures = new ArrayList<>();

        try (ZipFile zip = new ZipFile(JAR.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().endsWith(".class")) {
                    continue;
                }
                classes++;
                byte[] bytes;
                try (InputStream in = zip.getInputStream(entry)) {
                    bytes = in.readAllBytes();
                }
                byte[] rewritten;
                try {
                    rewritten = CompatRewriter.rewrite(bytes, selection);
                } catch (RuntimeException e) {
                    throw new AssertionError("rewriting " + entry.getName() + " failed", e);
                }
                if (rewritten == null) {
                    continue;
                }
                rewrittenClasses++;
                for (MethodInsnNode call : calls(rewritten)) {
                    if (!FrameShimRules.FACADE.equals(call.owner)) {
                        continue;
                    }
                    retargeted++;
                    byMethod.merge(call.name + call.desc, 1, Integer::sum);
                    assertTrue(allowed.contains(call.name + call.desc),
                            () -> "retargeted a call the ruleset does not list: " + call.name + call.desc
                                    + " in " + entry.getName());
                }
                try {
                    verify(rewritten);
                } catch (RuntimeException | org.objectweb.asm.tree.analysis.AnalyzerException e) {
                    verifyFailures.add(entry.getName() + ": " + e.getMessage());
                }
            }
        }

        assertTrue(classes > 0, "the jar should contain classes");
        assertTrue(verifyFailures.isEmpty(), () -> "rewritten classes failed verification:\n"
                + String.join("\n", verifyFailures));
        System.out.println("IC2C: " + retargeted + " call sites routed from " + rewrittenClasses + " classes, by "
                + "method: " + byMethod);
        // The measured surface is 63 (class, member) pairs across 15 classes; occurrences are higher because a
        // renderer calls the same entry point several times. These floors catch a ruleset that stops matching.
        assertTrue(rewrittenClasses >= 10, "expected at least 10 rewritten classes, got " + rewrittenClasses);
        assertTrue(retargeted >= 40, "expected at least 40 retargeted call sites, got " + retargeted
                + " in " + rewrittenClasses + " classes; by method: " + byMethod);
    }

    private static void verify(byte[] bytes) throws org.objectweb.asm.tree.analysis.AnalyzerException {
        ClassNode cn = new ClassNode();
        new ClassReader(bytes).accept(cn, 0);
        for (MethodNode mn : cn.methods) {
            if (mn.instructions != null && mn.instructions.size() > 0) {
                new Analyzer<BasicValue>(new BasicVerifier()).analyze(cn.name, mn);
            }
        }
    }
}
