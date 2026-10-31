package net.minecraftforge.common.config;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

/**
 * Decides which file on disk backs one {@code @Config} declaration.
 *
 * <p>Before this existed the answer was the expression {@code new File(configDir, name + ".cfg")}, repeated at every
 * place that touched a config file, so "which file is this" was re-derived from a string each time and could only ever
 * mean CFG. The format (and therefore the extension) is now part of the declaration, and the rule that picks the file
 * lives here alone so every caller - the loader, the register path, the GUI lookup - cannot disagree.
 *
 * <p>The resolution rules, in the order they win:
 *
 * <ol>
 *   <li><b>An explicit {@code @Config(format=...)}</b> decides. An author who names a format gets that format even if a
 *       file with another extension is sitting right next to it; the leftover file is simply not this config.</li>
 *   <li><b>An existing file of the same name</b> decides when the annotation does not. This is what keeps a config that
 *       used to declare, say, TOML working after the declaration is removed, instead of silently materialising a fresh
 *       CFG file next to the user's real settings and stranding them.</li>
 *   <li><b>{@link Config.Format#CFG}</b> decides otherwise, which is exactly the historical behaviour.</li>
 * </ol>
 *
 * <p>When rule 2 finds more than one candidate, {@link Config.Format} declaration order breaks the tie ({@code CFG}
 * first) and the others are reported. Picking deterministically and saying which files were ignored is deliberate: an
 * ambiguity here is a content question only the user or the author can answer, so refusing to start would take the game
 * down over a misnamed file, while silently picking at random would be indistinguishable from data loss.
 */
public final class ConfigFileResolver {

    private ConfigFileResolver() {}

    /**
     * Why a target came out the way it did, for callers that want to log it once.
     */
    public enum Origin {
        /** The annotation named the format. Nothing to say. */
        DECLARED,
        /** The annotation named no format and the default was used. This is the ordinary case. */
        DEFAULT,
        /** The annotation named no format but a file of another format already existed. */
        EXISTING_FILE,
        /** {@code name} carried a known extension, so the historical {@code name + ".cfg"} spelling was kept. */
        LEGACY_NAME
    }

    /**
     * One resolved config file.
     */
    public static final class Target {
        private final File file;
        private final Config.Format format;
        private final Origin origin;
        private final List<File> ignored;

        private Target(File file, Config.Format format, Origin origin, List<File> ignored) {
            this.file = file;
            this.format = format;
            this.origin = origin;
            this.ignored = ignored;
        }

        public File file() {
            return file;
        }

        public Config.Format format() {
            return format;
        }

        public Origin origin() {
            return origin;
        }

        /** Files of the same name that lost the tie-break. Empty unless several formats existed at once. */
        public List<File> ignored() {
            return ignored;
        }
    }

    /**
     * Resolves one declaration.
     *
     * @param configDir the directory config files live in
     * @param modid the declaring mod, used as the file name when {@code name} is empty
     * @param name the {@code @Config(name)} value, possibly empty
     * @param declared the {@code @Config(format)} value, or {@code null} to resolve by the rules above
     */
    public static Target resolve(File configDir, String modid, String name, @Nullable Config.Format declared) {
        String stem = name == null || name.isEmpty() ? modid : name;

        // A name that already carries an extension is the pre-format spelling of "this is called x.cfg". Stripping it
        // would move the file the user has been editing, so the old literal spelling is kept and the author is told.
        String extension = extensionOf(stem);
        if (extension != null && Config.Format.byExtension(extension) != null) {
            Config.Format format = declared != null ? declared : Config.Format.CFG;
            return new Target(new File(configDir, stem + "." + format.extension()), format, Origin.LEGACY_NAME,
                    List.of());
        }

        if (declared != null)
            return new Target(new File(configDir, stem + "." + declared.extension()), declared, Origin.DECLARED,
                    List.of());

        List<Config.Format> found = new ArrayList<>();
        List<File> files = new ArrayList<>();
        for (Config.Format format : Config.Format.values()) {
            for (String spelling : format.extensions()) {
                File candidate = new File(configDir, stem + "." + spelling);
                if (candidate.isFile()) {
                    found.add(format);
                    files.add(candidate);
                }
            }
        }

        if (found.isEmpty())
            return new Target(new File(configDir, stem + "." + Config.Format.CFG.extension()), Config.Format.CFG,
                    Origin.DEFAULT, List.of());

        return new Target(files.get(0), found.get(0), Origin.EXISTING_FILE, List.copyOf(files.subList(1, files.size())));
    }

    /** Resolves the config a class declares. */
    public static Target resolve(File configDir, Config annotation) {
        return resolve(configDir, annotation.modid(), annotation.name(), annotation.format());
    }

    /** @return the extension of {@code name} without the dot, or {@code null} if it has none */
    @Nullable
    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        // A leading dot is part of the name (hidden file), not an extension separator.
        return dot <= 0 || dot == name.length() - 1 ? null : name.substring(dot + 1);
    }
}
