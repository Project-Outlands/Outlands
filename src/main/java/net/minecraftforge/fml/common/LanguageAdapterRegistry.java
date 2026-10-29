package net.minecraftforge.fml.common;

import com.cleanroommc.loader.Registry;

/**
 * Maps a {@code modLanguage} name to the adapter that knows how to construct that language's mod instances.
 *
 * <p>Writable during discovery only: mods that ship an adapter register it from a coremod, before any container is
 * built, and the registry is frozen once discovery ends. A late registration is a bug in the registrant (it would be
 * invisible to every mod already resolved), so it fails loudly instead of being dropped.
 *
 * <p>The unknown-language fallback here is still the historical one - see the note on {@link #getAdapterFor}.
 */
public final class LanguageAdapterRegistry {

    private static final Registry<ILanguageAdapter> ADAPTERS =
            new Registry<>("language adapters", ILanguageAdapter.class);

    /** The adapter used for {@code java} and, until it is removed, for languages nobody provides. */
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
     * @return the adapter registered for {@code language}
     *
     * <p>Falls back to the Java adapter for unknown languages. That is the historical behaviour and it is wrong - it
     * turns "nobody provides this language" into a reflective-construction failure several frames later - but it is
     * changed together with the language metadata work rather than here, so this change stays behaviour preserving.
     */
    public static ILanguageAdapter getAdapterFor(String language) {
        int slot = ADAPTERS.slotOf(language);
        if (slot != -1) {
            return ADAPTERS.get(slot);
        }
        return JAVA;
    }

    /** Closes the registry. Called once discovery has built every container. */
    public static void freeze() {
        ADAPTERS.freeze();
    }
}
