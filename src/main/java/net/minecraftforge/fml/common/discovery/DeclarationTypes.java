package net.minecraftforge.fml.common.discovery;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The {@code jar.type} values the loader knows what to do with.
 *
 * <p>Two are built in, and only because each changes the loader's own assembly: {@link #MOD} is the default (scan for
 * containers) and {@link #LIB} says "put me on the classpath and do not look for mods in me". Nothing else is built in
 * on purpose - a {@code PLUGIN} type would drag one plugin framework's semantics into the loader, and the frameworks
 * already have their own container registries.
 *
 * <p>Other ids can be registered, which currently means "expected, do not warn about it". The jar still goes on the
 * classpath without containers being built from it, which is the same handling an unknown id gets minus the warning.
 * A factory hook that would build containers for a registered type belongs with the declarative container work, and
 * inventing its shape here would be guessing.
 *
 * <p>Registration is a static, process-wide list for the same reason the language adapter registry is: the ids describe
 * what this instance can load, and they have to be readable from the places that log about jars.
 */
public final class DeclarationTypes {

    /** A jar the loader scans for mod containers. The default. */
    public static final String MOD = "MOD";
    /** A jar that only goes on the classpath: libraries, language providers, resource packs shipped as jars. */
    public static final String LIB = "LIB";

    private static final Set<String> REGISTERED = new LinkedHashSet<>(Set.of(MOD, LIB));

    private DeclarationTypes() {
    }

    /**
     * Registers an additional type id.
     *
     * @param id the id as it would appear in {@code jar.type}, matched case insensitively
     * @throws IllegalArgumentException if the id is blank or is not a plain identifier
     */
    public static void register(String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("A declaration type id cannot be blank");
        }
        REGISTERED.add(id.trim().toUpperCase(Locale.ENGLISH));
    }

    /** @return whether {@code type} is one this instance knows about; {@code null} answers {@code false} */
    public static boolean isKnown(String type) {
        return type != null && REGISTERED.contains(type.trim().toUpperCase(Locale.ENGLISH));
    }

    /**
     * @return whether a jar declaring {@code type} should be scanned for mod containers. Only an unknown type and
     *         {@link #LIB} say no: the loader cannot build containers it has no factory for, and an unknown type must
     *         not silently start behaving like a mod.
     */
    public static boolean isScannedForContainer(String type) {
        return MOD.equals(type == null ? null : type.trim().toUpperCase(Locale.ENGLISH));
    }

    /** @return the registered ids, for diagnostics */
    public static String describe() {
        return String.join(", ", REGISTERED);
    }
}
