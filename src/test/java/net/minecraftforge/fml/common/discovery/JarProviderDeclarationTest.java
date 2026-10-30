package net.minecraftforge.fml.common.discovery;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import net.minecraftforge.fml.common.LoaderException;
import net.minecraftforge.fml.common.MetadataCollection;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the {@code providers} key of the {@code jar} section: the first jar-level declaration that describes code
 * rather than data.
 *
 * <p>The parsing rules worth pinning are the ones that decide whether a jar's claim is visible. A section read as an
 * empty map would make the jar look like it declared nothing, and the mod written in that language would then fail with
 * "no provider is registered" while its provider jar visibly declares one - the least debuggable outcome there is.
 */
class JarProviderDeclarationTest {

    /** The mandatory part of the section, so each case below only states what it is actually about. */
    private static final String MANDATORY = "\"schemaVersion\":1,\"license\":\"MIT\"";

    private static JarDeclaration declaration(String jarSectionBody) {
        String json = "{\"jar\":{" + jarSectionBody + "}}";
        return MetadataCollection
                .from(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), "test.jar")
                .getJarDeclaration();
    }

    private static Map<String, String> providers(String extra) {
        JarDeclaration declaration = declaration(MANDATORY + extra);
        assertNotNull(declaration);
        return declaration.providers();
    }

    @Test
    void providersAreParsedByLanguageName() {
        assertEquals(Map.of("scala", "com.example.ScalaLanguageAdapter"),
                providers(",\"providers\":{\"scala\":\"com.example.ScalaLanguageAdapter\"}"));
    }

    @Test
    void severalProvidersCanBeDeclared() {
        Map<String, String> providers = providers(
                ",\"providers\":{\"scala\":\"com.example.Scala\",\"kotlin\":\"com.example.Kotlin\"}");

        assertEquals(2, providers.size());
        assertEquals("com.example.Scala", providers.get("scala"));
        assertEquals("com.example.Kotlin", providers.get("kotlin"));
    }

    @Test
    void noProvidersKeyMeansNoProviders() {
        assertTrue(providers("").isEmpty());
        assertTrue(providers(",\"providers\":{}").isEmpty());
    }

    @Test
    void providersAreNotReportedAsUnknownKeys() {
        // The key is part of the schema now; a jar using it must not also be told the loader does not understand it.
        JarDeclaration declaration = declaration(MANDATORY + ",\"providers\":{\"scala\":\"com.example.Scala\"}");

        assertNotNull(declaration);
        assertTrue(declaration.unknownKeys().isEmpty(), declaration.unknownKeys().toString());
    }

    @Test
    void aMalformedProvidersValueIsRejected() {
        // Half-reading this would leave the provider undeclared with no sign that it was written at all.
        assertThrows(LoaderException.class, () -> providers(",\"providers\":[\"scala\"]"));
        assertThrows(LoaderException.class, () -> providers(",\"providers\":{\"scala\":\"\"}"));
        assertThrows(LoaderException.class, () -> providers(",\"providers\":{\"scala\":null}"));
        assertThrows(LoaderException.class, () -> providers(",\"providers\":{\"\":\"com.example.Scala\"}"));
    }

    @Test
    void theRejectionNamesTheJarAndTheLanguage() {
        // It happens while a jar is being scanned, where the filename is the only thing the reader can act on.
        LoaderException failure = assertThrows(LoaderException.class,
                () -> providers(",\"providers\":{\"scala\":\"\"}"));

        assertTrue(failure.getMessage().contains("test.jar"), failure.getMessage());
        assertTrue(failure.getMessage().contains("scala"), failure.getMessage());
    }

    @Test
    void providerVersionsAreParsedPerLanguage() {
        JarDeclaration declaration = declaration(MANDATORY
                + ",\"providers\":{\"scala\":\"com.example.Scala\",\"kotlin\":\"com.example.Kotlin\"}"
                + ",\"providerVersions\":{\"scala\":[\"2.12\",\"2.13\"]}");

        assertNotNull(declaration);
        assertEquals(Map.of("scala", java.util.List.of("2.12", "2.13")), declaration.providerVersions());
    }

    @Test
    void providerVersionsWithoutProvidersKeyIsNoDeclarations() {
        assertTrue(declaration(MANDATORY).providerVersions().isEmpty());
    }

    @Test
    void providerVersionsForALanguageTheJarDoesNotProvideIsRejected() {
        // Such a list describes a provider that does not exist, so it would silently never be consulted.
        LoaderException failure = assertThrows(LoaderException.class,
                () -> declaration(MANDATORY + ",\"providerVersions\":{\"scala\":[\"2.13\"]}"));

        assertTrue(failure.getMessage().contains("scala"), failure.getMessage());
        assertTrue(failure.getMessage().contains("no provider"), failure.getMessage());
    }

    @Test
    void malformedProviderVersionsAreRejected() {
        String providers = ",\"providers\":{\"scala\":\"com.example.Scala\"}";
        assertThrows(LoaderException.class,
                () -> declaration(MANDATORY + providers + ",\"providerVersions\":{\"scala\":\"2.13\"}"));
        assertThrows(LoaderException.class,
                () -> declaration(MANDATORY + providers + ",\"providerVersions\":{\"scala\":[]}"));
        assertThrows(LoaderException.class,
                () -> declaration(MANDATORY + providers + ",\"providerVersions\":{\"scala\":[\"  \"]}"));
    }

    @Test
    void providerVersionsAreNotReportedAsUnknownKeys() {
        JarDeclaration declaration = declaration(MANDATORY
                + ",\"providers\":{\"scala\":\"com.example.Scala\"}"
                + ",\"providerVersions\":{\"scala\":[\"2.13\"]}");

        assertNotNull(declaration);
        assertTrue(declaration.unknownKeys().isEmpty(), declaration.unknownKeys().toString());
    }
}
