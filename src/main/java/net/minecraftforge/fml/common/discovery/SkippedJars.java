package net.minecraftforge.fml.common.discovery;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

/**
 * The jars the loader looked at and deliberately did not put on the class path, with the reason.
 *
 * <p>A reader that walks {@code mods/} itself would find them and index classes the game will never load; the manifest
 * names them so that a class graph built offline can leave them out for the same reason the loader did.
 */
public final class SkippedJars {

    /** One jar and why it is not on the class path. */
    public record Skipped(String path, String reason) {}

    private static final Map<String, String> SKIPPED = new ConcurrentHashMap<>();

    private SkippedJars() {}

    public static void record(@Nullable File jar, String reason) {
        if (jar == null) {
            return;
        }
        try {
            record(jar.getAbsolutePath(), reason);
        } catch (RuntimeException ignored) {
            // a path we cannot name is not worth a manifest entry
        }
    }

    /** For a source that is not a file of its own, such as a nested library the loader decided not to extract. */
    public static void record(String path, String reason) {
        SKIPPED.putIfAbsent(path, reason);
    }

    public static List<Skipped> all() {
        final List<Skipped> result = new ArrayList<>(SKIPPED.size());
        for (Map.Entry<String, String> entry : SKIPPED.entrySet()) {
            result.add(new Skipped(entry.getKey(), entry.getValue()));
        }
        return result;
    }

    /** Forgets what this process recorded; the manifest is written once, so this is only for tests. */
    public static void clear() {
        SKIPPED.clear();
    }
}
