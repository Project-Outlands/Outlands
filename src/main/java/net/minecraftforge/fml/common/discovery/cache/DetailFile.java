package net.minecraftforge.fml.common.discovery.cache;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32C;

import javax.annotation.Nullable;

import net.minecraftforge.fml.common.FMLLog;
import net.minecraftforge.fml.common.asm.transformers.AtRule;

/**
 * On-disk form of a {@link DetailRecord}, a third file beside the class record and the member table.
 *
 * <p>Layout ({@link #encode} and {@link #decode} are the reference): {@code magic || rev || flags(reserved) ||
 * classEntryCount || fingerprint || pool[] || classCount || classes[] || atCount || atRules[] || crc32c}, big-endian,
 * gzip through {@link CacheFile}. It carries its own revision, so a change here does not invalidate the class record or
 * the member table, and its absence only means "nobody asked for detail on this archive yet".
 */
public final class DetailFile {

    /** Bump when what is collected or the payload meaning change (ASM upgrades included). */
    public static final int CACHE_REV = 1;

    private static final int MAGIC = 0x4446; // 'D''F'
    static final String SUFFIX = ".detail";
    private static final String TEMP_SUFFIX = ".tmp";

    private static final int CLASS_SIGNATURE = 1;
    private static final int CLASS_INNER = 1 << 1;
    private static final int CLASS_ENCLOSING = 1 << 2;
    private static final int CLASS_SOURCE = 1 << 3;

    private static final int MEMBER_SIGNATURE = 1;
    private static final int MEMBER_EXCEPTIONS = 1 << 1;
    private static final int MEMBER_CONSTANT = 1 << 2;

    private DetailFile() {}

    /** @return the detail file of a jar, next to the record {@link RecordFile#fileFor} places, or null */
    @Nullable
    public static File fileFor(@Nullable File gameDir, File jar) {
        final File record = RecordFile.fileFor(gameDir, jar);
        return record == null ? null : new File(record.getPath() + SUFFIX);
    }

    /** Writes the detail atomically; failures are logged and nothing else. */
    public static void write(@Nullable File file, DetailRecord record) {
        if (file == null || record.isEmpty()) {
            return;
        }
        try {
            CacheFile.write(file, encode(record), TEMP_SUFFIX);
        } catch (Exception e) {
            FMLLog.log.debug("Could not write the class detail {}", file, e);
        }
    }

    /** @return the detail of every class in the file, or null when it is absent, unreadable or another jar's */
    @Nullable
    public static DetailRecord read(@Nullable File file, JarFingerprint expected) {
        if (file == null || !file.isFile()) {
            return null;
        }
        try {
            return decode(CacheFile.read(file), expected);
        } catch (Exception e) {
            FMLLog.log.debug("Ignoring unreadable class detail {}", file, e);
            return null;
        }
    }

    // ---------------------------------------- encoding -----------------------------------------------

    private static byte[] encode(DetailRecord record) throws IOException {
        final CacheFormat.Pool pool = new CacheFormat.Pool();
        final ByteArrayOutputStream bodyBytes = new ByteArrayOutputStream(1 << 14);
        final DataOutputStream body = new DataOutputStream(bodyBytes);

        CacheFormat.writeVInt(body, record.classes().size());
        for (Map.Entry<String, ClassDetail> entry : record.classes().entrySet()) {
            writeClass(body, pool, entry.getKey(), entry.getValue());
        }

        final AtRule[] rules = record.atRules();
        CacheFormat.writeVInt(body, rules.length);
        for (AtRule rule : rules) {
            CacheFormat.writeVInt(body, pool.intern(rule.owner()));
            CacheFormat.writeVInt(body, pool.intern(rule.name()));
            writeOptional(body, pool, rule.descriptor().isEmpty() ? null : rule.descriptor());
            CacheFormat.writeVInt(body, rule.targetAccess());
            body.writeByte((rule.classVisibility() ? 1 : 0) | (rule.changeFinal() ? 2 : 0) | (rule.markFinal() ? 4 : 0));
        }
        body.flush();

        final ByteArrayOutputStream out = new ByteArrayOutputStream(bodyBytes.size() + (pool.size() * 16) + 32);
        final DataOutputStream data = new DataOutputStream(out);
        data.writeShort(MAGIC);
        data.writeByte(CACHE_REV);
        data.writeByte(0); // flags, reserved
        CacheFormat.writeVInt(data, record.fingerprint().classEntryCount());
        data.writeLong(record.fingerprint().value());
        CacheFormat.writePool(data, pool);
        bodyBytes.writeTo(data);
        data.flush();

        final CRC32C crc = new CRC32C();
        final byte[] payload = out.toByteArray();
        crc.update(payload, 0, payload.length);
        data.writeInt((int) crc.getValue());
        data.flush();
        return out.toByteArray();
    }

