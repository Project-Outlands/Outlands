package net.minecraftforge.fml.common.discovery.index;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraftforge.fml.common.FMLLog;

/**
 * The half of vanilla's assets that is not in the jar: the sounds, the languages and the pack icons the launcher
 * downloads into {@code assets/objects}, addressed through {@code assets/indexes/<index>.json}.
 *
 * <p>Measured on 1.12.2: the jar carries 4,642 asset files (textures, models, blockstates, structures) and the object
 * index 1,305 (1,085 sounds, 106 languages), with an empty intersection. The game reads the index first and falls back
 * to the class path, so the two are one priority level with two halves, not alternatives.
 *
 * <p>Nothing here is required for the game to run, and that is the point: a launcher that could not download, a
 * download that stopped halfway, an index that was never written - every one of those leaves the jar's half intact and
 * is the state a modded instance is in more often than not. So this class never throws at a caller: a missing or
 * unreadable index is an empty table, a missing object is a miss, and the answer falls through to the next layer.
 */
public final class AssetObjects {

    /** The index 1.12 and 1.12.2 both use; the name is not the game version. */
    public static final String DEFAULT_INDEX = "1.12";

    private static final AssetObjects ABSENT = new AssetObjects(null, Map.of(), "", "");

    private final Path objectsDir;
    /** {@code minecraft:lang/en_us.lang} to the object's file name, which is its hash. */
    private final Map<String, String> hashByName;
    private final String source;
    private final String indexId;

    private AssetObjects(Path objectsDir, Map<String, String> hashByName, String source, String indexId) {
        this.objectsDir = objectsDir;
        this.hashByName = hashByName;
        this.source = source;
        this.indexId = indexId;
    }

    /**
     * @param assetsDir the launcher's {@code assets} directory, or null when it is not known
     * @return the table it holds, or {@link #absent()} when there is nothing readable there
     */
    /**
     * @return the table for the paths the launcher passed, which {@code net.minecraftforge.boot.Main} keeps before the
     *         game starts; an absent directory or index is an empty table, as everywhere else here
     */
    public static AssetObjects fromLauncher() {
        final String dir = System.getProperty("outlands.assets.dir");
        return open(dir == null || dir.isBlank() ? null : Path.of(dir));
    }

    public static AssetObjects open(@Nullable Path assetsDir) {
        final String configured = System.getProperty("outlands.assets.index");
        return open(assetsDir, configured == null || configured.isBlank() ? DEFAULT_INDEX : configured);
    }

    public static AssetObjects open(@Nullable Path assetsDir, String indexId) {
        if (assetsDir == null) {
            return ABSENT;
        }
        final Path index = assetsDir.resolve("indexes").resolve(indexId + ".json");
        if (!Files.isRegularFile(index)) {
            FMLLog.log.debug("No asset index at {}; the jar's own assets answer alone", index);
            return ABSENT;
        }
        try (Reader reader = new InputStreamReader(Files.newInputStream(index), StandardCharsets.UTF_8)) {
            final JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            final JsonElement objects = root.get("objects");
            if (objects == null || !objects.isJsonObject()) {
                return ABSENT;
            }
            final Map<String, String> table = new HashMap<>();
            for (Map.Entry<String, JsonElement> entry : objects.getAsJsonObject().entrySet()) {
                final JsonElement hash = entry.getValue().isJsonObject()
                        ? entry.getValue().getAsJsonObject().get("hash") : null;
                // a key without a "/" is a legacy name the index 1.12 does not use, but one malformed row is not a
                // reason to lose the other 1,304
                if (hash == null || hash.isJsonNull()) {
                    continue;
                }
                final String value = hash.getAsString();
                if (value.length() < 2) {
                    continue;
                }
                table.put(normalise(entry.getKey()), value);
            }
            return new AssetObjects(assetsDir.resolve("objects"), Map.copyOf(table), index.toString(), indexId);
        } catch (IOException | RuntimeException e) {
            // a half-written index is a real state: a launcher that was interrupted leaves exactly this
            FMLLog.log.warn("Could not read the asset index {}; the jar's own assets answer alone", index, e);
            return ABSENT;
        }
    }

    /** @return a table that knows nothing, which every query treats as a miss */
    public static AssetObjects absent() {
        return ABSENT;
    }

    public boolean available() {
        return !hashByName.isEmpty();
    }

    /** @return the file the index was read from, for a diagnostic that can tell the user what is missing */
    public String source() {
        return source;
    }

    /** @return the index this table was read from, such as {@code 1.12}, or empty when there is no table */
    public String indexId() {
        return indexId;
    }

    public int size() {
        return hashByName.size();
    }

    /**
     * @param path {@code assets/minecraft/lang/en_us.lang} or {@code minecraft:lang/en_us.lang}
     * @return whether the index lists that name, whether or not the object is on disk
     */
    public boolean lists(String path) {
        return hashByName.containsKey(normalise(path));
    }

    /**
     * @return whether the index lists the name <em>and</em> the object it points at exists; a partial download passes
     *         {@link #lists} and fails this, which is what a caller should decide on
     */
    public boolean has(String path) {
        final String hash = hashByName.get(normalise(path));
        return hash != null && Files.isRegularFile(objectsDir.resolve(hash.substring(0, 2)).resolve(hash));
    }

    /**
     * @return the bytes of that object, or null when it was never downloaded
     */
    @Nullable
    public byte[] read(String path) throws IOException {
        final String hash = hashByName.get(normalise(path));
        if (hash == null) {
            return null;
        }
        final Path file = objectsDir.resolve(hash.substring(0, 2)).resolve(hash);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        return Files.readAllBytes(file);
    }

    /**
     * @param prefix an archive path prefix, such as {@code assets/minecraft/lang/}
     * @return how many names the index lists under it, reported in the archive spelling the layers use
     */
    public int paths(String prefix, java.util.function.Consumer<String> sink) {
        final String wanted = normalise(prefix.endsWith("/") ? prefix.substring(0, prefix.length() - 1) : prefix);
        int found = 0;
        for (String key : hashByName.keySet()) {
            if (!key.startsWith(wanted)) {
                continue;
            }
            final int colon = key.indexOf(':');
            if (colon < 0) {
                sink.accept(key);
            } else {
                sink.accept("assets/" + key.substring(0, colon) + "/" + key.substring(colon + 1));
            }
            found++;
        }
        return found;
    }

    /**
     * Turns an archive path into the key the index uses: the index keys are {@code <namespace>/<path>} and the game
     * joins them with a colon, so an {@code assets/}-prefixed path is accepted and rewritten.
     */
    static String normalise(String path) {
        String name = path;
        if (name.startsWith("assets/")) {
            name = name.substring("assets/".length());
        }
        if (name.indexOf(':') >= 0) {
            return name; // already the index's own spelling
        }
        final int slash = name.indexOf('/');
        return slash <= 0 ? name : name.substring(0, slash) + ":" + name.substring(slash + 1);
    }
}
