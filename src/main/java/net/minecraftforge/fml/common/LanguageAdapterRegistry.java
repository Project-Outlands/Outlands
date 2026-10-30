package net.minecraftforge.fml.common;

import com.cleanroommc.loader.Registry;

import javax.annotation.Nullable;

/**
 * Maps a {@code modLanguage} name to the adapter that knows how to construct that language's mod instances.
 *
 * <p>Writable during discovery only: mods that ship an adapter register it from a coremod, before any container is
 * built, and the registry is frozen once discovery ends. A late registration is a bug in the registrant (it would be
 * invisible to every mod already resolved), so it fails loudly instead of being dropped.
 *
 * <p>A language nobody registered is an error, not a fallback. Until this was changed, the lookup returned the Java
 * adapter for any unknown name, so {@code modLanguage="scala"} without a Scala provider was constructed reflectively as
 * if it were Java: the mod either worked by accident or failed in {@code getConstructor()} with a message about a
 * missing no-arg constructor, two frames away from the actual cause.
 */
public final class LanguageAdapterRegistry {

    private static final Registry<ILanguageAdapter> ADAPTERS =
            new Registry<>("language adapters", ILanguageAdapter.class);

    /** The adapter used for {@code java}. */
    private static final ILanguageAdapter JAVA = new ILanguageAdapter.JavaAdapter();

    static {
        registerLanguageAdapter("java", JAVA);
    }

    private LanguageAdapterRegistry() {
    }

    /**
     * Registers an adapter for {@code language}.
     *
     * @throws IllegalArgumentException if the id is blank, the adapter is not an {@link ILanguageAdapter}, or the
     *                                  language already has one - two jars claiming the same language cannot both be
     *                                  honoured, and silently keeping the first hides the conflict
     * @throws IllegalStateException    if called after discovery ended
     */
    public static void registerLanguageAdapter(String language, ILanguageAdapter languageAdapter) {
        ADAPTERS.register(language, languageAdapter);
        FMLLog.log.debug("Registering language adapter {} for language {}.", languageAdapter.getClass().getName(), language);
    }

    /**
     * Resolves {@code language} to its adapter.
     *
     * @param language   the declared language; {@code null} or blank means {@code java}, which is what a mod that
     *                   declares nothing gets
     * @param modId      the mod being resolved, for the error message; may be {@code null} for a direct API call
     * @param sourceName the jar the mod came from, for the error message; may be {@code null} for a direct API call
     * @throws LoaderException if no adapter is registered for {@code language}
     */
    public static ILanguageAdapter getAdapterFor(@Nullable String language, @Nullable String modId,
                                                 @Nullable String sourceName) {
        String id = language == null || language.isBlank()
                ? ModLanguageDeclaration.DEFAULT_LANGUAGE
                : language.trim();
        int slot = ADAPTERS.slotOf(id);
        if (slot != -1) {
            return ADAPTERS.get(slot);
        }
        throw new LoaderException(describeUnknownLanguage(id, modId, sourceName));
    }

    /**
     * @return the adapter registered for {@code language}
     * @throws LoaderException if no adapter is registered for {@code language}, or it is unknown
     */
    public static ILanguageAdapter getAdapterFor(@Nullable String language) {
        return getAdapterFor(language, null, null);
    }

    /**
     * Builds the message for an unresolvable language.
     *
     * <p>Three facts have to be in it, because each one alone leaves the reader guessing: which language was asked for,
     * which languages exist, and who was supposed to supply the missing one.
     */
    private static String describeUnknownLanguage(String language, @Nullable String modId, @Nullable String sourceName) {
        StringBuilder message = new StringBuilder();
        if (modId == null) {
            message.append("No language adapter is registered for modLanguage '").append(language).append("'.\n");
        } else {
            message.append("Mod '").append(modId).append("' (").append(sourceName).append(") declares modLanguage '")
                    .append(language).append("', but no adapter is registered for it.\n");
        }
        message.append("  Known languages: ").append(ADAPTERS.describeContents()).append('\n');
        message.append("  No jar in this instance declares that it provides '").append(language).append("'.\n");
        message.append("  Fix: install a jar that provides it, or set modLanguageAdapter to the full class name of an ")
                .append(ILanguageAdapter.class.getSimpleName())
                .append(", or set modLanguage to \"").append(ModLanguageDeclaration.DEFAULT_LANGUAGE)
                .append("\" if the entry class is a plain Java-style class.");
        return message.toString();
    }

    /** Closes the registry. Called once discovery has built every container. */
    public static void freeze() {
        ADAPTERS.freeze();
    }
}