    private static void writeClass(DataOutputStream out, CacheFormat.Pool pool, String name, ClassDetail detail)
            throws IOException {
        CacheFormat.writeVInt(out, pool.intern(name));
        int flags = 0;
        if (detail.signature() != null) {
            flags |= CLASS_SIGNATURE;
        }
        if (detail.inner() != null) {
            flags |= CLASS_INNER;
        }
        if (detail.enclosing() != null) {
            flags |= CLASS_ENCLOSING;
        }
        if (detail.sourceFile() != null) {
            flags |= CLASS_SOURCE;
        }
        out.writeByte(flags);
        if (detail.signature() != null) {
            CacheFormat.writeVInt(out, pool.intern(detail.signature()));
        }
        if (detail.inner() != null) {
            final ClassDetail.Inner inner = detail.inner();
            writeOptional(out, pool, inner.outer());
            writeOptional(out, pool, inner.innerName());
            CacheFormat.writeVInt(out, inner.access());
        }
        if (detail.enclosing() != null) {
            final ClassDetail.Enclosing enclosing = detail.enclosing();
            CacheFormat.writeVInt(out, pool.intern(enclosing.owner()));
            writeOptional(out, pool, enclosing.name());
            writeOptional(out, pool, enclosing.descriptor());
        }
        if (detail.sourceFile() != null) {
            CacheFormat.writeVInt(out, pool.intern(detail.sourceFile()));
        }
        final Map<String, MemberDetail> members = detail.members();
        CacheFormat.writeVInt(out, members.size());
        for (Map.Entry<String, MemberDetail> entry : members.entrySet()) {
            writeMember(out, pool, entry.getKey(), entry.getValue());
        }
    }

    /** The key a member detail is stored under is {@code name} for a field and {@code name+descriptor} for a method. */
    private static void writeMember(DataOutputStream out, CacheFormat.Pool pool, String key, MemberDetail detail)
            throws IOException {
        CacheFormat.writeVInt(out, pool.intern(key));
        int flags = 0;
        final String[] exceptions = detail.exceptions();
        if (detail.signature() != null) {
            flags |= MEMBER_SIGNATURE;
        }
        if (exceptions != null && exceptions.length > 0) {
            flags |= MEMBER_EXCEPTIONS;
        }
        if (detail.constantValue() != null) {
            flags |= MEMBER_CONSTANT;
        }
        out.writeByte(flags);
        if (detail.signature() != null) {
            CacheFormat.writeVInt(out, pool.intern(detail.signature()));
        }
        if (exceptions != null && exceptions.length > 0) {
            CacheFormat.writeVInt(out, exceptions.length);
            for (String exception : exceptions) {
                CacheFormat.writeVInt(out, pool.intern(exception));
            }
        }
        if (detail.constantValue() != null) {
            CacheFormat.writeVInt(out, pool.intern(detail.constantValue()));
        }
    }

    private static void writeOptional(DataOutputStream out, CacheFormat.Pool pool, @Nullable String value)
            throws IOException {
        out.writeBoolean(value != null);
        if (value != null) {
            CacheFormat.writeVInt(out, pool.intern(value));
        }
    }

