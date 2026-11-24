package net.minecraftforge.fml.common.discovery.index;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import net.minecraftforge.fml.common.discovery.cache.ClassScanRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The index of an obfuscated archive must be built with the mapping the class loader applies, so that a query uses the
 * name the loader defines; a second start must answer from the cache instead of reading every class again.
 */
class VanillaIndexTest {

    private static final Map<String, String> MAPPING = Map.of(
            "a", "net/minecraft/Alpha",
            "b", "net/minecraft/Itf",
            "c", "net/minecraft/Base");

    private static final UnaryOperator<String> MAPPER = name -> MAPPING.getOrDefault(name, name);

    @Test
    void theMinecraftJarIsRecognisedByItsMarker(@TempDir Path dir) throws Exception {
        final File minecraftJar = jar(dir.resolve("client.jar"), minecraftClasses());
        final File modJar = jar(dir.resolve("mod.jar"),
                Map.of("a", clazz("a", "c", new String[] {"b"})));

        assertTrue(VanillaIndex.looksLikeMinecraftJar(minecraftJar));
        assertFalse(VanillaIndex.looksLikeMinecraftJar(modJar), "a jar with obfuscated looking names is not enough");
    }

    @Test
    void theIndexUsesTheNamesTheLoaderDefines(@TempDir Path dir) throws Exception {
        final File gameDir = Files.createDirectories(dir.resolve("game")).toFile();
        final File mods = Files.createDirectories(gameDir.toPath().resolve("mods")).toFile();
        final File obfuscated = jar(new File(mods, "obf.jar").toPath(), minecraftClasses());

        final LoadIndex index = new LoadIndex(gameDir, List.of(obfuscated));
        assertTrue(index.indexMinecraftJar(MAPPER, "salt"));

        final ClassScanRecord alpha = index.classInfo("net/minecraft/Alpha");
        assertNotNull(alpha, "the mapped name must be the one a query uses");
        assertEquals("net/minecraft/Base", alpha.superName());
        assertEquals(List.of("net/minecraft/Itf"), List.of(alpha.interfaces()));
        assertEquals(List.of("net/minecraft/Base", "net/minecraft/Itf"), index.supertypes("net/minecraft/Alpha"));
        assertNull(index.classInfo("a"), "the obfuscated spelling is not what the loader defines");
        assertNotNull(index.classInfo("pkg/Plain"), "classes the mapper leaves alone are indexed too");
    }

    @Test
    void aSecondStartAnswersFromTheCache(@TempDir Path dir) throws Exception {
        final File gameDir = Files.createDirectories(dir.resolve("game")).toFile();
        final File mods = Files.createDirectories(gameDir.toPath().resolve("mods")).toFile();
        final File obfuscated = jar(new File(mods, "obf.jar").toPath(), minecraftClasses());

        final LoadIndex first = new LoadIndex(gameDir, List.of(obfuscated));
        final AtomicInteger firstCalls = new AtomicInteger();
        assertTrue(first.indexMinecraftJar(counting(firstCalls), "salt"));
        assertTrue(firstCalls.get() > 2, "a cold pass maps every name it reads");

        final LoadIndex second = new LoadIndex(gameDir, List.of(obfuscated));
        final AtomicInteger secondCalls = new AtomicInteger();
        assertTrue(second.indexMinecraftJar(counting(secondCalls), "salt"),
                "the cache must still index the archive");
        assertTrue(secondCalls.get() < 5, "the cache must answer instead of rescanning, calls=" + secondCalls.get());
        assertNotNull(second.classInfo("net/minecraft/Alpha"));
    }

    @Test
    void anotherMappingSaltIsNotReused(@TempDir Path dir) throws Exception {
        final File gameDir = Files.createDirectories(dir.resolve("game")).toFile();
        final File mods = Files.createDirectories(gameDir.toPath().resolve("mods")).toFile();
        final File obfuscated = jar(new File(mods, "obf.jar").toPath(), minecraftClasses());

        final LoadIndex first = new LoadIndex(gameDir, List.of(obfuscated));
        assertTrue(first.indexMinecraftJar(MAPPER, "v1"));

        final LoadIndex second = new LoadIndex(gameDir, List.of(obfuscated));
        final AtomicInteger calls = new AtomicInteger();
        assertTrue(second.indexMinecraftJar(counting(calls), "v2"));
        assertTrue(calls.get() > 2, "an index taken for another mapping must not be used");
    }

    /** The classes of the fake minecraft jar: the marker and the obfuscated ones. */
    private static Map<String, byte[]> minecraftClasses() {
        return Map.of(
                "net/minecraft/client/ClientBrandRetriever", clazz("net/minecraft/client/ClientBrandRetriever", "java/lang/Object", new String[0]),
                "a", clazz("a", "c", new String[] {"b"}),
                "pkg/Plain", clazz("pkg/Plain", "java/lang/Object", new String[0]));
    }

    private static UnaryOperator<String> counting(AtomicInteger calls) {
        return name -> {
            calls.incrementAndGet();
            return MAPPING.getOrDefault(name, name);
        };
    }

    private static File jar(Path file, Map<String, byte[]> classes) throws IOException {
        Files.createDirectories(file.getParent());
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file))) {
            for (Map.Entry<String, byte[]> entry : classes.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey() + ".class"));
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        return file.toFile().getAbsoluteFile();
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
