package net.minecraftforge.fml.common.discovery.cache;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.Opcodes;

import net.minecraftforge.fml.common.asm.transformers.AtRule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The detail file is a third cache file beside the headers and the member table: a record of another jar, of another
 * revision or a damaged one must not be used, and its absence is not an error.
 */
class DetailFileTest {

    private static final JarFingerprint FINGERPRINT = new JarFingerprint(0x0FED_CBA9_8765_4321L, 77);

    private static DetailRecord sample() {
        final Map<String, ClassDetail> classes = new LinkedHashMap<>();
        classes.put("pkg/Outer$Inner", new ClassDetail(
                "Ljava/lang/Object;Lpkg/Base<Lnet/minecraft/item/ItemStack;>;",
                new ClassDetail.Inner("pkg/Outer", "Inner", Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC),
                new ClassDetail.Enclosing("pkg/Outer", "make", "()V"),
                "Outer.java",
                Map.of(MemberRecord.key("items", "Ljava/util/List;", false),
                        new MemberDetail("Ljava/util/List<Ljava/lang/String;>;", null, null),
                        MemberRecord.key("run", "()V", true),
                        new MemberDetail(null, new String[] {"java/io/IOException"}, null),
                        MemberRecord.key("MAX", "I", false),
                        new MemberDetail(null, null, "7"))));
        classes.put("pkg/Anonymous", new ClassDetail(null,
                new ClassDetail.Inner(null, null, 0), new ClassDetail.Enclosing("pkg/Outer", null, null),
                null, Map.of()));
        return new DetailRecord(FINGERPRINT, classes,
                new AtRule[] {new AtRule("net/minecraft/world/World", "", "", Opcodes.ACC_PUBLIC, true, false, false)});
    }

    @Test
    void theDetailRoundTripsThroughDisk(@TempDir Path dir) {
        final File file = dir.resolve("classes.jar.bin.detail").toFile();
        DetailFile.write(file, sample());
        assertTrue(file.isFile());

        final DetailRecord read = DetailFile.read(file, FINGERPRINT);
        assertNotNull(read);
        final ClassDetail inner = read.classes().get("pkg/Outer$Inner");
        assertEquals("Ljava/lang/Object;Lpkg/Base<Lnet/minecraft/item/ItemStack;>;", inner.signature());
        assertEquals("pkg/Outer", inner.inner().outer());
        assertEquals("Inner", inner.inner().innerName());
        assertEquals(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, inner.inner().access());
        assertEquals("make", inner.enclosing().name());
        assertEquals("Outer.java", inner.sourceFile());
        assertEquals("Ljava/util/List<Ljava/lang/String;>;",
                inner.members().get(MemberRecord.key("items", "Ljava/util/List;", false)).signature());
        assertEquals("java/io/IOException",
                inner.members().get(MemberRecord.key("run", "()V", true)).exceptions()[0]);
        assertEquals("7", inner.members().get(MemberRecord.key("MAX", "I", false)).constantValue());

        final ClassDetail anonymous = read.classes().get("pkg/Anonymous");
        assertNull(anonymous.inner().outer(), "a local or anonymous class has no declaring class");
        assertNull(anonymous.inner().innerName(), "an anonymous class has no name");
        assertNull(anonymous.enclosing().name(), "declared in an initialiser, so no method name");

        assertEquals(1, read.atRules().length);
        assertEquals("", read.atRules()[0].name());
        assertTrue(read.atRules()[0].classVisibility());
    }

    @Test
    void aFileOfAnotherJarIsNotUsed(@TempDir Path dir) {
        final File file = dir.resolve("classes.jar.bin.detail").toFile();
        DetailFile.write(file, sample());
        assertNull(DetailFile.read(file, new JarFingerprint(FINGERPRINT.value(), FINGERPRINT.classEntryCount() + 1)));
        assertNull(DetailFile.read(new File(dir.toFile(), "missing.detail"), FINGERPRINT), "absence is not an error");
    }

    @Test
    void aDamagedFileIsNotUsed(@TempDir Path dir) throws Exception {
        final File file = dir.resolve("damaged.detail").toFile();
        DetailFile.write(file, sample());
        final byte[] bytes = Files.readAllBytes(file.toPath());
        bytes[bytes.length / 2] ^= 0x5A;
        Files.write(file.toPath(), bytes);
        assertNull(DetailFile.read(file, FINGERPRINT));
    }

    @Test
    void aSmallDetailFileIsWrittenUncompressedAndALargeOneCompressed(@TempDir Path dir) throws Exception {
        final File small = dir.resolve("small.detail").toFile();
        DetailFile.write(small, new DetailRecord(FINGERPRINT,
                Map.of("pkg/A", new ClassDetail("Lpkg/A;", null, null, "A.java", Map.of())), new AtRule[0]));
        final byte[] smallBytes = Files.readAllBytes(small.toPath());
        assertTrue(smallBytes.length < 4096 && (smallBytes[0] & 0xFF) != 0x1F, "a small payload is not worth gzip");

        final Map<String, ClassDetail> many = new LinkedHashMap<>();
        for (int i = 0; i < 400; i++) {
            many.put("pkg/Generated" + i, new ClassDetail(
                    "Ljava/util/Map<Ljava/lang/String;Lpkg/Value" + i + ";>;", null, null, "Generated.java", Map.of()));
        }
        final File large = dir.resolve("large.detail").toFile();
        DetailFile.write(large, new DetailRecord(FINGERPRINT, many, new AtRule[0]));
        final byte[] largeBytes = Files.readAllBytes(large.toPath());
        assertEquals(0x1F, largeBytes[0] & 0xFF, "a payload worth compressing is gzip");
        assertEquals(0x8B, largeBytes[1] & 0xFF);
        assertEquals(many, DetailFile.read(large, FINGERPRINT).classes(), "and reads back unchanged");
    }
}
