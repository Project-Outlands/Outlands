package net.minecraftforge.fml.common;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the two ways a language can end up with more than one provider: a jar declaring the built-in {@code java}, and
 * a declaration colliding with an adapter registered from code.
 *
 * <p>Exercised through the static registry because that is where the collision is detectable - it is the only place
 * that knows both tables. Every case here is rejected before anything is stored, so the registry's state is unchanged
 * for the other tests in this JVM.
 */
class LanguageProviderConflictTest {

    @Test
    void aJarCannotRedeclareTheBuiltInJavaLanguage() {
        // "java" is registered by the registry itself. Accepting a replacement would change how every plain mod in the
        // instance is constructed, decided by whichever jar happened to ship a provider.
        LoaderException failure = assertThrows(LoaderException.class,
                () -> LanguageAdapterRegistry.declareProviders(Map.of("java", "com.example.CustomJava"), "/mods/x.jar"));

        String message = failure.getMessage();
        assertTrue(message.contains("/mods/x.jar"), message);
        assertTrue(message.contains("java"), message);
        assertTrue(message.contains("already registered"), message);
    }

    @Test
    void aDeclaredProviderBlocksALaterRegistration() {
        // The declaration was read while scanning, the registration comes from a coremod; whichever arrives second has
        // to be told, because keeping the first silently makes the other's declaration inert.
        LanguageAdapterRegistry.declareProviders(Map.of("scalance", "com.example.ScalaLanguageAdapter"), "/mods/lib.jar");

        LoaderException failure = assertThrows(LoaderException.class,
                () -> LanguageAdapterRegistry.registerLanguageAdapter("scalance", new ILanguageAdapter.JavaAdapter()));

        String message = failure.getMessage();
        assertTrue(message.contains("scalance"), message);
        assertTrue(message.contains("/mods/lib.jar"), message);
        assertTrue(message.contains("com.example.ScalaLanguageAdapter"), message);
    }

    @Test
    void declaringTheSameLanguageTwiceIsRejectedWithBothJars() {
        // Reaching the shared collection through the public entry point: the second jar is the one that reports.
        LanguageAdapterRegistry.declareProviders(Map.of("scaladup", "com.example.One"), "/mods/first.jar");

        LoaderException failure = assertThrows(LoaderException.class,
                () -> LanguageAdapterRegistry.declareProviders(Map.of("scaladup", "com.example.Two"), "/mods/second.jar"));

        String message = failure.getMessage();
        assertTrue(message.contains("/mods/first.jar"), message);
        assertTrue(message.contains("/mods/second.jar"), message);
        assertTrue(message.contains("com.example.One"), message);
        assertTrue(message.contains("com.example.Two"), message);
    }
}
