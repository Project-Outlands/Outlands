package net.minecraftforge.fml.common.discovery.index;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import net.minecraftforge.fml.common.discovery.asm.ASMModParser;
import net.minecraftforge.fml.common.discovery.cache.ClassScanRecord;
import net.minecraftforge.fml.common.discovery.cache.JarFingerprint;
import net.minecraftforge.fml.common.discovery.cache.JarScanRecord;
import net.minecraftforge.fml.common.discovery.cache.RecordFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The class index must describe the archive it was built from: every class, its header, its package, and the super
 * type chain it can resolve itself.
 */
class ClassIndexTest {

    @Test
    void everyClassIsFoundWithItsHeader(@TempDir Path dir) throws Exception {
        final ClassIndex index = indexOf(scanOf(jar(dir)));

        final ClassScanRecord world = index.byName("pkg/C");
        assertNotNull(world);
        assertEquals("pkg/B", world.superName());
        assertEquals(Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, world.access() & (Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER));
        assertEquals(List.of("pkg/I"), List.of(world.interfaces()));

        assertNull(index.byName("pkg/Missing"));
        assertNull(index.byName("pkg"));
        assertEquals(5, index.size());
    }

    @Test
    void aPackageQueryReturnsOnlyTheClassesDirectlyInIt(@TempDir Path dir) throws Exception {
        final ClassIndex index = indexOf(scanOf(jar(dir)));

        assertEquals(List.of("pkg/A", "pkg/B", "pkg/C", "pkg/I"), sortedNames(index.inPackage("pkg/")));
        assertTrue(!sortedNames(index.inPackage("pkg/")).contains("pkg/nested/D"),
                "a sub package's classes must not be reported for its parent");
        assertEquals(List.of("pkg/nested/D"), sortedNames(index.inPackage("pkg/nested/")));
        assertEquals(List.of(), index.inPackage("nope/"));
    }

    @Test
    void aPrefixQuerySpansPackages(@TempDir Path dir) throws Exception {
        final ClassIndex index = indexOf(scanOf(jar(dir)));

        assertEquals(List.of("pkg/A", "pkg/B", "pkg/C", "pkg/I", "pkg/nested/D"),
                sortedNames(index.withPrefix("pkg/")));
        assertEquals(List.of("pkg/C"), sortedNames(index.withPrefix("pkg/C")));
        assertEquals(List.of(), index.withPrefix("other/"));
    }

    @Test
    void superTypesComeNearestFirstAndStopAtTheArchiveBoundary(@TempDir Path dir) throws Exception {
        final ClassIndex index = indexOf(scanOf(jar(dir)));

        // C extends B implements I, B extends java/lang/Object
        assertEquals(List.of("pkg/B", "pkg/I", "java/lang/Object"), index.supertypes("pkg/C"));
        assertEquals(List.of("java/lang/Object"), index.supertypes("pkg/B"));
        assertEquals(List.of(),
                index.supertypes("pkg/Elsewhere"),
                "a class this archive does not have has no chain here");
        assertTrue(index.isSameOrSubtypeOf("pkg/C", "pkg/I"));
        assertTrue(index.isSameOrSubtypeOf("pkg/C", "pkg/C"));
        assertTrue(index.isSameOrSubtypeOf("pkg/C", "java/lang/Object"));
        assertTrue(!index.isSameOrSubtypeOf("pkg/B", "pkg/C"));
    }

    @Test
    void theCacheKeepsTheHeader(@TempDir Path dir) throws Exception {
        final Path jar = jar(dir);
        final JarScanRecord scan = scanOf(jar);
        final File cacheFile = RecordFile.fileFor(dir.toFile(), jar.toFile());
        assertNotNull(cacheFile, "the jar is below the game directory, so it is cacheable");

        RecordFile.write(cacheFile, scan);
        final JarScanRecord read = RecordFile.read(cacheFile, scan.fingerprint());
        assertNotNull(read, "a record written for this fingerprint must be read back");

        final ClassIndex fromCache = ClassIndex.of(read);
        assertEquals(describe(indexOf(scan)), describe(fromCache),
                "the cached records must describe the same classes");
        assertEquals("pkg/B", fromCache.byName("pkg/C").superName());
        assertEquals(indexOf(scan).byName("pkg/C").access(), fromCache.byName("pkg/C").access());
    }

    /** Records hold arrays, which compare by identity; compare what they describe instead. */
    private static List<String> describe(ClassIndex index) {
        return index.all().stream()
                .map(record -> record.internalName() + "|" + record.classVersion() + "|" + record.superName()
                        + "|" + record.access() + "|" + String.join(",", record.interfaces()))
                .sorted()
                .collect(Collectors.toList());
    }

    private static List<String> sortedNames(List<ClassScanRecord> records) {
        return records.stream().map(ClassScanRecord::internalName).sorted().collect(Collectors.toList());
    }

    private static ClassIndex indexOf(JarScanRecord scan) {
        return ClassIndex.of(scan);
    }

    /** Parses the jar the way discovery does: every scanned entry through {@link ASMModParser}. */
    private static JarScanRecord scanOf(Path jar) throws IOException {
        final List<Integer> indices = new ArrayList<>();
        final List<ClassScanRecord> records = new ArrayList<>();
        final List<java.util.zip.ZipEntry> entries = new ArrayList<>();
        try (java.util.zip.ZipFile file = new java.util.zip.ZipFile(jar.toFile())) {
            int ordinal = -1;
            for (ZipEntry entry : java.util.Collections.list(file.entries())) {
                if (!entry.getName().endsWith(".class")) {
                    continue;
                }
                ordinal++;
                entries.add(entry);
                indices.add(ordinal);
                records.add(new ASMModParser(file.getInputStream(entry)).toRecord());
            }
        }
        return JarScanRecord.of(JarFingerprint.compute(entries), indices, records);
    }

    /** pkg/A (top level), pkg/B extends Object, pkg/C extends B implements I, pkg/nested/D, pkg/I. */
    private static Path jar(Path dir) throws IOException {
        final Path file = dir.resolve("classes.jar");
        final Map<String, byte[]> classes = Map.of(
                "pkg/A", clazz("pkg/A", null, new String[0], Opcodes.ACC_PUBLIC),
                "pkg/B", clazz("pkg/B", "java/lang/Object", new String[0], Opcodes.ACC_PUBLIC),
                "pkg/C", clazz("pkg/C", "pkg/B", new String[] {"pkg/I"}, Opcodes.ACC_PUBLIC),
                "pkg/I", clazz("pkg/I", "java/lang/Object", new String[0], Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT),
                "pkg/nested/D", clazz("pkg/nested/D", "java/lang/Object", new String[0], Opcodes.ACC_PUBLIC));
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file))) {
            for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey() + ".class"));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        return file;
    }

    private static byte[] clazz(String name, String superName, String[] interfaces, int access) {
        final ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, access | Opcodes.ACC_SUPER, name, null, superName, interfaces);
        if ((access & Opcodes.ACC_INTERFACE) == 0) {
            final MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
            constructor.visitCode();
            constructor.visitVarInsn(Opcodes.ALOAD, 0);
            constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
            constructor.visitInsn(Opcodes.RETURN);
            constructor.visitMaxs(1, 1);
            constructor.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }
}
