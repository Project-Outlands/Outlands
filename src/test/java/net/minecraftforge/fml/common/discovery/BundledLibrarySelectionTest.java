package net.minecraftforge.fml.common.discovery;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import net.minecraftforge.fml.common.MetadataCollection;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers choosing between several bundled copies of one library.
 *
 * <p>Two versions of a library cannot both be on 1.12.2's single class loader. Before this the winner was whichever jar
 * was extracted first, so the same pack could produce different results from one start to the next and the failure
 * surfaced as a {@code NoSuchMethodError} far from its cause. These cases pin the replacement: highest version wins,
 * ties keep the earlier claim, and every decision is explainable from the log line.
 */
class BundledLibrarySelectionTest {

    private static BundledLibrarySelection.Claim claim(String id, String version, String source) {
        return new BundledLibrarySelection.Claim(id, version, "META-INF/libraries/" + id + ".jar", source);
    }

    @Test
    void theHighestVersionWins() {
        BundledLibrarySelection.Claim old = claim("guava", "21.0", "a.jar");
        BundledLibrarySelection.Claim newer = claim("guava", "31.1", "b.jar");

        BundledLibrarySelection selection = BundledLibrarySelection.select(List.of(old, newer));

        assertTrue(selection.isShadowed(old), "the older copy must not go on the classpath");
        assertFalse(selection.isShadowed(newer));
        assertEquals("31.1", selection.winnerVersion("guava"));
    }

    /** The claim the log makes has to be checkable, so both copies and the winner are named. */
    @Test
    void theNoteNamesTheWinnerAndWhatItBeat() {
        BundledLibrarySelection selection = BundledLibrarySelection.select(List.of(
                claim("guava", "21.0", "a.jar"),
                claim("guava", "31.1", "b.jar")));

        assertEquals(1, selection.notes().size());
        String note = selection.notes().get(0);
        assertTrue(note.contains("guava"), note);
        assertTrue(note.contains("31.1") && note.contains("b.jar"), "the winner is named: " + note);
        assertTrue(note.contains("21.0") && note.contains("a.jar"), "the copy that lost is named: " + note);
    }

    /**
     * Versions in the wild are Maven versions. {@code 1.0.1} has to beat {@code 1.0}, and a qualifier has to sort
     * before its release - lexicographic comparison gets both wrong.
     */
    @Test
    void versionsAreOrderedTheWayMavenOrdersThem() {
        assertTrue(BundledLibrarySelection.compareVersions("1.0.1", "1.0") > 0);
        assertTrue(BundledLibrarySelection.compareVersions("2.0-beta", "2.0") < 0);
        assertTrue(BundledLibrarySelection.compareVersions("10", "9") > 0);
        assertEquals(0, BundledLibrarySelection.compareVersions("1.0", "1.0"));
    }

    /**
     * Equal versions must keep the old outcome. Extraction order used to decide, and it still does here, which is what
     * makes this change safe for the jars that declare no version at all (they all compare as {@code "0"}).
     */
    @Test
    void equalVersionsKeepTheEarlierClaim() {
        BundledLibrarySelection.Claim first = claim("guava", "0", "a.jar");
        BundledLibrarySelection.Claim second = claim("guava", "0", "b.jar");

        BundledLibrarySelection selection = BundledLibrarySelection.select(List.of(first, second));

        assertFalse(selection.isShadowed(first), "the pre-existing winner has to stay the winner");
        assertTrue(selection.isShadowed(second));
    }

    @Test
    void differentLibrariesDoNotCompete() {
        BundledLibrarySelection.Claim guava = claim("guava", "21.0", "a.jar");
        BundledLibrarySelection.Claim asm = claim("asm", "9.6", "b.jar");

        BundledLibrarySelection selection = BundledLibrarySelection.select(List.of(guava, asm));

        assertFalse(selection.isShadowed(guava));
        assertFalse(selection.isShadowed(asm));
        assertTrue(selection.notes().isEmpty(), "nothing was contested, so there is nothing to say");
    }

    /** A single claim is the ordinary case and must not produce a note. */
    @Test
    void oneClaimPerLibrarySaysNothing() {
        BundledLibrarySelection selection = BundledLibrarySelection.select(List.of(claim("guava", "21.0", "a.jar")));

        assertEquals("21.0", selection.winnerVersion("guava"));
        assertTrue(selection.notes().isEmpty());
    }

    @Test
    void anEmptySelectionShadowsNothing() {
        BundledLibrarySelection selection = BundledLibrarySelection.empty();

        assertFalse(selection.isShadowed(claim("guava", "21.0", "a.jar")));
        assertNull(selection.winnerVersion("guava"));
        assertTrue(selection.notes().isEmpty());
    }

    // ---------------------------------------------------------------- declaration lookup

    private static JarDeclaration declaration(String containedDeps) {
        String json = "{\"jar\":{\"schemaVersion\":1,\"license\":\"MIT\",\"containedDeps\":" + containedDeps + "}}";
        JarDeclaration declaration = MetadataCollection
                .from(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), "test.jar")
                .getJarDeclaration();
        assertTrue(declaration != null);
        return declaration;
    }

    /**
     * Extraction is driven by paths, while identity lives in the declaration. Looking one up from the other is what
     * lets the selection be applied to the copy that is about to be extracted.
     */
    @Test
    void aDeclarationIsFoundByPathOrByFileName() {
        JarDeclaration declaration = declaration(
                "[{\"id\":\"guava\",\"version\":\"21.0\",\"path\":\"META-INF/libraries/guava.jar\",\"kind\":\"LIB\"}]");

        assertEquals("guava", declaration.depFor("META-INF/libraries/guava.jar").id());
        assertEquals("guava", declaration.depFor("guava.jar").id(), "the manifest may spell the entry without the prefix");
        assertEquals("guava.jar", declaration.depFor("guava.jar").fileName());
        assertNull(declaration.depFor("other.jar"), "an undeclared entry must not match anything");
    }
}
