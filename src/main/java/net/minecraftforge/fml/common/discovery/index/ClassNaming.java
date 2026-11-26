package net.minecraftforge.fml.common.discovery.index;

/**
 * How the class loader renames what it reads, in the three shapes the index stores: a type, a member, a descriptor.
 *
 * <p>Kept as an interface so the index can be built and tested without the loader's mapping, and so that the vanilla
 * pass asks for exactly the operations the transformer uses rather than a name-only subset.
 */
public interface ClassNaming {

    /** A class name the way the loader defines it. */
    String type(String internalName);

    /** A declared member of {@code ownerInternalName}, which is the name the class file declares, not the mapped one. */
    String member(String ownerInternalName, String name, String descriptor, boolean method);

    /** A member descriptor with the type names inside it mapped. */
    String descriptor(String descriptor);

    /** The mapping that leaves everything alone, which is what a deobfuscated install uses. */
    static ClassNaming identity() {
        return new ClassNaming() {
            @Override
            public String type(String internalName) {
                return internalName;
            }

            @Override
            public String member(String ownerInternalName, String name, String descriptor, boolean method) {
                return name;
            }

            @Override
            public String descriptor(String descriptor) {
                return descriptor;
            }
        };
    }
}
