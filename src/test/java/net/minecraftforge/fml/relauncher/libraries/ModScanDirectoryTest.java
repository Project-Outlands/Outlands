package net.minecraftforge.fml.relauncher.libraries;

import java.io.File;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers which subdirectories of the mods folder the {@code ONE_LEVEL} scan looks inside.
 *
 * <p>Worth pinning because a false positive here is not a crash: a jar belonging to some other tool gets scanned as a mod
 * candidate, and the user is told about a "mod" they never installed. A false negative is quieter still - a group folder
 * the pack author made is skipped and its mods silently do not load.
 */
class ModScanDirectoryTest {

    private static boolean scannable(String name) {
        return LibraryManager.isScannableDirectory(new File("/mods", name));
    }

    @Test
    void aFolderAPersonNamedIsSearched() {
        assertTrue(scannable("thaumcraft"));
        assertTrue(scannable("AE2 Addons"));
        assertTrue(scannable("1.12.2-packs"));
    }

    @Test
    void generatedWorkingDirectoriesAreSkipped() {
        // Each of these belongs to a launcher or to the loader itself, and none of them is a place a player puts mods.
        assertFalse(scannable("memory_repo"));
        assertFalse(scannable(".cache"));
        assertFalse(scannable(".index"));
        assertFalse(scannable("META-INF"));
    }

    @Test
    void everyHiddenDirectoryIsSkipped() {
        // By convention a leading dot means "tool state", so the rule cannot depend on knowing each tool's name.
        assertFalse(scannable(".something-new"));
        assertFalse(scannable(".minecraft"));
    }

    @Test
    void theMinecraftVersionDirectoryIsNotReadTwice() {
        // It is already a scan root of its own; reading it again as an ordinary subdirectory would add nothing and
        // would scan the same jars under two different code paths.
        assertFalse(scannable(net.minecraftforge.common.ForgeVersion.mcVersion));
    }

    @Test
    void nameMatchingIgnoresCase() {
        // Windows filesystems do not distinguish these, so a rule that did would behave differently per platform.
        assertFalse(scannable("Memory_Repo"));
        assertFalse(scannable("meta-inf"));
    }
}
