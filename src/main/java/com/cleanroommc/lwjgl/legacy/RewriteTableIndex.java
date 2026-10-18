package com.cleanroommc.lwjgl.legacy;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The owners {@link RewriteTable} can rewrite a member of, derived from the table keys.
 *
 * <p>{@link ConstantPoolRefs} uses this as its second gate: a class that references an LWJGL2 class but no member the
 * table acts on has nothing to rewrite, and {@link Lwjgl2ClassSet} - the full inventory of LWJGL2 classes, shimmed
 * ones included - would send it through a full ASM pass for nothing.
 *
 * <p>Derived rather than hand-written: the gate and the table can no longer disagree about what is rewritable.
 */
final class RewriteTableIndex {

    /** Owners of the members in {@link RewriteTable}, i.e. every class the rewriter can act on. */
    static final Set<String> OWNERS = owners(
            RewriteTable.RENAME, RewriteTable.ALIAS, RewriteTable.CONVERT, RewriteTable.CTOR, RewriteTable.ADAPTER);

    private RewriteTableIndex() {}

    /** Whether the table acts on exactly this member; the five tables are consulted in {@code CompatRewriter} order. */
    static boolean isRewritable(String owner, String name, String desc) {
        String key = RewriteTable.key(owner, name, desc);
        return RewriteTable.RENAME.containsKey(key)
                || RewriteTable.ALIAS.containsKey(key)
                || RewriteTable.CONVERT.containsKey(key)
                || RewriteTable.CTOR.containsKey(key)
                || RewriteTable.ADAPTER.containsKey(key);
    }

    @SafeVarargs
    private static Set<String> owners(Map<String, String>... tables) {
        Set<String> owners = new HashSet<>();
        for (Map<String, String> table : tables) {
            for (String key : table.keySet()) {
                owners.add(ownerOf(key));
            }
        }
        return Collections.unmodifiableSet(owners);
    }

    /** The owner part of a {@link RewriteTable#key}: everything before the first separator. */
    static String ownerOf(String key) {
        int end = key.indexOf('\0');
        return end < 0 ? key : key.substring(0, end);
    }
}
