package net.minecraftforge.fml.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the one collision the loader can still see: a jar claiming a language the loader itself provides.
 *
 * <p>Rejected before anything is stored, so the registry's state is unchanged for the other tests in this JVM.
 */
class LanguageProviderConflictTest {

    @Test
    void aJarCannotRedeclareTheBuiltInJavaLanguage() {
        // "java" is registered by the registry itself. Accepting a replacement would change how every plain mod in the
        // instance is constructed, decided by whichever jar happened to ship a provider.
        LoaderException failure = assertThrows(LoaderException.class,
                () -> LanguageAdapterRegistry.declareProviders(Declarations.providing("java", "com.example.CustomJava"), "/mods/x.jar"));

        String message = failure.getMessage();
        assertTrue(message.contains("/mods/x.jar"), message);
        assertTrue(message.contains("java"), message);
        assertTrue(message.contains("already registered"), message);
    }
}
