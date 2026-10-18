package com.cleanroommc.lwjgl.legacy;

/**
 * Constant-pool scanner deciding whether a class references LWJGL2 members, without parsing any code.
 */
final class ConstantPoolRefs {

    private ConstantPoolRefs() {}

    private static final String LWJGL_PREFIX = "org/lwjgl/";

    /** Cheap first gate: may report false positives (string literals, LWJGL3 references), never false negatives. */
    static boolean mentionsLwjgl(byte[] classBytes) {
        // Latin1 is a fast path for String(byte[], Charset) and indexOf is SIMD-intrinsified by HotSpot
        return new String(classBytes, java.nio.charset.StandardCharsets.ISO_8859_1).indexOf(LWJGL_PREFIX) >= 0;
    }

    /**
     * Second gate: returns true if the class references a member {@link RewriteTable} can rewrite, matched on owner,
     * name and descriptor. Parse errors are treated as "yes".
     */
    static boolean hasLwjgl2MemberRef(byte[] b) {
        if (b.length < 10) {
            return false;
        }
        final int cpCount = u2(b, 8);
        final int[] offset = new int[cpCount];
        final int[] tag = new int[cpCount];
        int p = 10;
        for (int i = 1; i < cpCount; i++) {
            if (p + 1 > b.length) {
                return true;
            }
            final int t = b[p] & 0xFF;
            tag[i] = t;
            offset[i] = p;
            switch (t) {
                case 1: {
                    final int len = u2(b, p + 1);
                    p += 3 + len;
                    break;
                }
                case 3: case 4:
                    p += 5;
                    break;
                case 5: case 6:
                    p += 9;
                    i++;
                    break;
                case 7: case 8:
                    p += 3;
                    break;
                case 9: case 10: case 11:
                case 12:
                    p += 5;
                    break;
                case 15:
                    p += 4;
                    break;
                case 16:
                    p += 3;
                    break;
                case 17: case 18:
                    p += 5;
                    break;
                case 19: case 20:
                    p += 3;
                    break;
                default:
                    return true;
            }
        }
        for (int i = 1; i < cpCount; i++) {
            final int t = tag[i];
            if (t != 9 && t != 10 && t != 11) {
                continue;
            }
            final int classIdx = u2(b, offset[i] + 1);
            if (classIdx <= 0 || classIdx >= cpCount || tag[classIdx] != 7) {
                continue;
            }
            final int nameIdx = u2(b, offset[classIdx] + 1);
            if (nameIdx <= 0 || nameIdx >= cpCount) {
                continue;
            }
            final String owner = utf8(b, offset[nameIdx]);
            if (owner == null || !RewriteTableIndex.OWNERS.contains(owner)) {
                continue;
            }
            // The owner alone only says "this class is touched by the table", not that this member is: resolve the
            // NameAndType as well, so the gate lets through exactly the classes the rewriter will change.
            final int nameTypeIdx = u2(b, offset[i] + 3);
            if (nameTypeIdx <= 0 || nameTypeIdx >= cpCount || tag[nameTypeIdx] != 12) {
                return true;
            }
            final String name = utf8(b, offset[u2(b, offset[nameTypeIdx] + 1)]);
            if (name == null) {
                continue;
            }
            final int descIdx = u2(b, offset[nameTypeIdx] + 3);
            final int descOffset = descIdx > 0 && descIdx < cpCount ? offset[descIdx] : 0;
            final String desc = utf8(b, descOffset);
            if (desc != null && RewriteTableIndex.isRewritable(owner, name, desc)) {
                return true;
            }
        }
        return false;
    }

    private static String utf8(byte[] b, int entryOffset) {
        if (entryOffset <= 0 || entryOffset + 3 > b.length || (b[entryOffset] & 0xFF) != 1) {
            return null;
        }
        final int len = u2(b, entryOffset + 1);
        final int start = entryOffset + 3;
        if (start + len > b.length) {
            return null;
        }
        final char[] chars = new char[len];
        for (int i = 0; i < len; i++) {
            chars[i] = (char) (b[start + i] & 0xFF);
        }
        return new String(chars);
    }

    private static int u2(byte[] b, int p) {
        return ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF);
    }
}
