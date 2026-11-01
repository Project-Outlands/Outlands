package net.minecraftforge.lwjgl.legacy;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import javax.annotation.Nullable;

import net.minecraft.launchwrapper.Launch;
import net.minecraftforge.fml.common.discovery.DiscoveredMod;
import net.minecraftforge.fml.common.discovery.ModDiscoverer;
import top.outlands.foundation.boot.LoadingContext;

import org.spongepowered.asm.util.Constants.ManifestAttributes;

/**
 * Classifies the source a class comes from, using the mod markings the early scan produced.
 *
 * <p>Only the early scan can answer "is this source a mod at all": most Forge mods carry no {@code ModType}
 * manifest attribute at all, so a missing attribute means "Forge mod", not "not a mod". A {@code ModType} that is
 * present and reads {@code CRL} is the one positive statement a manifest makes on its own.
 *
 * <p>Used by {@link Lwjgl2CompatTransformer} to decide whose classes need LWJGL2 call sites rewritten.
 */
final class ModSourceIndex {

    /** What a class's source is, as far as the early scan can tell. */
    enum Kind {
        /** The source was not scanned, or the scan had not run yet: no statement can be made. */
        UNCLASSIFIED,
        /** The source was scanned and carries no mod marking at all. */
        NON_MOD,
        /** A mod the Forge container type applies to: LWJGL2 call sites must be rewritten. */
        FORGE_MOD,
        /** A mod that declares {@code ModType: CRL}, i.e. one written against the loader's own LWJGL3 API. */
        LOADER_MOD
    }

    /**
     * Decision per package, because a source root always covers whole packages. {@link Kind#UNCLASSIFIED} is never
     * cached: it is also the answer before the early scan has run, and must be reconsidered later.
     */
    private final ConcurrentMap<String, Kind> byPackage = new ConcurrentHashMap<>();

    /** The source root of the class, for diagnostics; {@code null} when it could not be resolved. */
    @Nullable
    File sourceNameOf(String internalName) {
        return sourceOf(internalName);
    }

    /** The kind of the class {@code internalName} belongs to, resolving its source on a package cache miss. */
    Kind kindOf(String internalName) {
        int slash = internalName.lastIndexOf('/');
        String pkg = slash <= 0 ? "" : internalName.substring(0, slash);
        Kind known = byPackage.get(pkg);
        if (known != null) {
            return known;
        }
        Kind kind = kindOfSource(sourceOf(internalName));
        if (kind != Kind.UNCLASSIFIED) {
            byPackage.put(pkg, kind);
        }
        return kind;
    }

    private static Kind kindOfSource(@Nullable File source) {
        if (source == null) {
            return Kind.UNCLASSIFIED;
        }
        DiscoveredMod discovered;
        try {
            discovered = ModDiscoverer.instance().discoveredMod(source);
        } catch (Throwable t) {
            // Discovery may still be running; a class loaded from inside it must not break class loading.
            return Kind.UNCLASSIFIED;
        }
        return classify(discovered);
    }

    /**
     * The classification of one early-scan record. {@code null} means the source was never scanned.
     *
     * <p>Being a mod is decided by the scan alone; the manifest is consulted afterwards, only to tell an Outlands
     * mod apart from a Forge one. Most mods in the wild declare no {@code ModType} at all - plenty have no manifest
     * either - so the attribute can never be read as "this is not a mod".
     *
     * <p>A source directory that only sits on the classpath - the output directory of the module being developed -
     * is not scanned at all and therefore stays {@link Kind#UNCLASSIFIED}.
     */
    static Kind classify(@Nullable DiscoveredMod discovered) {
        if (discovered == null) {
            return Kind.UNCLASSIFIED;
        }
        if (!isMod(discovered)) {
            return Kind.NON_MOD;
        }
        return ManifestAttributes.CLEANROOMMODTYPE.equals(discovered.modType())
                ? Kind.LOADER_MOD
                : Kind.FORGE_MOD;
    }

    /**
     * Whether the scan found anything that makes this source a mod: a mod id, mixin manifest attributes, a coremod
     * or a tweaker. This is the primary criterion; {@code modType} only refines mods into Forge and Outlands ones.
     */
    private static boolean isMod(DiscoveredMod discovered) {
        return !discovered.modIds().isEmpty()
                || discovered.hasMixinManifestAttributes()
                || discovered.mixinTweakerForceMod()
                || discovered.coreModContainsMod()
                || discovered.coremod() != null
                || discovered.tweaker() != null;
    }

    /**
     * The artifact root (jar file or class directory) the class was found in, or {@code null} when unknown.
     *
     * <p>{@link LoadingContext} answers this without any lookup: Foundation records the code source of the class it is
     * loading for the duration of the transformer calls. Only classes loaded outside it fall back to a resource
     * lookup, which costs a jar index scan.
     */
    @Nullable
    private static File sourceOf(String internalName) {
        LoadingContext context = LoadingContext.current();
        if (context != null && context.codeSourceUrl() != null) {
            File source = sourceOf(context.codeSourceUrl(), internalName);
            if (source != null) {
                return source;
            }
        }
        URL resource;
        try {
            resource = Launch.classLoader.getResource(internalName + ".class");
        } catch (Throwable t) {
            return null;
        }
        return resource == null ? null : sourceOf(resource, internalName);
    }

    /** Turns a class resource URL into the root it lives in: the jar for {@code jar:}, the directory otherwise. */
    @Nullable
    static File sourceOf(URL resource, String internalName) {
        String external = resource.toString();
        try {
            if (external.startsWith("jar:")) {
                int separator = external.indexOf("!/");
                if (separator < 0) {
                    return null;
                }
                return new File(new URL(external.substring("jar:".length(), separator)).toURI());
            }
            if (!"file".equals(resource.getProtocol())) {
                return null;
            }
            File file = new File(resource.toURI());
            // strip the class path below the root: as many segments as the class name has, the file itself included
            int segments = 1;
            for (int i = 0; i < internalName.length(); i++) {
                if (internalName.charAt(i) == '/') {
                    segments++;
                }
            }
            File root = file;
            for (int i = 0; i < segments && root != null; i++) {
                root = root.getParentFile();
            }
            return root;
        } catch (URISyntaxException | IOException | RuntimeException e) {
            return null;
        }
    }
}
