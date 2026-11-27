package net.minecraftforge.fml.common.discovery.cache;

/**
 * One member of a class, as the class file declares it: the name and descriptor a compiler needs to resolve a call,
 * and the flags a caller needs to know whether the access is even legal.
 */
public record MemberRecord(String name, String descriptor, int access, boolean method) {

    public static final int FLAG_METHOD = 1;

    public boolean isField() {
        return !method;
    }

    /** The key a lookup by name alone uses: the descriptor is what tells an overload apart. */
    public String key() {
        return key(name, descriptor, method);
    }

    /** The key of a member, which is what a detail record is stored under: a field needs only its name. */
    public static String key(String name, String descriptor, boolean method) {
        return method ? name + descriptor : name;
    }
}
