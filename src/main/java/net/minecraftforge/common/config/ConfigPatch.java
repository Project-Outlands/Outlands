package net.minecraftforge.common.config;

import java.io.File;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.minecraftforge.fml.common.FMLLog;

/**
 * The hand-written patch layer that lives in {@code custom_config/}.
 *
 * <p>A modpack replaces {@code config/} wholesale when it updates, so anything a player or pack author wants to survive
 * that has to live outside it. {@code custom_config/<name>.<ext>} sits next to {@code config/} for exactly that reason:
 * it is never generated, never written by the game, and it holds only the keys that were deliberately overridden.
 *
 * <p>A key absent from the patch keeps whatever {@code config/} says. That is the whole reason this layer needs no
 * record of "what the value used to be": absence already means "not overridden".
 *
 * <p>{@code @Config(name = "sub/quality")} puts the file at {@code config/sub/quality.cfg}, and the patch mirrors that
 * relative path: {@code custom_config/sub/quality.cfg}. Keying on the bare file name instead would make every
 * {@code quality.cfg} in the tree share one patch file, and would also drop the patch <em>inside</em> {@code config/},
 * where a pack update wipes it.
 *
 * <p>The patch is applied when the annotation model creates or looks up the property it patches, not when the file is
 * read. A config file written by an older version of a mod does not contain the keys that were added since, so applying
 * the patch straight after {@code load()} would call every one of those keys unknown and drop the override.
 */
public final class ConfigPatch {

    /** The directory name, a sibling of {@code config/} so a pack update cannot wipe it. */
    public static final String DIRECTORY_NAME = "custom_config";

    private ConfigPatch() {}

    /**
     * @param configDir the {@code config/} directory the config subsystem was told about
     * @param configFile a config file, normally one inside {@code configDir}
     * @return the patch file that would patch {@code configFile}, whether or not it exists, or {@code null} when there
     *         can be no patch - a file outside {@code config/} is not part of a pack's tree, so nothing should patch it
     */
    static File fileFor(File configDir, File configFile) {
        if (configDir == null || configFile == null)
            return null;

        Path root = configDir.getAbsoluteFile().toPath().normalize();
        Path file = configFile.getAbsoluteFile().toPath().normalize();
        if (file.equals(root) || !file.startsWith(root))
            return null;

        Path instanceDir = root.getParent();
        if (instanceDir == null)
            return null;

        return instanceDir.resolve(DIRECTORY_NAME).resolve(root.relativize(file)).toFile();
    }

    /**
     * Reads the patch for {@code configFile}.
     *
     * @return the layer, or {@code null} when there is no patch file
     */
    static Layer load(File configDir, File configFile) {
        File patchFile = fileFor(configDir, configFile);
        if (patchFile == null || !patchFile.isFile())
            return null;

        // Parsed as an ordinary Configuration so the two layers cannot disagree about syntax.
        Configuration parsed = new Configuration(patchFile);
        parsed.load();

        Map<String, Property> entries = new HashMap<>();
        for (String categoryName : parsed.getCategoryNames()) {
            for (Property property : parsed.getCategory(categoryName).getValues().values())
                entries.put(key(categoryName, property.getName()), property);
        }

        return new Layer(patchFile.getPath(), entries);
    }

    /** Qualified name used for patch entries, matching how the annotation model names its categories. */
    private static String key(String category, String property) {
        return category + Configuration.CATEGORY_SPLITTER + property;
    }

    /**
     * Applies the patch for {@code target}, if the configuration carries one for this key.
     *
     * @return whether the property was patched
     */
    static boolean applyTo(Configuration cfg, String category, Property target) {
        Layer layer = cfg.getPatchLayer();
        if (layer == null)
            return false;

        return layer.applyTo(key(category, target.getName()), target);
    }

    /** Warns about patch entries that no config class ever asked for, i.e. typos and stale keys. */
    static void reportUnused(Configuration cfg) {
        Layer layer = cfg.getPatchLayer();
        if (layer != null)
            layer.reportUnused(cfg);
    }

