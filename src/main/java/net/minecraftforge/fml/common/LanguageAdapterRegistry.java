package net.minecraftforge.fml.common;

import java.util.Map;

import com.cleanroommc.loader.Registry;

import javax.annotation.Nullable;

/**
 * Maps a {@code modLanguage} name to the adapter that knows how to construct that language's mod instances.
 *
 * <p>One registry, one way in: {@link #registerLanguageAdapter}. The loader's own adapters (today only {@code java}) use it
 * directly; a language a jar provides is first declared ({@link #declareProviders}) and then registered the same way when
 * something asks for it, which is also when it is instantiated.
 *
 * <p>Declaring is the only way a jar can provide a language. There is deliberately no public method that hands over a
 * live adapter instance: the claim "this jar provides scala" belongs with the jar, where it is discoverable, travels
 * under the same signature, and can be named in an error message. An instance handed over from code makes the same claim
 * in a place nobody can find.
 *
 * <p>Declarations stay data until they are used, because jars are scanned before any coremod is constructed: a provider
 * loaded at scan time would be loaded before the transformers that were going to apply to it exist. Resolution therefore
 * happens at the first lookup, which is still during discovery and therefore still before {@link #freeze()}.
 *
 * <p>A language nobody provides is an error, not a fallback. Until this was changed, the lookup returned the Java
 * adapter for any unknown name, so {@code modLanguage="scala"} without a Scala provider was constructed reflectively as
 * if it were Java: the mod either worked by accident or failed in {@code getConstructor()} with a message about a
 * missing no-arg constructor, two frames away from the actual cause.
 */
public final class LanguageAdapterRegistry {

    private static final Registry<ILanguageAdapter> ADAPTERS =
            new Registry<>("language adapters", ILanguageAdapter.class);

    /** The adapter used for {@code java}. */
    private static final ILanguageAdapter JAVA = new ILanguageAdapter.JavaAdapter();

    /** Providers declared by jars. Immutable data until a language is actually asked for. */
    private static final LanguageProviderDeclarations DECLARED = new LanguageProviderDeclarations();

    static {
        registerLanguageAdapter("java", JAVA);
    }

    private LanguageAdapterRegistry() {
    }

    /**
     * Registers an adapter for {@code language}. The single way in.
     *
     * <p>Package-private on purpose. A jar provides a language by declaring it in {@code mcmod.info}
     * ({@link #declareProviders}); that declaration is turned into a registration here, at the first lookup. Adding a
     * public entry point would restore a second declaration site - and with it a second set of conflict rules against the
     * declarations - for a claim that already has a home.
     *
     * @throws IllegalArgumentException if the id is blank, the adapter is not an {@link ILanguageAdapter}, or the
     *                                  language already has one
     * @throws IllegalStateException    if called after discovery ended
     */
    static void registerLanguageAdapter(String language, ILanguageAdapter languageAdapter) {
        ADAPTERS.register(language, languageAdapter);
        FMLLog.log.debug("Registering language adapter {} for language {}.", languageAdapter.getClass().getName(), language);
    }

    /**
     * Records the providers a jar declares in its {@code jar} section.
     *
     * <p>Called while the jar is scanned, where two things must not happen: instantiating the provider (jars are scanned
     * before any coremod exists, so the class would be loaded before the transformers meant for it) and accepting a
     * second claim to the same language. Both are handled here, the first by only recording the class name.
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
        return getAdapterFor(language, modId, sourceName, Loader.instance().getModClassLoader());
    }

    /**
     * The class loader is a parameter rather than always {@code Loader.instance()}'s so that the declared-provider path
     * can be driven directly, which is the only part that needs one.
     */
    static ILanguageAdapter getAdapterFor(@Nullable String language, @Nullable String modId, @Nullable String sourceName,
                                         ClassLoader classLoader) {
        String id = language == null || language.isBlank()
                ? ModLanguageDeclaration.DEFAULT_LANGUAGE
                : language.trim();
        int slot = ADAPTERS.slotOf(id);
        if (slot != -1) {
            return ADAPTERS.get(slot);
        }
        LanguageProviderDeclarations.Declaration declared = DECLARED.declarationFor(id);
        if (declared != null) {
            return resolveDeclared(declared, classLoader);
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
     * Turns a declaration into a registered adapter, at the first lookup.
     *
     * <p>Deferred to here rather than done while the jar was scanned: scanning happens before any coremod is
     * constructed, so a class loaded then would never see the transformers that were going to apply to it. This runs
     * during discovery - containers resolve their language when their metadata is bound - so the registration still
     * lands before {@link #freeze()}, and every later lookup finds it in the registry instead of resolving again.
     */
    private static ILanguageAdapter resolveDeclared(LanguageProviderDeclarations.Declaration declaration,
                                                   ClassLoader classLoader) {
        Class<?> adapterType;
        try {
            adapterType = Class.forName(declaration.className(), true, classLoader);
        } catch (ClassNotFoundException | LinkageError e) {
            throw new LoaderException("The jar " + declaration.jarPath() + " declares a language provider for '"
                    + declaration.language() + "', but its class " + declaration.className()
                    + " cannot be loaded. Check the class name and that the class is in that jar.", e);
        }

        // Checked before instantiation, not left to a cast at the first construction call: a wrong class is a wrong
        // declaration, and the mod that asked for the language has no way to know why its constructor is missing.
        if (!ILanguageAdapter.class.isAssignableFrom(adapterType)) {
            throw new LoaderException("The jar " + declaration.jarPath() + " declares " + declaration.className()
                    + " as the language provider for '" + declaration.language() + "', but it does not implement "
                    + ILanguageAdapter.class.getName() + ".");
        }

        ILanguageAdapter adapter;
        try {
            adapter = (ILanguageAdapter) adapterType.getConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new LoaderException("The language provider " + declaration.className() + " declared by "
                    + declaration.jarPath() + " for '" + declaration.language()
                    + "' has no public no-argument constructor, so it cannot be instantiated.", e);
        }
        registerLanguageAdapter(declaration.language(), adapter);
        return adapter;
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
