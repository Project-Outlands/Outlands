package net.minecraftforge.fml.common;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

/**
 * What the jars of this instance say about the languages they provide.
 *
 * <p>Pure declarations: a language name, the class that would serve it, and the jar that claims it. Nothing here is
 * loaded or instantiated, and that is deliberate - jars are scanned before any coremod has been constructed, so loading
 * a class at that point would skip whatever transformers the coremods were going to install. A declared provider is
 * therefore turned into an adapter only when something asks for its language, and that resolution goes through
 * {@link LanguageAdapterRegistry#registerLanguageAdapter} like every other adapter.
 *
 * <p>Kept separate from the registry so that "what was declared" and "what can be used" stay distinguishable: the error
 * message for a language nobody provides has to be able to list declarations that exist without implying they work yet.
 */
final class LanguageProviderDeclarations {

    /** One jar's claim to provide a language, and which language versions that provider can serve. */
    record Declaration(String language, String className, String jarPath, List<String> versions) {
    }

    private final Map<String, Declaration> declarations = new LinkedHashMap<>();

    /**
     * Records that {@code jarPath} provides {@code language}.
     *
     * @param versions the language versions this provider can serve, empty when it does not say
     * @throws LoaderException if another jar already claimed the same language
     */
    void declare(String language, String className, String jarPath, List<String> versions) {
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
        declarations.put(language, new Declaration(language, className, jarPath, List.copyOf(versions)));
    }

    /** @return the declaration for {@code language}, or {@code null} when no jar claims it */
    @Nullable
    Declaration declarationFor(String language) {
        return declarations.get(language);
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
