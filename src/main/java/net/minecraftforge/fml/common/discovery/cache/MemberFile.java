package net.minecraftforge.fml.common.discovery.cache;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32C;

import javax.annotation.Nullable;

import net.minecraftforge.fml.common.FMLLog;

/**
 * On-disk form of a jar's member table: the members of every scanned class, keyed by class name and guarded by the
 * fingerprint the scan was taken for.
 *
 * <p>Kept apart from {@link RecordFile} because the two are read at different moments: the class headers answer
 * "which archive has this class" during discovery, while a member table is only needed once something wants to
 * resolve a member, and a full member table is an order of magnitude larger than the headers.
 *
 * <p>Layout ({@link #write} and {@link #decode} are the reference): {@code magic || rev || flags(reserved) ||
 * classEntryCount || fingerprint || classCount || poolCount || pool[] || body[] || crc32c}, big-endian. The body is
 * one record per class, so the class name is the key and the file does not depend on the order the headers are in.
 */
public final class MemberFile {

    /** Bump when what is collected or the payload meaning change (ASM upgrades included). */
    public static final int CACHE_REV = 1;

    private static final int MAGIC = 0x4D46; // 'M''F'
    private static final String SUFFIX = ".members";
    private static final String TEMP_SUFFIX = ".tmp";

    private MemberFile() {}

    /** @return the member file of a jar, next to the record {@link RecordFile#fileFor} places, or null */
    @Nullable
    public static File fileFor(@Nullable File gameDir, File jar) {
        final File record = RecordFile.fileFor(gameDir, jar);
        return record == null ? null : new File(record.getPath() + SUFFIX);
    }

    /** Writes the table atomically; failures are logged and nothing else. */
    public static void write(@Nullable File file, JarFingerprint fingerprint, Map<String, MemberRecord[]> members) {
        if (file == null || members.isEmpty()) {
            return;
        }
        try {
            final byte[] bytes = encode(fingerprint, members);
            final File parent = file.getParentFile();
            if (parent != null) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            final File temp = new File(file.getPath() + TEMP_SUFFIX);
            Files.write(temp.toPath(), bytes);
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            FMLLog.log.debug("Could not write the member index {}", file, e);
        }
    }

    /**
     * @return the members of every class in the file, or null when it is absent, unreadable or belongs to another
     *         version of the jar
     */
    @Nullable
    public static Map<String, MemberRecord[]> read(@Nullable File file, JarFingerprint expected) {
        if (file == null || !file.isFile()) {
            return null;
        }
        try {
            return decode(Files.readAllBytes(file.toPath()), expected);
        } catch (Exception e) {
            FMLLog.log.debug("Ignoring unreadable member index {}", file, e);
            return null;
        }
    }

    private static byte[] encode(JarFingerprint fingerprint, Map<String, MemberRecord[]> members) throws IOException {
        final Pool pool = new Pool();
        final ByteArrayOutputStream bodyBytes = new ByteArrayOutputStream(1 << 14);
        final DataOutputStream body = new DataOutputStream(bodyBytes);
        writeVInt(body, members.size());
        for (Map.Entry<String, MemberRecord[]> entry : members.entrySet()) {
            writeVInt(body, pool.intern(entry.getKey()));
            final MemberRecord[] records = entry.getValue();
            writeVInt(body, records.length);
            for (MemberRecord record : records) {
                writeVInt(body, pool.intern(record.name()));
                writeVInt(body, pool.intern(record.descriptor()));
                writeVInt(body, record.access());
                body.writeByte(record.method() ? MemberRecord.FLAG_METHOD : 0);
            }
        }

        final ByteArrayOutputStream out = new ByteArrayOutputStream(bodyBytes.size() + (pool.size() * 16) + 32);
        final DataOutputStream data = new DataOutputStream(out);
        data.writeShort(MAGIC);
        data.writeByte(CACHE_REV);
        data.writeByte(0); // flags, reserved
        writeVInt(data, fingerprint.classEntryCount());
        data.writeLong(fingerprint.value());
        writeVInt(data, pool.size());
        for (String string : pool.strings()) {
            final byte[] utf8 = string.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            writeVInt(data, utf8.length);
            data.write(utf8);
        }
        body.flush();
        data.write(bodyBytes.toByteArray());

        final CRC32C crc = new CRC32C();
        crc.update(out.toByteArray(), 0, out.size());
        data.writeInt((int) crc.getValue());
        data.flush();
        return out.toByteArray();
    }

    @Nullable
    private static Map<String, MemberRecord[]> decode(byte[] bytes, JarFingerprint expected) throws IOException {
        if (bytes.length < 16) {
            return null;
        }
        final CRC32C crc = new CRC32C();
        crc.update(bytes, 0, bytes.length - 4);
        final int stored = java.nio.ByteBuffer.wrap(bytes, bytes.length - 4, 4).getInt();
        if ((int) crc.getValue() != stored) {
            return null;
        }

        final DataInputStream data = new DataInputStream(new ByteArrayInputStream(bytes, 0, bytes.length - 4));
        if (data.readUnsignedShort() != MAGIC) {
            return null;
        }
        if (data.readUnsignedByte() != CACHE_REV) {
            return null;
        }
        data.readUnsignedByte(); // flags, reserved
        final int classEntryCount = readVInt(data);
        final long fingerprint = data.readLong();
        if (classEntryCount != expected.classEntryCount() || fingerprint != expected.value()) {
            return null;
        }

        final int poolCount = readVInt(data);
        if (poolCount < 0) {
            return null;
        }
        final String[] pool = new String[poolCount];
        for (int i = 0; i < poolCount; i++) {
            final byte[] utf8 = new byte[readVInt(data)];
            data.readFully(utf8);
            pool[i] = new String(utf8, java.nio.charset.StandardCharsets.UTF_8);
        }

        final int classCount = readVInt(data);
        if (classCount < 0) {
            return null;
        }
        final Map<String, MemberRecord[]> members = new LinkedHashMap<>(classCount * 2);
        for (int i = 0; i < classCount; i++) {
            final String className = pool[readVInt(data)];
            final int memberCount = readVInt(data);
            if (memberCount < 0) {
                return null;
            }
            final MemberRecord[] records = new MemberRecord[memberCount];
            for (int j = 0; j < memberCount; j++) {
                final String name = pool[readVInt(data)];
                final String descriptor = pool[readVInt(data)];
                final int access = readVInt(data);
                final boolean method = (data.readUnsignedByte() & MemberRecord.FLAG_METHOD) != 0;
                records[j] = new MemberRecord(name, descriptor, access, method);
            }
            members.put(className, records);
        }
        if (data.available() != 0) {
            return null;
        }
        return members;
    }

    private static void writeVInt(DataOutputStream out, int value) throws IOException {
        int v = (value << 1) ^ (value >> 31);
        while ((v & ~0x7F) != 0) {
            out.writeByte((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.writeByte(v);
    }

    private static int readVInt(DataInputStream in) throws IOException {
        int v = 0;
        for (int shift = 0; shift <= 28; shift += 7) {
            int b = in.readUnsignedByte();
            v |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return (v >>> 1) ^ -(v & 1);
            }
        }
        throw new IOException("malformed varint");
    }

    private static final class Pool {
        private final List<String> strings = new ArrayList<>();
        private final Map<String, Integer> ids = new HashMap<>();

        int intern(String string) {
            Integer id = ids.get(string);
            if (id == null) {
                id = strings.size();
                strings.add(string);
                ids.put(string, id);
            }
            return id;
        }

        int size() {
            return strings.size();
        }

        List<String> strings() {
            return strings;
        }
    }
}
