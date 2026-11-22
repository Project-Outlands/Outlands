package org.lwjgl.opengl;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Guards the version skew that killed the first Android launches of the 2026.11.20 build: the shims are compiled
 * against one LWJGL release but run against the launcher's, and a {@code GLCapabilities} field that only exists in
 * the newer one is a {@code NoSuchFieldError} inside {@code Display.create()}, before the main menu.
 * <p>
 * The test scans this build's own bytecode for fields taken from {@link GLCapabilities} and requires every one of
 * them to exist in the oldest release the project supports ({@code lwjgl_minimum_version}, handed over by the
 * {@code :outlands:test} task). A field that is missing there has to be read through
 * {@link ContextCapabilities} instead of being linked to directly.
 */
class CapabilityFieldCompatibilityTest {
    private static final String GL_CAPABILITIES = "org/lwjgl/opengl/GLCapabilities";
    private static final String MINIMUM_RUNTIME_PROPERTY = "outlands.test.lwjglMinimumApi";

    @Test
    void everyCapabilityFieldLinkedByTheShimsExistsInTheOldestSupportedLwjgl() throws IOException {
        Set<String> linked = linkedCapabilityFields();
        // Anti-vacuity: a compile of the shim always links well over 250 capability flags. A lower number means the
        // scan stopped finding them, and a green test would then prove nothing.
        assertTrue(linked.size() > 250, "the scan only found " + linked.size() + " capability fields");

        Set<String> missing = new TreeSet<>(linked);
        missing.removeAll(declaredCapabilityFields());
        assertEquals(Set.of(), missing, "these GLCapabilities fields are missing from the oldest supported LWJGL;"
                + " read them through ContextCapabilities#supports instead of linking to them directly");
    }

    /** The fields {@link ContextCapabilities} links to, straight out of its constant pool. */
    private static Set<String> linkedCapabilityFields() throws IOException {
        ClassNode node = read(ContextCapabilities.class.getResourceAsStream("ContextCapabilities.class"));
        Set<String> names = new LinkedHashSet<>();
        for (MethodNode method : node.methods) {
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof FieldInsnNode field && GL_CAPABILITIES.equals(field.owner)) {
                    names.add(field.name);
                }
            }
        }
        return names;
    }

    private static Set<String> declaredCapabilityFields() throws IOException {
        Path runtime = minimumRuntime();
        try (ZipFile zip = new ZipFile(runtime.toFile())) {
            ZipEntry entry = zip.getEntry(GL_CAPABILITIES + ".class");
            assertTrue(entry != null, runtime + " does not contain " + GL_CAPABILITIES);
            ClassNode node = read(zip.getInputStream(entry));
            Set<String> names = new LinkedHashSet<>();
            node.fields.forEach(field -> names.add(field.name));
            return names;
        }
    }

    private static Path minimumRuntime() {
        String configured = System.getProperty(MINIMUM_RUNTIME_PROPERTY);
        assertTrue(configured != null && !configured.isBlank(), "the test needs '" + MINIMUM_RUNTIME_PROPERTY
                + "', which the test task sets from the 'lwjglMinimumApi' configuration");
        for (String candidate : configured.split(File.pathSeparator)) {
            Path jar = Path.of(candidate);
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                if (zip.getEntry(GL_CAPABILITIES + ".class") != null) return jar;
            } catch (IOException e) {
                // not a readable jar: keep looking
            }
        }
        return fail("none of '" + configured + "' contains " + GL_CAPABILITIES);
    }

    private static ClassNode read(InputStream stream) throws IOException {
        try (InputStream in = stream) {
            ClassNode node = new ClassNode();
            new ClassReader(in).accept(node, 0);
            return node;
        }
    }
}
