package net.minecraftforge.fml.common.discovery.cache;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The member table is a second file next to the class record, guarded by the same fingerprint; a table of another jar,
 * of another revision or a damaged one must not be used.
 */
class MemberFileTest {

    private static final JarFingerprint FINGERPRINT = new JarFingerprint(0x1234_5678_9ABC_DEF0L, 42);

    @Test
    void aTableRoundTripsThroughDisk(@TempDir Path dir) throws Exception {
        final File file = dir.resolve("classes.jar.members").toFile();
        final Map<String, MemberRecord[]> members = new LinkedHashMap<>();
        members.put("pkg/Thing", new MemberRecord[] {
                new MemberRecord("value", "I", 0x0002 /* private */, false),
                new MemberRecord("<init>", "()V", 0x0001, true),
                new MemberRecord("value", "()I", 0x0001, true),
        });
        members.put("pkg/Empty", new MemberRecord[0]);

        MemberFile.write(file, FINGERPRINT, members);
        assertTrue(file.isFile());

        final Map<String, MemberRecord[]> read = MemberFile.read(file, FINGERPRINT);
        assertNotNull(read);
        assertEquals(2, read.size());
        final MemberRecord[] thing = read.get("pkg/Thing");
        assertEquals(3, thing.length);
        assertEquals("value", thing[0].name());
        assertEquals("I", thing[0].descriptor());
        assertEquals(0x0002, thing[0].access());
        assertTrue(thing[0].isField());
        assertEquals("<init>", thing[1].name());
        assertTrue(thing[1].method());
        assertEquals(0, read.get("pkg/Empty").length, "a class without members is still a class in the table");
    }

    @Test
    void anOverloadIsToldApartByItsDescriptor(@TempDir Path dir) throws Exception {
        final File file = dir.resolve("overloads.jar.members").toFile();
        MemberFile.write(file, FINGERPRINT, Map.of("pkg/Thing", new MemberRecord[] {
                new MemberRecord("value", "()I", 0x0001, true),
                new MemberRecord("value", "(I)V", 0x0001, true),
        }));

        final MemberRecord[] read = MemberFile.read(file, FINGERPRINT).get("pkg/Thing");
        assertEquals("value()I", read[0].key());
        assertEquals("value(I)V", read[1].key());
    }

    @Test
    void aTableOfAnotherJarIsRejected(@TempDir Path dir) throws Exception {
        final File file = dir.resolve("other.jar.members").toFile();
        MemberFile.write(file, FINGERPRINT, Map.of("a/B", new MemberRecord[] {new MemberRecord("f", "I", 0, false)}));

        assertNull(MemberFile.read(file, new JarFingerprint(FINGERPRINT.value() ^ 1L, FINGERPRINT.classEntryCount())));
        assertNull(MemberFile.read(file, new JarFingerprint(FINGERPRINT.value(), FINGERPRINT.classEntryCount() + 1)));
        assertNotNull(MemberFile.read(file, FINGERPRINT));
    }

    @Test
    void aDamagedTableIsRejected(@TempDir Path dir) throws Exception {
        final File file = dir.resolve("damaged.jar.members").toFile();
        MemberFile.write(file, FINGERPRINT, Map.of("a/B", new MemberRecord[] {new MemberRecord("f", "I", 0, false)}));

        final byte[] bytes = Files.readAllBytes(file.toPath());
        bytes[bytes.length - 6] ^= 0x7F;
        Files.write(file.toPath(), bytes);

        assertNull(MemberFile.read(file, FINGERPRINT), "a table whose checksum no longer matches is not used");
    }

    @Test
    void aMissingFileIsNoTable(@TempDir Path dir) throws Exception {
        assertNull(MemberFile.read(null, FINGERPRINT));
        assertNull(MemberFile.read(dir.resolve("absent.members").toFile(), FINGERPRINT));
    }
}
