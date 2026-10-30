package net.minecraftforge.fml.common;

import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraftforge.fml.common.versioning.ArtifactVersion;
import net.minecraftforge.fml.common.versioning.DefaultArtifactVersion;
import net.minecraftforge.fml.common.versioning.InvalidVersionSpecificationException;
import net.minecraftforge.fml.common.versioning.VersionRange;

/**
 * What a mod declares about the language its entry class is written in, after merging the two places that declaration
 * can come from.
 *
 * <p>Historically the declaration lived only in {@code @Mod}: {@code modLanguage} and {@code modLanguageAdapter} were
 * read from the annotation value map and nothing else could set them, so a jar that shipped {@code mcmod.info} had no
 * way to describe how it should be constructed. This class gives the pair a single merged form; the annotation stays
 * authoritative for mods that write it there, and the metadata keys are the same names because "two ways to spell the
 * same thing" is how the two places end up meaning different things.
 *
 * <p><b>Disagreement fails instead of being resolved.</b> Preferring one source silently would make the other one look
 * like it worked - an author edits {@code mcmod.info}, sees no change, and has nothing to search for. Since the loader
 * cannot know which of the two the author meant, it reports both and stops. A mod that only declares in one place, which
 * is every mod written before the metadata keys existed, never conflicts.
 *
 * <p>{@code modLanguageVersion} is checked against the versions the provider declares
 * ({@code jar.providerVersions}), which turns what used to be a {@code NoSuchMethodError} inside {@code preInit} into a
 * discovery-time failure that names both sides. A provider that declares no versions keeps working - see
 * {@link #checkVersion}.
 */
public final class ModLanguageDeclaration {

    /** The language of a mod that declares nothing. Matches {@code @Mod#modLanguage()}'s default. */
    public static final String DEFAULT_LANGUAGE = "java";

    /** Annotation key, also the metadata key: the language name that gets resolved to an adapter. */
    private static final String LANGUAGE = "modLanguage";
    /** Annotation key, also the metadata key: an {@link ILanguageAdapter} implementation, by class name. */
    private static final String ADAPTER = "modLanguageAdapter";
    /** Metadata-only key: a runtime version constraint for the language. */
    private static final String VERSION = "modLanguageVersion";

    private final String language;
    @Nullable
    private final String adapterClass;
    @Nullable
    private final String version;

    private ModLanguageDeclaration(String language, @Nullable String adapterClass, @Nullable String version) {
        this.language = language;
        this.adapterClass = adapterClass;
        this.version = version;
    }

    /**
     * Merges the annotation values and the metadata of one mod.
     *
     * @param annotation the {@code @Mod} values, as harvested from the class file; only explicitly written values are
     *                   present, so an absent key means "not declared" rather than "the default"
     * @param metadata   the mod's {@code mcmod.info} entry, or {@code null} when there is none
     * @param modId      the mod being described, for error messages
     * @param sourceName the jar the mod came from, for error messages
     * @throws LoaderException if the two sources declare the same key with different values
     */
    public static ModLanguageDeclaration of(@Nullable Map<String, Object> annotation, @Nullable ModMetadata metadata,
                                            String modId, String sourceName) {
        String language = merge(annotationValue(annotation, LANGUAGE), metadata == null ? null : metadata.modLanguage,
                LANGUAGE, modId, sourceName);
        String adapter = merge(annotationValue(annotation, ADAPTER), metadata == null ? null : metadata.modLanguageAdapter,
                ADAPTER, modId, sourceName);
        // The annotation has no counterpart for this one, so there is nothing to merge and nothing to disagree about.
        String version = metadata == null ? null : metadata.modLanguageVersion;

        String trimmedLanguage = blankToNull(language);
        return new ModLanguageDeclaration(trimmedLanguage == null ? DEFAULT_LANGUAGE : trimmedLanguage,
                blankToNull(adapter), blankToNull(version));
    }

    /**
     * A declaration carrying no explicit adapter, for callers that only need the default.
     */
    public static ModLanguageDeclaration defaults() {
        return new ModLanguageDeclaration(DEFAULT_LANGUAGE, null, null);
    }

    /**
     * @throws LoaderException if both sources declare {@code key} and the values differ
     */
    private static String merge(@Nullable String fromAnnotation, @Nullable String fromMetadata, String key,
                               String modId, String sourceName) {
        String annotationValue = blankToNull(fromAnnotation);
        String metadataValue = blankToNull(fromMetadata);
        if (annotationValue != null && metadataValue != null && !annotationValue.equals(metadataValue)) {
            throw new LoaderException("Cannot read the language of mod '" + modId + "' (" + sourceName + "): @Mod declares "
                    + key + "=\"" + annotationValue.trim() + "\" but its mcmod.info declares \"" + metadataValue.trim()
                    + "\". Declare it in one place only, or make the two agree - the loader will not silently pick one,"
                    + " because the other would then look like it took effect.");
        }
        return annotationValue != null ? annotationValue : metadataValue;
    }