    /**
     * One parsed {@code custom_config} file.
     *
     * <p>The parsed patch property is used as a value only: its type, comment and range are ignored in favour of the
     * target's, because the target is what the annotation model describes.
     */
    static final class Layer {
        private final String source;
        private final Map<String, Property> entries;
        private final Set<String> used = new HashSet<>();
        private boolean reported;

        private Layer(String source, Map<String, Property> entries) {
            this.source = source;
            this.entries = entries;
        }

        private boolean applyTo(String key, Property target) {
            Property patch = entries.get(key);
            if (patch == null)
                return false;

            used.add(key);

            if (target.isList() != patch.isList()) {
                FMLLog.log.warn("Ignoring {} in {}: the config file has it as {}, the patch as {}", key, source,
                        target.isList() ? "a list" : "a single value", patch.isList() ? "a list" : "a single value");
                return false;
            }

            if (patch.isList()) {
                String[] values = patch.getStringList();
                if (values == null)
                    return false;
                // Re-applying is expected (a property can be looked up more than once); keep the file's own value.
                target.applyPatch(values, source);
                return true;
            }

            String value = patch.getString();
            if (!isValidFor(target, value)) {
                FMLLog.log.warn("Ignoring {} in {}: \"{}\" is not a valid {}", key, source, value, target.getType());
                return false;
            }

            target.applyPatch(value, source);
            return true;
        }

        private void reportUnused(Configuration cfg) {
            if (reported)
                return;
            reported = true;

            for (String key : entries.keySet()) {
                if (used.contains(key))
                    continue;

                String hint = closest(key, cfg);
                FMLLog.log.warn("Ignoring {} in {}: no such config entry{}. Nothing is overridden by that line.",
                        key, source, hint == null ? "" : " - did you mean " + hint + "?");
            }
        }

        /**
         * The patch is the one place a value is written by a human who cannot see a generated file, so a bad value has
         * to be caught here: it would otherwise only blow up later, when the field is read.
         */
        private static boolean isValidFor(Property target, String value) {
            if (value == null || target.getType() == null)
                return value != null;

            switch (target.getType()) {
                case INTEGER:
                    return parses(value, Integer::parseInt);
                case DOUBLE:
                    return parses(value, Double::parseDouble);
                case BOOLEAN:
                    String trimmed = value.trim().toLowerCase(Locale.ENGLISH);
                    return trimmed.equals("true") || trimmed.equals("false");
                default:
                    return true;
            }
        }

        private static boolean parses(String value, java.util.function.Function<String, Object> parser) {
            try {
                parser.apply(value.trim());
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }

        /** Picks the closest existing key so a typo points at the intended one instead of silently doing nothing. */
        private static String closest(String key, Configuration cfg) {
            String leaf = key.substring(key.lastIndexOf(Configuration.CATEGORY_SPLITTER) + 1);
            String best = null;
            int bestDistance = Integer.MAX_VALUE;

            for (String categoryName : cfg.getCategoryNames()) {
                for (String candidate : cfg.getCategory(categoryName).getValues().keySet()) {
                    int distance = distance(leaf.toLowerCase(Locale.ENGLISH), candidate.toLowerCase(Locale.ENGLISH));
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = key(categoryName, candidate);
                    }
                }
            }

            // Beyond a couple of edits it is not a typo any more, and suggesting something unrelated is worse than nothing.
            return bestDistance <= 2 ? best : null;
        }

        private static int distance(String a, String b) {
            int[] previous = new int[b.length() + 1];
            int[] current = new int[b.length() + 1];

            for (int j = 0; j <= b.length(); j++)
                previous[j] = j;

            for (int i = 1; i <= a.length(); i++) {
                current[0] = i;
                for (int j = 1; j <= b.length(); j++) {
                    int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                    current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
                }
                int[] swap = previous;
                previous = current;
                current = swap;
            }

            return previous[b.length()];
        }
    }
}
