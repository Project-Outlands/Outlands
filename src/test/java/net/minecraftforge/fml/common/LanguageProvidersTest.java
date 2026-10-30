package net.minecraftforge.fml.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the collection and lazy resolution of the providers jars declare in the {@code jar} section.
 *
 * <p>Exercised on a fresh table rather than through {@link LanguageAdapterRegistry}, whose state is static and shared
 * with every other test in the JVM. That also lets the resolution path be driven with the test class loader, which is
 * the part that would otherwise need a running loader to reach.
 */
class LanguageProvidersTest {

    private static final String JAR_A = "/mods/a.jar";
    private static final String JAR_B = "/mods/b.jar";

    @Test
    void aDeclarationIsDataUntilSomethingResolvesIt() {
        LanguageProviders providers = new LanguageProviders();
        StubLanguageAdapter.instantiations = 0;

        providers.declare("scala", StubLanguageAdapter.class.getName(), JAR_A);

        assertEquals(0, StubLanguageAdapter.instantiations, "declaring must not run the provider's code");
        LanguageProviders.Declaration declaration = providers.declarationFor("scala");
        assertNotNull(declaration);
        assertEquals(StubLanguageAdapter.class.getName(), declaration.className());
        assertEquals(JAR_A, declaration.jarPath());
    }

    @Test
    void undeclaredLanguageHasNoDeclaration() {
        assertNull(new LanguageProviders().declarationFor("scala"));
    }

    @Test
    void resolvingBuildsOneSharedInstance() {
        LanguageProviders providers = new LanguageProviders();
        providers.declare("scala", StubLanguageAdapter.class.getName(), JAR_A);
        StubLanguageAdapter.instantiations = 0;

        ILanguageAdapter first = providers.resolve(providers.declarationFor("scala"), getClass().getClassLoader());
        ILanguageAdapter second = providers.resolve(providers.declarationFor("scala"), getClass().getClassLoader());

        assertTrue(first instanceof StubLanguageAdapter);
        // One instance per language, shared: the old registered path shared one while the adapter-by-class path built a
        // new one per mod, which forced every adapter to be stateless without saying so.
        assertSame(first, second);
        assertEquals(1, StubLanguageAdapter.instantiations);
    }

    @Test
    void twoJarsClaimingOneLanguageFailAndBothPathsAreReported() {
        LanguageProviders providers = new LanguageProviders();
        providers.declare("scala", "com.example.One", JAR_A);

        LoaderException failure = assertThrows(LoaderException.class,
                () -> providers.declare("scala", "com.example.Two", JAR_B));

        String message = failure.getMessage();
        assertTrue(message.contains("scala"), message);
        assertTrue(message.contains(JAR_A), message);
        assertTrue(message.contains(JAR_B), message);
        assertTrue(message.contains("com.example.One"), message);
        assertTrue(message.contains("com.example.Two"), message);
    }

    @Test
    void differentLanguagesDoNotCollide() {
        LanguageProviders providers = new LanguageProviders();
        providers.declare("scala", "com.example.Scala", JAR_A);
        providers.declare("kotlin", "com.example.Kotlin", JAR_B);

        assertNotNull(providers.declarationFor("scala"));
        assertNotNull(providers.declarationFor("kotlin"));
    }

    @Test
    void aProviderThatDoesNotImplementTheInterfaceFailsBeforeAnyInstanceIsBuilt() {
        // The alternative is a ClassCastException at the first mod construction, which names neither the jar nor the
        // language that asked for it.
        LanguageProviders providers = new LanguageProviders();
        providers.declare("scala", String.class.getName(), JAR_A);

        LoaderException failure = assertThrows(LoaderException.class,
                () -> providers.resolve(providers.declarationFor("scala"), getClass().getClassLoader()));

        assertTrue(failure.getMessage().contains(String.class.getName()), failure.getMessage());
        assertTrue(failure.getMessage().contains(JAR_A), failure.getMessage());
        assertTrue(failure.getMessage().contains("ILanguageAdapter"), failure.getMessage());
    }

    @Test
    void aMissingProviderClassFailsAndNamesTheJar() {
        LanguageProviders providers = new LanguageProviders();
        providers.declare("scala", "com.example.NotThere", JAR_A);

        LoaderException failure = assertThrows(LoaderException.class,
                () -> providers.resolve(providers.declarationFor("scala"), getClass().getClassLoader()));

        assertTrue(failure.getMessage().contains("com.example.NotThere"), failure.getMessage());
        assertTrue(failure.getMessage().contains(JAR_A), failure.getMessage());
    }

    @Test
    void describeListsDeclaredProvidersForErrorMessages() {
        LanguageProviders providers = new LanguageProviders();
        assertEquals("    - (none)", providers.describe());

        providers.declare("scala", "com.example.Scala", JAR_A);
        assertEquals("    - scala (from " + JAR_A + ")", providers.describe());
    }
}
