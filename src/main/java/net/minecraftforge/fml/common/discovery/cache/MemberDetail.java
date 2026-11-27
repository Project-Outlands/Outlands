package net.minecraftforge.fml.common.discovery.cache;

import javax.annotation.Nullable;

/**
 * What one member says beyond its name, descriptor and access: the generic signature a completion shows, the checked
 * exceptions a signature hint lists, and the constant a static final field stands for.
 *
 * <p>Absent for most members; a member with none of the three is not stored at all.
 */
public record MemberDetail(
        @Nullable String signature,
        @Nullable String[] exceptions,
        @Nullable String constantValue) {

    /**
     * @param signature  the generic signature, or null
     * @param exceptions the checked exceptions, or null
     * @param value      the {@code ConstantValue} a field was visited with, or null
     * @return the detail, or null when the member has none of the three
     */
    public static MemberDetail of(@Nullable String signature, @Nullable String[] exceptions, @Nullable Object value) {
        final String constant = constantOf(value);
        final String[] thrown = exceptions == null || exceptions.length == 0 ? null : exceptions;
        if (signature == null && thrown == null && constant == null) {
            return null;
        }
        return new MemberDetail(signature, thrown, constant);
    }

    /**
     * @return the literal a completion shows, or null for a value that is not one (a class literal, a method handle)
     */
    @Nullable
    public static String constantOf(@Nullable Object value) {
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte
                || value instanceof Float || value instanceof Double) {
            return String.valueOf(value);
        }
        if (value instanceof Character character) {
            return String.valueOf(character);
        }
        if (value instanceof Boolean flag) {
            return String.valueOf(flag);
        }
        return null;
    }

    public boolean isEmpty() {
        return signature == null && (exceptions == null || exceptions.length == 0) && constantValue == null;
    }
}