    private static String annotationValue(@Nullable Map<String, Object> annotation, String key) {
        if (annotation == null) {
            return null;
        }
        Object value = annotation.get(key);
        // Both keys are Strings in the annotation, so a non-String value is not a language name or a class name.
        // Comparing its text still surfaces a disagreement rather than silently ignoring it.
        return value == null ? null : String.valueOf(value);
    }

    @Nullable
    private static String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** @return the declared language, {@code "java"} when nothing was declared */
    public String language() {
        return language;
    }

    /**
     * @return the class name of the adapter to use, or {@code null} when the language should be resolved to a
     *         registered adapter instead. An adapter class always wins over the language name: it is the older escape
     *         hatch, it is self-contained, and a mod that ships its own adapter must not need anyone else's provider.
     */
    @Nullable
    public String adapterClass() {
        return adapterClass;
    }

    /** @return the declared runtime version constraint, or {@code null} when none was declared */
    @Nullable
    public String version() {
        return version;
    }

    /**
     * Reports a declared {@code modLanguageVersion} as recorded-but-uncheckable.
     *
     * <p>The alternative is to drop the constraint on the floor, which is worse in both directions: an author would not
     * learn that it is inert, and a user hitting the runtime mismatch it exists to prevent would have no hint that the
     * declaration was ever read.
     */
    public void warnUncheckableVersion(String modId) {
        if (version != null) {
            FMLLog.log.warn("Mod {} (language {}) declares modLanguageVersion \"{}\", but nothing states which versions that"
                    + " language provider supports, so the constraint cannot be checked and is ignored.", modId, language, version);
        }
    }

    /**
     * Checks the declared {@code modLanguageVersion} against the versions the provider says it supports.
     *
     * <p>This exists because the failure it catches shows up at the worst possible time otherwise: a mod compiled against
     * Scala 2.13 running on a 2.11 provider does not fail at load, it fails inside {@code preInit} with
     * {@code NoSuchMethodError} or {@code AbstractMethodError}, which points at the mod rather than at the mismatch.
     *
     * <p>Silence is not a failure: a provider that does not say which versions it serves keeps working, and the author of
     * the mod gets a warning instead of a crash. Requiring the declaration would have invalidated every provider written
     * before it existed, for a check that is a convenience rather than a correctness requirement.
     *
     * @param supported the versions the provider declares, or {@code null} when it declares none
     * @throws LoaderException if the constraint is malformed, or no supported version satisfies it
     */
    public void checkVersion(String modId, String sourceName, @Nullable List<String> supported) {
        if (version == null) {
            return;
        }
        if (supported == null || supported.isEmpty()) {
            warnUncheckableVersion(modId);
            return;
        }

        VersionRange wanted;
        try {
            wanted = VersionRange.createFromVersionSpec(version);
        } catch (InvalidVersionSpecificationException e) {
            throw new LoaderException("Mod '" + modId + "' (" + sourceName + ") declares modLanguageVersion \"" + version
                    + "\", which is not a version range. Use Maven range syntax, for example \"[2.13,2.14)\".", e);
        }

        if (wanted != null && satisfiesAny(wanted, supported)) {
            return;
        }
        throw new LoaderException("Mod '" + modId + "' (" + sourceName + ") requires language " + language + " " + version
                + ", but the provider supports " + supported + ".\n"
                + "  Fix: install a provider jar that supports a version in that range, or change modLanguageVersion to one"
                + " of the supported versions.");
    }

    /**
     * @return whether any of {@code supported} falls inside {@code wanted}. One common version is enough: the provider's
     *         list says which versions it can serve, not which one is installed, so the question is whether the two
     *         descriptions overlap.
     */
    static boolean satisfiesAny(VersionRange wanted, List<String> supported) {
        // A bare version ("2.13", no brackets) ends up as the range's recommended version with no restrictions, and
        // VersionRange.containsVersion consults only the restrictions - so it answers "yes" to everything. Comparing
        // against the recommended version is what makes "2.13" mean exactly 2.13 instead of nothing at all.
        ArtifactVersion exact = wanted.getRecommendedVersion();
        for (String candidate : supported) {
            // Single-argument constructor on purpose: the (String, boolean) one means "unbounded", and an unbounded
            // version compares equal to everything, which would make an exclusive upper bound reject every candidate.
            DefaultArtifactVersion parsed = new DefaultArtifactVersion(candidate);
            if (exact != null ? parsed.compareTo(exact) == 0 : wanted.containsVersion(parsed)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return "ModLanguageDeclaration[language=" + language
                + (adapterClass == null ? "" : ", adapter=" + adapterClass)
                + (version == null ? "" : ", version=" + version) + "]";
    }
}