    @Nullable
    private static DetailRecord decode(byte[] bytes, JarFingerprint expected) throws IOException {
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
        if (data.readUnsignedShort() != MAGIC || data.readUnsignedByte() != CACHE_REV) {
            return null;
        }
        data.readUnsignedByte(); // flags, reserved
        if (CacheFormat.readVInt(data) != expected.classEntryCount() || data.readLong() != expected.value()) {
            return null;
        }
        final String[] pool = CacheFormat.readPool(data, bytes.length);
        if (pool == null) {
            return null;
        }

        final int classCount = CacheFormat.readVInt(data);
        if (classCount < 0) {
            return null;
        }
        final Map<String, ClassDetail> classes = new LinkedHashMap<>(classCount * 2);
        for (int i = 0; i < classCount; i++) {
            final String name = CacheFormat.at(pool, CacheFormat.readVInt(data));
            if (name == null) {
                return null;
            }
            classes.put(name, readClass(data, pool));
        }

        final int ruleCount = CacheFormat.readVInt(data);
        if (ruleCount < 0) {
            return null;
        }
        final AtRule[] rules = new AtRule[ruleCount];
        for (int i = 0; i < ruleCount; i++) {
            final String owner = CacheFormat.at(pool, CacheFormat.readVInt(data));
            final String name = CacheFormat.at(pool, CacheFormat.readVInt(data));
            final String descriptor = readNullable(data, pool);
            final int access = CacheFormat.readVInt(data);
            final int flags = data.readUnsignedByte();
            if (owner == null || name == null) {
                return null;
            }
            rules[i] = new AtRule(owner, name, descriptor == null ? "" : descriptor, access,
                    (flags & 1) != 0, (flags & 2) != 0, (flags & 4) != 0);
        }
        if (data.available() != 0) {
            return null;
        }
        return new DetailRecord(new JarFingerprint(expected.value(), expected.classEntryCount()), classes, rules);
    }

    private static ClassDetail readClass(DataInputStream data, String[] pool) throws IOException {
        final int flags = data.readUnsignedByte();
        final String signature = (flags & CLASS_SIGNATURE) != 0 ? CacheFormat.at(pool, CacheFormat.readVInt(data)) : null;
        ClassDetail.Inner inner = null;
        if ((flags & CLASS_INNER) != 0) {
            inner = new ClassDetail.Inner(readNullable(data, pool), readNullable(data, pool), CacheFormat.readVInt(data));
        }
        ClassDetail.Enclosing enclosing = null;
        if ((flags & CLASS_ENCLOSING) != 0) {
            final String owner = CacheFormat.at(pool, CacheFormat.readVInt(data));
            enclosing = new ClassDetail.Enclosing(owner, readNullable(data, pool), readNullable(data, pool));
        }
        final String sourceFile = (flags & CLASS_SOURCE) != 0 ? CacheFormat.at(pool, CacheFormat.readVInt(data)) : null;
        final int memberCount = CacheFormat.readVInt(data);
        final Map<String, MemberDetail> members = memberCount <= 0 ? Map.of() : new LinkedHashMap<>(memberCount * 2);
        for (int i = 0; i < memberCount; i++) {
            final String key = CacheFormat.at(pool, CacheFormat.readVInt(data));
            final int memberFlags = data.readUnsignedByte();
            final String memberSignature = (memberFlags & MEMBER_SIGNATURE) != 0
                    ? CacheFormat.at(pool, CacheFormat.readVInt(data)) : null;
            String[] exceptions = null;
            if ((memberFlags & MEMBER_EXCEPTIONS) != 0) {
                final int count = CacheFormat.readVInt(data);
                exceptions = new String[count];
                for (int j = 0; j < count; j++) {
                    exceptions[j] = CacheFormat.at(pool, CacheFormat.readVInt(data));
                }
            }
            final String constant = (memberFlags & MEMBER_CONSTANT) != 0
                    ? CacheFormat.at(pool, CacheFormat.readVInt(data)) : null;
            if (key != null) {
                members.put(key, new MemberDetail(memberSignature, exceptions, constant));
            }
        }
        return new ClassDetail(signature, inner, enclosing, sourceFile, members);
    }

    /** A string that may be absent: a local class has no outer, an anonymous one no name. */
    @Nullable
    private static String readNullable(DataInputStream data, String[] pool) throws IOException {
        if (!data.readBoolean()) {
            return null;
        }
        return CacheFormat.at(pool, CacheFormat.readVInt(data));
    }
}
