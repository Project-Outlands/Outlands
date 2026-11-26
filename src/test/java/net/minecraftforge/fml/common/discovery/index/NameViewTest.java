package net.minecraftforge.fml.common.discovery.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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

import net.minecraftforge.fml.common.discovery.asm.ASMModParser;
import net.minecraftforge.fml.common.discovery.cache.ClassScanRecord;
import net.minecraftforge.fml.common.discovery.cache.JarFingerprint;
import net.minecraftforge.fml.common.discovery.cache.JarScanRecord;
import net.minecraftforge.fml.common.discovery.cache.MemberFile;
import net.minecraftforge.fml.common.discovery.cache.MemberRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A name query is answered by the owner and the descriptor, not by the name alone: the same MCP name covers several
 * SRG names, and the same SRG name is declared by several owners.
 */
class NameViewTest {

    /** func_1 and func_2 are both called {@code size} by MCP, with different descriptors. */
    private static final String TSRG = """
            name/Mine name/Mine
            \tfunc_1 ()I size
            \tfunc_2 (Ljava/lang/String;)I size
            \tfunc_3 ()V doThing
            """;

    @Test
    void srgToMcpAnswersOnlyForAMemberTheOwnerDeclares(@TempDir Path dir) throws Exception {
        final LoadIndex index = indexOf(dir);

        assertEquals("doThing", index.srgToMcp("name/Mine", "func_3", "()V").orElse(null));
        assertTrue(index.srgToMcp("name/Mine", "func_3", "()I").isEmpty(), "the descriptor has to match");
        assertTrue(index.srgToMcp("name/Other", "func_3", "()V").isEmpty(), "the owner has to declare it");
        assertTrue(index.srgToMcp("name/Mine", "func_untold", "()V").isEmpty(), "a member the class does not have");
    }

    @Test
    void mcpToSrgReturnsEveryCandidateAndNarrowsByDescriptor(@TempDir Path dir) throws Exception {
        final LoadIndex index = indexOf(dir);

        final List<MemberRef> size = index.mcpToSrg("name/Mine", "size", null);
        assertEquals(2, size.size(), "both overloads are called size by MCP");
        assertTrue(size.stream().anyMatch(ref -> ref.name().equals("func_1") && ref.descriptor().equals("()I")));
        assertTrue(size.stream().anyMatch(ref -> ref.name().equals("func_2")));

        final List<MemberRef> narrowed = index.mcpToSrg("name/Mine", "size", "(Ljava/lang/String;)I");
        assertEquals(1, narrowed.size());
        assertEquals("func_2", narrowed.get(0).name());

        assertTrue(index.mcpToSrg("name/Mine", "noSuchName", null).isEmpty());
    }

    @Test
    void aMemberMcpLeavesAloneAnswersToItsOwnName(@TempDir Path dir) throws Exception {
        final LoadIndex index = indexOf(dir);

        final List<MemberRef> constructor = index.mcpToSrg("name/Mine", "<init>", "()V");
        assertEquals(1, constructor.size(), "the constructor is not renamed, so the name it has is the name to ask for");
        assertEquals("<init>", constructor.get(0).name());
    }

    @Test
    void withoutATableNothingIsRenamedAndNothingBreaks(@TempDir Path dir) throws Exception {
        final LoadIndex index = indexOf(dir);
        final Mappings absent = Mappings.fresh();
        absent.start(null);
        index.useMappings(absent);

        assertTrue(index.srgToMcp("name/Mine", "func_3", "()V").isEmpty(), "no table means no MCP name");
        assertTrue(index.mcpToSrg("name/Mine", "doThing", null).isEmpty(),
                "func_3 is not called doThing without a table, so the query answers nothing");
        assertEquals("func_3", index.mcpToSrg("name/Mine", "func_3", null).get(0).name(),
                "and the SRG name still answers to itself");
    }

    private static LoadIndex indexOf(Path dir) throws IOException {
        final File gameDir = Files.createDirectories(dir.resolve("game")).toFile();
        final File mods = Files.createDirectories(gameDir.toPath().resolve("mods")).toFile();
        final File jar = jar(mods, "mine.jar", Map.of("name/Mine",
                clazz("name/Mine", new String[] {"func_1", "func_2", "func_3"})));

        final LoadIndex index = new LoadIndex(gameDir, List.of(jar));
        final JarScanRecord scan = scanOf(jar);
        index.register(jar, scan);
        MemberFile.write(MemberFile.fileFor(gameDir, jar), scan.fingerprint(),
                Map.of("name/Mine", new MemberRecord[] {
                        new MemberRecord("func_1", "()I", Opcodes.ACC_PUBLIC, true),
                        new MemberRecord("func_2", "(Ljava/lang/String;)I", Opcodes.ACC_PUBLIC, true),
                        new MemberRecord("func_3", "()V", Opcodes.ACC_PUBLIC, true),
                        new MemberRecord("<init>", "()V", Opcodes.ACC_PUBLIC, true),
                }));

        final Path mappings = Files.createDirectories(gameDir.toPath().resolve(Mappings.CONFIG_DIR));
        Files.writeString(mappings.resolve("srg2mcp.tsrg"), TSRG, StandardCharsets.UTF_8);
        final Mappings table = Mappings.fresh();
        table.start(gameDir);
        table.awaitReady(5_000);
        index.useMappings(table);
        return index;
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

    private static byte[] clazz(String name, String[] methods) {
        final ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object", null);
        for (String method : methods) {
            final String desc = method.equals("func_2") ? "(Ljava/lang/String;)I" : method.equals("func_1") ? "()I" : "()V";
            final MethodVisitor visitor = writer.visitMethod(Opcodes.ACC_PUBLIC, method, desc, null, null);
            visitor.visitCode();
            if (!desc.endsWith(")V")) {
                visitor.visitInsn(Opcodes.ICONST_0);
                visitor.visitInsn(Opcodes.IRETURN);
                visitor.visitMaxs(1, method.equals("func_2") ? 2 : 1);
            } else {
                visitor.visitInsn(Opcodes.RETURN);
                visitor.visitMaxs(0, 1);
            }
            visitor.visitEnd();
        }
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
