package net.minecraftforge.fml.common.discovery.cache;

import java.util.Map;

import javax.annotation.Nullable;

/**
 * What a class file says that only a language tool asks for: the generic signature, where a nested class belongs, and
 * the per-member extras. Kept in the detail file instead of the class header because nothing in the loader reads it:
 * the header answers "which archive has this class", the detail answers "what does it look like" for a compiler.
 *
 * <p>Every field is optional and absent for most classes. {@link #members()} only holds the members that have
 * something to say, keyed by {@link MemberRecord#key(String, String, boolean)}.
 */
public record ClassDetail(
        @Nullable String signature,
        @Nullable Inner inner,
        @Nullable Enclosing enclosing,
        @Nullable String sourceFile,
        Map<String, MemberDetail> members) {

    public static final ClassDetail EMPTY = new ClassDetail(null, null, null, null, Map.of());

    /**
     * The class' own entry in its {@code InnerClasses} attribute. The class file's own access flags have no
     * {@code ACC_STATIC} or {@code ACC_PRIVATE}, so this is the only place those exist.
     *
     * @param outer     the declaring class, null for a local or anonymous class
     * @param innerName the source name, null for an anonymous class
     */
    public record Inner(@Nullable String outer, @Nullable String innerName, int access) {}

    /**
     * The {@code EnclosingMethod} attribute, which local and anonymous classes carry.
     *
     * @param name       the method, null when the class was declared in an initialiser
     * @param descriptor that method's descriptor, null together with {@code name}
     */
    public record Enclosing(String owner, @Nullable String name, @Nullable String descriptor) {}

    /** Whether nothing at all is known about this class, so the record can be dropped from the file. */
    public boolean isEmpty() {
        return signature == null && inner == null && enclosing == null && sourceFile == null && members.isEmpty();
    }
}
