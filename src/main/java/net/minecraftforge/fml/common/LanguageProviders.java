package net.minecraftforge.fml.common;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * The language providers declared by the jars of this instance, and the instances built from them.
 *
 * <p>A provider declaration is data, not code: reading it costs nothing and runs nothing. That matters because a
 * provider is executed at the earliest possible moment (it has to exist before the first mod written in its language is
 * constructed), so a provider nobody asks for must never be instantiated. Resolution therefore happens on first use and
 * is cached - one instance per language, shared by every mod that asks for it, which also removes the old asymmetry
 * where an adapter named by class ({@code modLanguageAdapter}) got a fresh instance per mod while a registered one was
 * shared.
 *
 * <p>Separate from {@link LanguageAdapterRegistry} on purpose: that registry freezes when discovery ends, whereas a
 * declared provider may legitimately be resolved later (a mod constructed after the freeze still needs its adapter).
 * Two tables with two lifecycles is more honest than one table with an exception to its own rule.
 */
final class LanguageProviders {

    /** One jar's claim to provide a language. */
    record Declaration(String language, String className, String jarPath) {
    }

    private final Map<String, Declaration> declarations = new LinkedHashMap<>();
    private final Map<String, ILanguageAdapter> instances = new LinkedHashMap<>();

    /**
     * Records that {@code jarPath} provides {@code language}.
     *
     * @throws LoaderException if another jar already claimed the same language
     */
    void declare(String language, String className, String jarPath) {
        Declaration existing = declarations.get(language);
        if (existing != null) {
            // Two jars cannot both be the adapter for one language, and keeping whichever was scanned first would make
            // the other declaration inert with nothing to notice it by. Both paths, so the duplicate can be found.
            throw new LoaderException("Two jars declare a language provider for '" + language + "':\n"
                    + "    " + existing.jarPath() + " declares " + existing.className() + "\n"
                    + "    " + jarPath + " declares " + className + "\n"
                    + "Only one jar can provide a language, so this has to be resolved before the game can start."
                    + " Remove the provider from one of them.");
        }
        declarations.put(language, new Declaration(language, className, jarPath));
    }

    /** @return the declaration for {@code language}, or {@code null} when no jar claims it */
    @Nullable
    Declaration declarationFor(String language) {
        return declarations.get(language);
    }

    /**
     * Builds (once) and returns the adapter described by {@code declaration}.
     *
     * @throws LoaderException if the class is missing, is not an {@link ILanguageAdapter}, or cannot be constructed
     */
    ILanguageAdapter resolve(Declaration declaration, ClassLoader classLoader) {
        ILanguageAdapter cached = instances.get(declaration.language());
        if (cached != null) {
            return cached;
        }

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
        instances.put(declaration.language(), adapter);
        FMLLog.log.debug("Using language provider {} from {} for language {}", declaration.className(),
                declaration.jarPath(), declaration.language());
        return adapter;
    }

    /**
     * @return the declared providers, one indented line each, for an error message that has to answer "who was
     *         supposed to supply this"
     */
    String describe() {
        if (declarations.isEmpty()) {
            return "    - (none)";
        }
        StringBuilder result = new StringBuilder();
        for (Declaration declaration : Collections.unmodifiableCollection(declarations.values())) {
            if (result.length() > 0) {
                result.append('\n');
            }
            result.append("    - ").append(declaration.language()).append(" (from ")
                    .append(declaration.jarPath()).append(')');
        }
        return result.toString();
    }
}
