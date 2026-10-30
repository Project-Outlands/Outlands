package net.minecraftforge.fml.relauncher.libraries;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Covers the order mod files are offered in, which matters because it is the order coremods are opened in.
 *
 * <p>The rule has to satisfy two things at once: keep the historical filename order for the flat layout that every
 * existing pack uses, and stay deterministic when subdirectories make duplicate names possible. The second half is the
 * new risk - comparing by name alone leaves equal names in directory-listing order, which differs between machines.
 */
class ModFileOrderTest {

    private static List<String> order(String... paths) {
        List<File> files = new ArrayList<>();
        for (String path : paths) {
            files.add(new File(path));
        }
        files.sort(ModFileOrder.INSTANCE);
        return files.stream().map(file -> file.getPath().replace(File.separatorChar, '/')).toList();
    }

    @Test
    void filesAreOrderedByName() {
        assertEquals(List.of("mods/a.jar", "mods/b.jar", "mods/c.jar"),
                order("mods/c.jar", "mods/a.jar", "mods/b.jar"));
    }

    @Test
    void nameComparisonIgnoresCase() {
        // Packs only differ in case by accident; treating "Foo.jar" as after "bar.jar" would be an unexplained reorder.
        assertEquals(List.of("mods/Alpha.jar", "mods/beta.jar"),
                order("mods/beta.jar", "mods/Alpha.jar"));
    }

    @Test
    void sameNameInDifferentDirectoriesIsDeterministic() {
        // The reason the tie-break exists: without it these two keep whatever order they were listed in.
        List<String> forwards = order("mods/thaumcraft/coremods.jar", "mods/ae2/coremods.jar");
        List<String> backwards = order("mods/ae2/coremods.jar", "mods/thaumcraft/coremods.jar");

        assertEquals(forwards, backwards);
        assertEquals(List.of("mods/ae2/coremods.jar", "mods/thaumcraft/coremods.jar"), forwards);
    }

    @Test
    void aSubdirectoryDoesNotJumpAheadOfTheTopLevel() {
        // Directory names are labels. A jar in "aaa/" must not sort before "b.jar" just because the folder is named that.
        assertEquals(List.of("mods/b.jar", "mods/aaa/c.jar"), order("mods/aaa/c.jar", "mods/b.jar"));
    }

    @Test
    void theMinecraftVersionDirectoryIsNotSpecial() {
        // mods/1.12.2/ is scanned as a directory like any other and its name carries no meaning for ordering.
        assertEquals(List.of("mods/a.jar", "mods/1.12.2/b.jar"),
                order("mods/1.12.2/b.jar", "mods/a.jar"));
    }
}
