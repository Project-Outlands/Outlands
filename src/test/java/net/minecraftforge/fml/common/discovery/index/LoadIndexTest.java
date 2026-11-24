package net.minecraftforge.fml.common.discovery.index;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
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
 * The load index must answer like the class path does: the first archive that has a class wins, a super type chain is
 * followed into the other archives, and an archive this run never scanned is answered from its cached record.
 */
class LoadIndexTest {

    @Test
    void theFirstArchiveInClassPathOrderWins(@TempDir Path dir) throws Exception {
        final File gameDir = Files.createDirectories(dir.resolve("game")).toFile();
        final File mods = Files.createDirectories(gameDir.toPath().resolve("mods")).toFile();
        final File first = jar(mods, "first.jar", Map.of("dup/Target", clazz("dup/Target", "java/lang/Object", new String[0])));
        final File second = jar(mods, "second.jar", Map.of("dup/Target", clazz("dup/Target", "java/lang/Object", new String[] {"dup/Marker"})));

        final LoadIndex index = new LoadIndex(gameDir, List.of(first, second));
        index.register(first, scanOf(first));
        index.register(second, scanOf(second));

        final ClassScanRecord target = index.classInfo("dup/Target");
        assertNotNull(target);
        assertEquals(0, target.interfaces().length, "the first archive's version is the one the class loader defines");
        assertNull(index.classInfo("dup/Missing"));
    }

    @Test
    void superTypesAreFollowedIntoOtherArchives(@TempDir Path dir) throws Exception {
        final File gameDir = Files.createDirectories(dir.resolve("game")).toFile();
        final File mods = Files.createDirectories(gameDir.toPath().resolve("mods")).toFile();
        // app/Child extends lib/Base implements lib/Itf, and those two live in another jar
        final File app = jar(mods, "app.jar",
                Map.of("app/Child", clazz("app/Child", "lib/Base", new String[] {"lib/Itf"})));
        final File lib = jar(mods, "lib.jar", Map.of(
                "lib/Base", clazz("lib/Base", "java/lang/Object", new String[0]),
                "lib/Itf", clazz("lib/Itf", "java/lang/Object", new String[0])));

        final LoadIndex index = new LoadIndex(gameDir, List.of(app, lib));
        index.register(app, scanOf(app));
        index.register(lib, scanOf(lib));

        assertEquals(List.of("lib/Base", "lib/Itf", "java/lang/Object"), index.supertypes("app/Child"));
        assertTrue(index.isSameOrSubtypeOf("app/Child", "lib/Itf"));
        assertTrue(!index.isSameOrSubtypeOf("lib/Base", "app/Child"));
        assertEquals(List.of(), index.supertypes("nope/Nothing"));
    }

    @Test
    void anArchiveThisRunNeverScannedIsAnsweredFromItsCachedRecord(@TempDir Path dir) throws Exception {
        final File gameDir = Files.createDirectories(dir.resolve("game")).toFile();
        final File mods = Files.createDirectories(gameDir.toPath().resolve("mods")).toFile();
        final File jar = jar(mods, "cached.jar",
                Map.of("cached/Thing", clazz("cached/Thing", "java/lang/Object", new String[] {"cached/Marker"})));

        // write the record the way discovery would have, without registering a scan in this run
        final JarScanRecord scan = scanOf(jar);
        final File cacheFile = RecordFile.fileFor(gameDir, jar);
        assertNotNull(cacheFile);
        RecordFile.write(cacheFile, scan);

        final LoadIndex index = new LoadIndex(gameDir, List.of(jar));
        final ClassScanRecord thing = index.classInfo("cached/Thing");
        assertNotNull(thing, "the cache must answer for a jar this run did not scan");
        assertEquals(List.of("cached/Marker"), List.of(thing.interfaces()));
        assertEquals(List.of("java/lang/Object", "cached/Marker"), index.supertypes("cached/Thing"),
                "the super type comes before the interfaces, as in the scan");
    }

    @Test
    void aJarWithoutARecordAnswersNothing(@TempDir Path dir) throws Exception {
        final File gameDir = Files.createDirectories(dir.resolve("game")).toFile();
        final File mods = Files.createDirectories(gameDir.toPath().resolve("mods")).toFile();
        final File jar = jar(mods, "noscan.jar", Map.of("x/Y", clazz("x/Y", "java/lang/Object", new String[0])));

        final LoadIndex index = new LoadIndex(gameDir, List.of(jar));
        assertNull(index.classInfo("x/Y"));
        assertNull(index.indexOf(jar));
        assertTrue(index.classesInPackage("x/").isEmpty());
    }

    @Test
    void aPackageQuerySpansArchivesAndKeepsTheFirstOfEachName(@TempDir Path dir) throws Exception {
        final File gameDir = Files.createDirectories(dir.resolve("game")).toFile();
        final File mods = Files.createDirectories(gameDir.toPath().resolve("mods")).toFile();
        final File first = jar(mods, "p1.jar", Map.of("pkg/A", clazz("pkg/A", "java/lang/Object", new String[0])));
        final File second = jar(mods, "p2.jar", Map.of(
                "pkg/A", clazz("pkg/A", "java/lang/Object", new String[] {"pkg/Other"}),
                "pkg/B", clazz("pkg/B", "java/lang/Object", new String[0])));

        final LoadIndex index = new LoadIndex(gameDir, List.of(first, second));
        index.register(first, scanOf(first));
        index.register(second, scanOf(second));

        final List<ClassScanRecord> found = index.classesInPackage("pkg/");
        assertEquals(List.of("pkg/A", "pkg/B"), found.stream().map(ClassScanRecord::internalName).toList());
        assertEquals(0, found.get(0).interfaces().length, "the first archive's version of a name is the one reported");
    }

    private static JarScanRecord scanOf(File jar) throws IOException {
        final List<ZipEntry> entries = new ArrayList<>();
        final List<Integer> indices = new ArrayList<>();
        final List<ClassScanRecord> records = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar)) {
            int ordinal = -1;
            for (ZipEntry entry : Collections.list(zip.entries())) {
                entries.add(entry);
                if (!entry.getName().endsWith(".class")) {
                    continue;
                }
                ordinal++;
                indices.add(ordinal);
                records.add(new ASMModParser(zip.getInputStream(entry)).toRecord());
            }
        }
        return JarScanRecord.of(JarFingerprint.compute(entries), indices, records);
    }

    private static File jar(File dir, String name, Map<String, byte[]> classes) throws IOException {
        final File file = new File(dir, name);
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file.toPath()))) {
            for (Map.Entry<String, byte[]> entry : new LinkedHashMap<>(classes).entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey() + ".class"));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        return file.getAbsoluteFile();
    }

    private static byte[] clazz(String name, String superName, String[] interfaces) {
        final ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, superName, interfaces);
        final MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
