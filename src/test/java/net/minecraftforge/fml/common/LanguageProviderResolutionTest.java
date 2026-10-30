package net.minecraftforge.fml.common;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the path from a declaration to a usable adapter: the registry resolves it at first lookup and registers it
 * through the same entry point the loader's own adapters use.
 *
 * <p>Every case uses its own language name. The registry is static, so a test that reused one would depend on which test
 * ran first - a failure that only shows up on some machines.
 */
class LanguageProviderResolutionTest {

    private static final ClassLoader LOADER = LanguageProviderResolutionTest.class.getClassLoader();

    private static ILanguageAdapter resolve(String language) {
        return LanguageAdapterRegistry.getAdapterFor(language, "testmod", "test.jar", LOADER);
    }

    @Test
    void aDeclarationBecomesAnAdapterOnFirstUseAndIsThenShared() {
        LanguageAdapterRegistry.declareProviders(Map.of("testscale", StubLanguageAdapter.class.getName()), "/mods/lib.jar");
        StubLanguageAdapter.instantiations = 0;

        ILanguageAdapter first = resolve("testscale");
        ILanguageAdapter second = resolve("testscale");

        assertTrue(first instanceof StubLanguageAdapter);
        // Second lookup finds the registration rather than resolving again: the registry is the cache.
        assertSame(first, second);
        assertTrue(StubLanguageAdapter.instantiations <= 1,
                "expected one instantiation, got " + StubLanguageAdapter.instantiations);
    }

    @Test
    void aProviderThatDoesNotImplementTheInterfaceFailsBeforeAnyInstanceIsBuilt() {
        // The alternative is a ClassCastException at the first mod construction, which names neither the jar nor the
        // language that asked for it.
        LanguageAdapterRegistry.declareProviders(Map.of("testnotadapter", String.class.getName()), "/mods/lib.jar");

        LoaderException failure = assertThrows(LoaderException.class, () -> resolve("testnotadapter"));

        assertTrue(failure.getMessage().contains(String.class.getName()), failure.getMessage());
        assertTrue(failure.getMessage().contains("/mods/lib.jar"), failure.getMessage());
        assertTrue(failure.getMessage().contains("ILanguageAdapter"), failure.getMessage());
    }

    @Test
    void aMissingProviderClassFailsAndNamesTheJar() {
        LanguageAdapterRegistry.declareProviders(Map.of("testmissing", "com.example.NotThere"), "/mods/lib.jar");

        LoaderException failure = assertThrows(LoaderException.class, () -> resolve("testmissing"));

        assertTrue(failure.getMessage().contains("com.example.NotThere"), failure.getMessage());
        assertTrue(failure.getMessage().contains("/mods/lib.jar"), failure.getMessage());
    }

    @Test
    void anUndeclaredLanguageStillFailsAndListsWhatIsDeclared() {
        // The message has to distinguish "nothing provides this" from "something declared it and could not be used".
        // The declaration is made here rather than relied on from another test: the registry is static.
        LanguageAdapterRegistry.declareProviders(Map.of("testlisted", StubLanguageAdapter.class.getName()), "/mods/lib.jar");

        LoaderException failure = assertThrows(LoaderException.class, () -> resolve("testnothing"));

        assertTrue(failure.getMessage().contains("Known languages: java"), failure.getMessage());
        assertTrue(failure.getMessage().contains("Providers declared in this instance"), failure.getMessage());
        assertTrue(failure.getMessage().contains("testlisted"), failure.getMessage());
    }
}
