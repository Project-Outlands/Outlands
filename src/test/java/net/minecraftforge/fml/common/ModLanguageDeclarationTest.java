package net.minecraftforge.fml.common;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers how a mod's language declaration is merged from the two places it can be written: the {@code @Mod} annotation
 * and the {@code mcmod.info} entry.
 *
 * <p>The cases worth pinning are the ones where the two sources can disagree or be absent, because those decide whether
 * an author's edit has any effect. Before this, the metadata keys were read by nobody, so writing them looked correct
 * and did nothing - the exact failure mode {@code merge} refuses to reproduce.
 */
class ModLanguageDeclarationTest {

    private static Map<String, Object> annotation(String language, String adapter) {
        return Map.of("modLanguage", language, "modLanguageAdapter", adapter);
    }

    private static ModMetadata metadata(String language, String adapter, String version) {
        ModMetadata metadata = new ModMetadata();
        metadata.modLanguage = language;
        metadata.modLanguageAdapter = adapter;
        metadata.modLanguageVersion = version;
        return metadata;
    }

    private static ModMetadata emptyMetadata() {
        return new ModMetadata();
    }

    @Test
    void aModThatDeclaresNothingIsJava() {
        // Annotation defaults are not present in the harvested map (only explicitly written values are), so both
        // sources being silent is the common case, not an edge case.
        ModLanguageDeclaration declaration = ModLanguageDeclaration.of(Map.of("modid", "plain"), emptyMetadata(), "plain", "plain.jar");

        assertEquals("java", declaration.language());
        assertNull(declaration.adapterClass());
        assertNull(declaration.version());
    }

    @Test
    void metadataAloneIsEnough() {
        // The point of the whole change: a jar with no annotation-side declaration can still say how it is built.
        ModLanguageDeclaration declaration = ModLanguageDeclaration.of(Map.of("modid", "scaly"),
                metadata("scala", "", ""), "scaly", "scaly.jar");

        assertEquals("scala", declaration.language());
    }

    @Test
    void annotationAloneStillWorks() {
        ModLanguageDeclaration declaration = ModLanguageDeclaration.of(annotation("scala", ""), emptyMetadata(),
                "scaly", "scaly.jar");

        assertEquals("scala", declaration.language());
    }

    @Test
    void agreeingSourcesAreNotAConflict() {
        ModLanguageDeclaration declaration = ModLanguageDeclaration.of(annotation("scala", "com.example.Adapter"),
                metadata("scala", "com.example.Adapter", ""), "scaly", "scaly.jar");

        assertEquals("scala", declaration.language());
        assertEquals("com.example.Adapter", declaration.adapterClass());
    }

    @Test
    void disagreeingLanguageFailsAndNamesBothSources() {
        // Silently preferring either side would leave the author editing a file that has no effect, which is the bug
        // this whole mechanism exists to prevent, so the message has to name both places and both values.
        LoaderException failure = assertThrows(LoaderException.class,
                () -> ModLanguageDeclaration.of(annotation("scala", ""), metadata("kotlin", "", ""), "confused", "confused.jar"));

        assertTrue(failure.getMessage().contains("modLanguage"), failure.getMessage());
        assertTrue(failure.getMessage().contains("scala"), failure.getMessage());
        assertTrue(failure.getMessage().contains("kotlin"), failure.getMessage());
        assertTrue(failure.getMessage().contains("confused.jar"), failure.getMessage());
    }

    @Test
    void disagreeingAdapterFails() {
        LoaderException failure = assertThrows(LoaderException.class,
                () -> ModLanguageDeclaration.of(annotation("scala", "com.example.One"),
                        metadata("scala", "com.example.Two", ""), "confused", "confused.jar"));

        assertTrue(failure.getMessage().contains("com.example.One"), failure.getMessage());
        assertTrue(failure.getMessage().contains("com.example.Two"), failure.getMessage());
    }

    @Test
    void blankMetadataValueDoesNotConflict() {
        // A key written as "" is indistinguishable from an absent key in the parsed metadata (both are the field's
        // initial value), so it must not be treated as a declaration that disagrees.
        ModLanguageDeclaration declaration = ModLanguageDeclaration.of(annotation("scala", ""),
                metadata("", "", ""), "scaly", "scaly.jar");

        assertEquals("scala", declaration.language());
    }

    @Test
    void anAdapterClassOutranksTheLanguageName() {
        // The older escape hatch has to keep winning: it is self-contained and a mod shipping its own adapter must not
        // depend on someone else's provider.
        ModLanguageDeclaration declaration = ModLanguageDeclaration.of(annotation("scala", "com.example.Adapter"),
                emptyMetadata(), "scaly", "scaly.jar");

        assertEquals("com.example.Adapter", declaration.adapterClass());
        assertEquals("scala", declaration.language());
    }

    @Test
    void missingMetadataIsNotAConflict() {
        // bindMetadata is the normal path, but a container can be asked for its language before that happens.
        ModLanguageDeclaration declaration = ModLanguageDeclaration.of(annotation("scala", ""), null, "scaly", "scaly.jar");

        assertEquals("scala", declaration.language());
    }

    @Test
    void versionIsCarriedButNotInterpreted() {
        ModLanguageDeclaration declaration = ModLanguageDeclaration.of(Map.of(), metadata("scala", "", "[2.13,2.14)"),
                "scaly", "scaly.jar");

        assertEquals("[2.13,2.14)", declaration.version());
    }

    @Test
    void unknownLanguageFailsWithLanguageNameKnownLanguagesAndTheFix() {
        // The three facts a reader needs, in one message: what was asked for, what exists, and what to do about it.
        LoaderException failure = assertThrows(LoaderException.class,
                () -> LanguageAdapterRegistry.getAdapterFor("scala", "scaly", "scaly.jar"));

        String message = failure.getMessage();
        assertTrue(message.contains("scaly"), message);
        assertTrue(message.contains("scala"), message);
        assertTrue(message.contains("Known languages: java"), message);
        assertTrue(message.contains("modLanguageAdapter"), message);
    }

    @Test
    void undeclaredLanguageResolvesToTheJavaAdapter() {
        // "Declares nothing" and "declares java" must stay the same thing; both are the behaviour existing mods rely on.
        assertTrue(LanguageAdapterRegistry.getAdapterFor(null) instanceof ILanguageAdapter.JavaAdapter);
        assertTrue(LanguageAdapterRegistry.getAdapterFor("") instanceof ILanguageAdapter.JavaAdapter);
        assertTrue(LanguageAdapterRegistry.getAdapterFor("java") instanceof ILanguageAdapter.JavaAdapter);
    }
}
