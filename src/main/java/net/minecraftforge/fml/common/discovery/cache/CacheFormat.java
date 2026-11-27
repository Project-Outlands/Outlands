package net.minecraftforge.fml.common.discovery.cache;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The pieces every cache file shares: a zig-zag varint and a string pool.
 *
 * <p>The pool goes before the body of a file, so the body is buffered until every string has been interned; that is
 * also what makes exact repeats (class names, type names, descriptors) cost one varint each instead of their bytes.
 */
final class CacheFormat {

    /** The string index a record writes instead of the string itself. */
    static final class Pool {
        private final List<String> strings = new ArrayList<>();
        private final Map<String, Integer> ids = new HashMap<>();

        int intern(String string) {
            final Integer id = ids.get(string);
            if (id != null) {
                return id;
            }
            final int next = strings.size();
            strings.add(string);
            ids.put(string, next);
            return next;
        }

        int size() {
            return strings.size();
        }

        List<String> strings() {
            return strings;
        }
    }

    private CacheFormat() {}

    static void writePool(DataOutputStream out, Pool pool) throws IOException {
        writeVInt(out, pool.size());
        for (String string : pool.strings()) {
            final byte[] utf8 = string.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            writeVInt(out, utf8.length);
            out.write(utf8);
        }
    }

    /** @return the pool, or null when the count is not a count */
    static String[] readPool(DataInputStream data, int length) throws IOException {
        final int poolCount = readVInt(data);
        if (poolCount < 0 || poolCount > length) {
            return null;
        }
        final String[] pool = new String[poolCount];
        for (int i = 0; i < poolCount; i++) {
            final int size = readVInt(data);
            if (size < 0 || size > length) {
                return null;
            }
            final byte[] utf8 = new byte[size];
            data.readFully(utf8);
            pool[i] = new String(utf8, java.nio.charset.StandardCharsets.UTF_8);
        }
        return pool;
    }

    static void writeVInt(DataOutputStream out, int value) throws IOException {
        int v = (value << 1) ^ (value >> 31);
        while ((v & ~0x7F) != 0) {
            out.writeByte((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.writeByte(v);
    }

    static int readVInt(DataInputStream in) throws IOException {
        int v = 0;
        for (int shift = 0; shift <= 28; shift += 7) {
            final int b = in.readUnsignedByte();
            v |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return (v >>> 1) ^ -(v & 1);
            }
        }
        throw new IOException("malformed varint");
    }

    static void writeVLong(DataOutputStream out, long value) throws IOException {
        long v = (value << 1) ^ (value >> 63);
        while ((v & ~0x7FL) != 0) {
            out.writeByte((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        out.writeByte((int) v);
    }

    static long readVLong(DataInputStream in) throws IOException {
        long v = 0;
        for (int shift = 0; shift <= 63; shift += 7) {
            final int b = in.readUnsignedByte();
            v |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return (v >>> 1) ^ -(v & 1);
            }
        }
        throw new IOException("malformed varlong");
    }

    /** @return whether {@code value} is the interned string at {@code index}, for validating a pool index */
    static String at(String[] pool, int index) {
        return index >= 0 && index < pool.length ? pool[index] : null;
    }
}
