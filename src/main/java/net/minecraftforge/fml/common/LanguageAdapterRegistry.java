package net.minecraftforge.fml.common;

import java.util.Map;

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
 *
 * <p>Two things can satisfy a language lookup: an adapter registered from code (a coremod calling
 * {@link #registerLanguageAdapter}) and a provider a jar declares in its {@code mcmod.info} {@code jar} section
 * ({@link #declareProviders}). The first is instantiated by its registrant and frozen with the registry; the second is
 * only data until something asks for it. Either way the language has exactly one provider, and a jar declaring one that
 * already exists is rejected rather than ignored.
 */
public final class LanguageAdapterRegistry {

    private static final Registry<ILanguageAdapter> ADAPTERS =
            new Registry<>("language adapters", ILanguageAdapter.class);

    /** The adapter used for {@code java}. */
    private static final ILanguageAdapter JAVA = new ILanguageAdapter.JavaAdapter();

    /** Providers declared by jars. Immutable data until a language is actually asked for. */
    private static final LanguageProviders DECLARED = new LanguageProviders();

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
     * @throws LoaderException          if a jar already declared a provider for {@code language}; that declaration would
     *                                  become inert, so both sides are reported
     * @throws IllegalStateException    if called after discovery ended
     */
    public static void registerLanguageAdapter(String language, ILanguageAdapter languageAdapter) {
        LanguageProviders.Declaration declared = DECLARED.declarationFor(language);
        if (declared != null) {
            throw new LoaderException("Cannot register a language adapter for '" + language + "': "
                    + declared.jarPath() + " already declares " + declared.className()
                    + " as its provider. Only one adapter can serve a language; remove one of the two.");
        }
        ADAPTERS.register(language, languageAdapter);
        FMLLog.log.debug("Registering language adapter {} for language {}.", languageAdapter.getClass().getName(), language);
    }

    /**
     * Records the providers a jar declares in its {@code jar} section.
     *
     * <p>Called while the jar is scanned, so a duplicate is reported before any mod has been constructed. Nothing is
     * instantiated here: a provider is code that runs before the mods that need it, so a provider nothing asks for should
     * never run at all.
     *
     * @param providers language name to the full name of an {@link ILanguageAdapter} implementation
     * @param jarPath   the jar making the claim, for conflict reports
     * @throws LoaderException if a language is already provided by a registered adapter or by another jar
     */
    public static void declareProviders(Map<String, String> providers, String jarPath) {
        for (Map.Entry<String, String> entry : providers.entrySet()) {
            String language = entry.getKey();
            int slot = ADAPTERS.slotOf(language);
            if (slot != -1) {
                // Covers "java" too: it is registered by this class, so a jar claiming it is claiming to replace the
                // adapter that reads plain Java entry classes. That would be a silent global change to every mod.
                throw new LoaderException("The jar " + jarPath + " declares a language provider for '" + language
                        + "', but an adapter for it is already registered (" + ADAPTERS.get(slot).getClass().getName()
                        + "). Only one adapter can serve a language; remove the declaration from the jar.");
            }
            DECLARED.declare(language, entry.getValue(), jarPath);
        }
    }

    /**
     * Resolves {@code language} to its adapter.
     *
     * @param language   the declared language; {@code null} or blank means {@code java}, which is what a mod that
     *                   declares nothing gets
     * @param modId      the mod being resolved, for the error message; may be {@code null} for a direct API call
     * @param sourceName the jar the mod came from, for the error message; may be {@code null} for a direct API call
     * @throws LoaderException if no adapter is registered for {@code language} and no jar declares one, or if the
     *                         declared provider cannot be used
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
        LanguageProviders.Declaration declared = DECLARED.declarationFor(id);
        if (declared != null) {
            return DECLARED.resolve(declared, Loader.instance().getModClassLoader());
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
        message.append("  Providers declared in this instance:\n").append(DECLARED.describe()).append('\n');
        message.append("  Fix: install a jar that declares jar.providers.").append(language)
                .append(", or set modLanguageAdapter to the full class name of an ")
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
