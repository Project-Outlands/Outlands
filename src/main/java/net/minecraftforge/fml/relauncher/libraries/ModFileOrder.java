package net.minecraftforge.fml.relauncher.libraries;

import java.io.File;
import java.util.Comparator;
import java.util.Locale;

/**
 * The order mod files are offered to the loader in.
 *
 * <p>The rule is a filename comparison with one tie-break, and the tie-break exists because subdirectories make
 * duplicate names possible: two folders can each hold {@code coremods.jar}. Comparing by name alone leaves those two in
 * whatever order the directory listing produced, and since the sort is stable that order is the filesystem's - so
 * "which one wins" would vary between machines. The relative path makes it deterministic again.
 *
 * <p>Directory names are used for exactly that and nothing else. They are labels a pack author picks for their own
 * benefit, so no ordering, discovery or assembly decision may depend on them: renaming a folder must not change what
 * loads first.
 */
public final class ModFileOrder implements Comparator<File> {

    /** Used for every scan mode. A single level of nesting is enough to tell two same-named files apart. */
    public static final ModFileOrder INSTANCE = new ModFileOrder();

    private ModFileOrder() {
    }

    @Override
    public int compare(File left, File right) {
        int byName = name(left).compareTo(name(right));
        if (byName != 0) {
            return byName;
        }
        // Only reached for duplicate names, where the path is the only stable discriminator available.
        return path(left).compareTo(path(right));
    }

    private static String name(File file) {
        return file.getName().toLowerCase(Locale.ENGLISH);
    }

    /**
     * The path of the parent directory, normalised so the comparison does not depend on the separator the platform
     * happens to use.
     */
    private static String path(File file) {
        File parent = file.getParentFile();
        String path = parent == null ? "" : parent.getAbsolutePath();
        return path.replace(File.separatorChar, '/').toLowerCase(Locale.ENGLISH);
    }
}
