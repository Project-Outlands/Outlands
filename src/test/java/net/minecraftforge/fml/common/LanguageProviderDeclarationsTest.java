package net.minecraftforge.fml.common;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the declarations themselves: what a jar claims, and what happens when two jars claim the same thing.
 *
 * <p>Exercised on a fresh instance rather than through {@link LanguageAdapterRegistry}, whose state is static and shared
 * with every other test in the JVM.
 *
 * <p>What is deliberately not here is anything about instantiation - a declaration holds no code. That a declaration
 * becomes a registered adapter belongs to the registry, which is tested separately.
 */
class LanguageProviderDeclarationsTest {

    private static final String JAR_A = "/mods/a.jar";
    private static final String JAR_B = "/mods/b.jar";

    @Test
    void aDeclarationIsJustRecorded() {
        LanguageProviderDeclarations declarations = new LanguageProviderDeclarations();
        declarations.declare("scala", StubLanguageAdapter.class.getName(), JAR_A, List.of());

        // Nothing was loaded, so the class has to still be uninstantiated for this to mean anything.
        assertEquals(0, StubLanguageAdapter.instantiations, "declaring must not run the provider's code");

        LanguageProviderDeclarations.Declaration declaration = declarations.declarationFor("scala");
        assertNotNull(declaration);
        assertEquals(StubLanguageAdapter.class.getName(), declaration.className());
        assertEquals(JAR_A, declaration.jarPath());
        assertEquals("scala", declaration.language());
    }

    @Test
    void undeclaredLanguageHasNoDeclaration() {
        assertNull(new LanguageProviderDeclarations().declarationFor("scala"));
    }

    @Test
    void twoJarsClaimingOneLanguageFailAndBothPathsAreReported() {
        LanguageProviderDeclarations declarations = new LanguageProviderDeclarations();
        declarations.declare("scala", "com.example.One", JAR_A, List.of());

        LoaderException failure = assertThrows(LoaderException.class,
                () -> declarations.declare("scala", "com.example.Two", JAR_B, List.of()));

        String message = failure.getMessage();
        assertTrue(message.contains("scala"), message);
        assertTrue(message.contains(JAR_A), message);
        assertTrue(message.contains(JAR_B), message);
        assertTrue(message.contains("com.example.One"), message);
        assertTrue(message.contains("com.example.Two"), message);
    }

    @Test
    void differentLanguagesDoNotCollide() {
        LanguageProviderDeclarations declarations = new LanguageProviderDeclarations();
        declarations.declare("scala", "com.example.Scala", JAR_A, List.of());
        declarations.declare("kotlin", "com.example.Kotlin", JAR_B, List.of());

        assertNotNull(declarations.declarationFor("scala"));
        assertNotNull(declarations.declarationFor("kotlin"));
    }

    @Test
    void describeListsDeclaredProvidersForErrorMessages() {
        LanguageProviderDeclarations declarations = new LanguageProviderDeclarations();
        assertEquals("    - (none)", declarations.describe());

        declarations.declare("scala", "com.example.Scala", JAR_A, List.of());
        assertEquals("    - scala (from " + JAR_A + ")", declarations.describe());
    }
}
