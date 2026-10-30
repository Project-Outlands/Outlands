package net.minecraftforge.fml.common;

import java.util.Map;

import javax.annotation.Nullable;

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
 * <p>{@code modLanguageVersion} is recorded here but has nothing to compare against yet: provider version ranges
 * ({@code jar.providerVersions}) arrive with the provider declaration, and until then the field cannot be enforced. It
 * is still carried through so that a declaration written today keeps its meaning once enforcement exists - see
 * {@link #warnAboutUnenforcedVersion}.
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
     * Reports a declared {@code modLanguageVersion} as recorded-but-not-enforced.
     *
     * <p>The alternative is to drop the field on the floor, which is worse in both directions: an author would not learn
     * that the constraint is inert, and a user hitting the version mismatch the field exists to prevent would have no
     * hint that the declaration was even seen.
     */
    public void warnAboutUnenforcedVersion(String modId) {
        if (version != null) {
            FMLLog.log.warn("Mod {} (language {}) declares modLanguageVersion \"{}\", which is recorded but not enforced"
                    + " yet: no provider declares the versions it supports, so there is nothing to compare against.",
                    modId, language, version);
        }
    }

    @Override
    public String toString() {
        return "ModLanguageDeclaration[language=" + language
                + (adapterClass == null ? "" : ", adapter=" + adapterClass)
                + (version == null ? "" : ", version=" + version) + "]";
    }
}
