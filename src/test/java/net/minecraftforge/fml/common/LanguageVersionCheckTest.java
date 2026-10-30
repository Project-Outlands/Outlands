package net.minecraftforge.fml.common;

import java.util.List;
import java.util.Map;

import net.minecraftforge.fml.common.versioning.VersionRange;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@code modLanguageVersion} against the versions a provider says it supports.
 *
 * <p>Worth pinning because of what it replaces: without this check, a mod compiled against a language version the
 * provider cannot serve loads fine and dies inside {@code preInit} with a {@code NoSuchMethodError}, which reports the
 * mod rather than the mismatch.
 */
class LanguageVersionCheckTest {

    /** A mod that requires {@code range} for language {@code scala}. */
    private static ModLanguageDeclaration requiring(String range) {
        ModMetadata metadata = new ModMetadata();
        metadata.modLanguage = "scala";
        metadata.modLanguageVersion = range;
        return ModLanguageDeclaration.of(Map.of(), metadata, "scaly", "scaly.jar");
    }

    @Test
    void aSupportedVersionInsideTheRangePasses() {
        assertDoesNotThrow(() -> requiring("[2.13,2.14)").checkVersion("scaly", "scaly.jar", List.of("2.12", "2.13")));
    }

    @Test
    void anExactRequirementMatchesOnlyThatVersion() {
        assertDoesNotThrow(() -> requiring("[2.13]").checkVersion("scaly", "scaly.jar", List.of("2.13")));
        assertThrows(LoaderException.class,
                () -> requiring("[2.13]").checkVersion("scaly", "scaly.jar", List.of("2.12")));
    }

    @Test
    void aRangeWithNoSupportedVersionFailsAndReportsBothSides() {
        LoaderException failure = assertThrows(LoaderException.class,
                () -> requiring("[2.13,2.14)").checkVersion("scaly", "scaly.jar", List.of("2.11")));

        String message = failure.getMessage();
        assertTrue(message.contains("scaly"), message);
        assertTrue(message.contains("[2.13,2.14)"), message);
        assertTrue(message.contains("2.11"), message);
    }

    @Test
    void aProviderThatSaysNothingKeepsWorking() {
        // Refusing to load here would have invalidated every provider written before this key existed, for a check that
        // is a convenience rather than a correctness requirement.
        assertDoesNotThrow(() -> requiring("[2.13,2.14)").checkVersion("scaly", "scaly.jar", null));
        assertDoesNotThrow(() -> requiring("[2.13,2.14)").checkVersion("scaly", "scaly.jar", List.of()));
    }

    @Test
    void noDeclaredVersionMeansNothingToCheck() {
        ModMetadata metadata = new ModMetadata();
        metadata.modLanguage = "scala";
        ModLanguageDeclaration declaration = ModLanguageDeclaration.of(Map.of(), metadata, "scaly", "scaly.jar");

        // Even a provider list that contradicts everything must not matter when the mod stated no requirement.
        assertDoesNotThrow(() -> declaration.checkVersion("scaly", "scaly.jar", List.of("9.9")));
    }

    @Test
    void aBareVersionMeansExactlyThatVersion() {
        // Maven semantics, and surprising enough to write down: no brackets is not "malformed", it is an exact
        // requirement. Only the bracket syntax describes a range.
        assertDoesNotThrow(() -> requiring("2.13").checkVersion("scaly", "scaly.jar", List.of("2.13")));
        assertThrows(LoaderException.class,
                () -> requiring("2.13").checkVersion("scaly", "scaly.jar", List.of("2.14")));
    }

    @Test
    void aMalformedRangeIsRejectedWithTheModNamed() {
        // An unterminated bracket - the one shape the parser cannot read as anything.
        LoaderException failure = assertThrows(LoaderException.class,
                () -> requiring("[2.13,2.14").checkVersion("scaly", "scaly.jar", List.of("2.13")));

        assertTrue(failure.getMessage().contains("scaly"), failure.getMessage());
        assertTrue(failure.getMessage().contains("[2.13,2.14"), failure.getMessage());
    }

    @Test
    void overlapIsDecidedByAnySingleSupportedVersion() throws Exception {
        // The provider lists what it can serve, not what is installed, so the question is whether the two descriptions
        // overlap at all - not whether every supported version is inside the range.
        VersionRange wanted = VersionRange.createFromVersionSpec("[2.13,2.14)");

        assertTrue(ModLanguageDeclaration.satisfiesAny(wanted, List.of("2.11", "2.13")));
        assertFalse(ModLanguageDeclaration.satisfiesAny(wanted, List.of("2.11", "2.14")));
    }
}
