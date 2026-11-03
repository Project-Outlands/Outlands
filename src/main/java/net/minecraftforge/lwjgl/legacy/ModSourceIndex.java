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

/**
 * Classifies the source a class comes from, using the mod markings the early scan produced.
 *
 * <p>The question it answers is "may this class's LWJGL2 call sites be rewritten", not "is this a mod": the scan
 * decides what is a mod at all, and {@code mcmod.info} decides whether that mod was written against the current
 * environment.
 *
 * <p>Any answer other than {@link Kind#MODERN} means "rewrite", including {@link Kind#UNCLASSIFIED}: the rewrite is
 * gated on an exact {@code owner/name/desc} match against {@link RewriteTable}, so a class with no LWJGL2 call site is
 * left untouched anyway. Failing to classify therefore costs a constant-pool scan, never correctness.
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
        /**
         * A mod written before the current environment existed: rewrite its call sites.
         *
         * <p>Where every jar lands that declares {@code ModType: CRL}. That attribute used to mean "skip", but those
         * jars were built while both LWJGL generations were reachable, so their code may call either, and the ones
         * that call LWJGL2 are exactly what this transformer exists to fix.
         */
        LEGACY,
        /**
         * A mod that declares the current metadata schema, and therefore the current environment: no LWJGL2 API was
         * on its compile classpath, so its call sites are LWJGL3 ones and rewriting them is redundant work.
         */
        MODERN
    }

    /**
     * Decision per source artifact, i.e. per jar. One jar is one answer, which is what the decision is about.
     *
     * <p>Deliberately not keyed by package: two jars can contribute classes to the same package (a shaded library and
     * the mod that shades it), and a package-wide answer would then decide one jar's fate from the other's metadata.
     * {@link Kind#UNCLASSIFIED} is never cached: it is also the answer before the early scan has run, and must be
     * reconsidered later.
     */
    private final ConcurrentMap<File, Kind> bySource = new ConcurrentHashMap<>();

    /** The source root of the class, for diagnostics; {@code null} when it could not be resolved. */
    @Nullable
    File sourceNameOf(String internalName) {
        return sourceOf(internalName);
    }

    /** The kind of the class {@code internalName} belongs to, resolving its source on a cache miss. */
    Kind kindOf(String internalName) {
        File source = sourceOf(internalName);
        if (source == null) {
            return Kind.UNCLASSIFIED;
        }
        Kind known = bySource.get(source);
        if (known != null) {
            return known;
        }
        Kind kind = kindOfSource(source);
        if (kind != Kind.UNCLASSIFIED) {
            bySource.put(source, kind);
        }
        return kind;
    }

    private static Kind kindOfSource(File source) {
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
     * <p>Being a mod is decided by the scan alone; the metadata is consulted afterwards, only to tell a jar written
     * against the current environment from everything else. Most mods in the wild declare no {@code ModType} at all -
     * plenty have no manifest either - so nothing about a source can be read as "not a mod" but the scan itself.
     *
     * <p>A source directory that only sits on the classpath - the output directory of the module being developed -
     * is not scanned at all and therefore stays {@link Kind#UNCLASSIFIED}, unless the scan was handed it as a
     * candidate, in which case it is the output of a module built against the current environment.
     */
    static Kind classify(@Nullable DiscoveredMod discovered) {
        if (discovered == null) {
            return Kind.UNCLASSIFIED;
        }
        if (!isMod(discovered)) {
            return Kind.NON_MOD;
        }
        return discovered.modernMetadata() ? Kind.MODERN : Kind.LEGACY;
    }

    /**
     * Whether the scan found anything that makes this source a mod: a mod id, mixin declarations (in the manifest or in
     * {@code mcmod.info}), a coremod or a tweaker.
     */
    private static boolean isMod(DiscoveredMod discovered) {
        return !discovered.modIds().isEmpty()
                || discovered.declaresMixins()
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
