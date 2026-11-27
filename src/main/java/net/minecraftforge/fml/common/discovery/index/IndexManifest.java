package net.minecraftforge.fml.common.discovery.index;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraftforge.fml.common.FMLLog;
import net.minecraftforge.fml.common.discovery.DetailScanner;
import net.minecraftforge.fml.common.discovery.SkippedJars;
import net.minecraftforge.fml.common.discovery.cache.JarFingerprint;
import net.minecraftforge.fml.common.discovery.cache.RecordFile;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * The index's publication record: the one file a reader opens first, and the last one written.
 *
 * <p>It lists the class path in the order the class loader resolves it, the jars the loader refused, the position from
 * which the index cannot model the order, and for every archive which section files exist with their hashes. A reader
 * that has the manifest has everything it needs to place a class in an archive, and can tell "this class does not
 * exist" from "this build did not include that kind of data".
 *
 * <p>Three keys are published instead of one version number, because the three move independently: {@code chunks} when
 * the mapping or this format changes, {@code order} when the class path changes, and each section's own hash when that
 * archive was re-read.
 */
public final class IndexManifest {

    /** Bump when the manifest itself changes shape. */
    public static final int FORMAT = 1;

    private static final String NAME = "manifest.json";
    private static final String PREVIOUS = "manifest.prev.json";
    private static final String TEMP = "manifest.json.tmp";

    /** Manifest writes are serialised: a startup write and a dump can overlap, and both publish the same file. */
    private static final Object WRITE_LOCK = new Object();

    /** Startup must not wait for the hashes; nothing in the game reads this file. */
    private static final java.util.concurrent.ExecutorService WRITER =
            java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                final Thread thread = new Thread(runnable, "Outlands index manifest");
                thread.setDaemon(true);
                return thread;
            });

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private IndexManifest() {}

    /**
     * Builds and publishes the manifest for what the index currently has.
     *
     * <p>The previous file is copied aside first and the new one is moved into place, so a reader never sees a partial
     * manifest and an interrupted write leaves the previous generation readable.
     */
    public static void write(File gameDir, LoadIndex index, String reason) {
        if (gameDir == null) {
            return;
        }
        synchronized (WRITE_LOCK) {
            writeNow(gameDir, index, reason);
        }
    }

    /** Publishes the manifest on a thread of its own; ordering against a later dump is kept by {@link #WRITE_LOCK}. */
    public static void writeAsync(File gameDir, LoadIndex index, String reason) {
        if (gameDir == null) {
            return;
        }
        WRITER.execute(() -> write(gameDir, index, reason));
    }

    private static void writeNow(File gameDir, LoadIndex index, String reason) {
        final File dir = RecordFile.cacheDir(gameDir);
        final File file = new File(dir, NAME);
        final File previous = new File(dir, PREVIOUS);
        try {
            final String json = GSON.toJson(build(gameDir, index, reason));
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            if (file.isFile()) {
                try {
                    Files.copy(file.toPath(), previous.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ignored) {
                    // the new manifest is what matters; a missing previous generation only costs the fallback
                }
            }
            final File temp = new File(dir, TEMP);
            Files.write(temp.toPath(), json.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            FMLLog.log.debug("Could not write the index manifest {}", file, e);
        }
    }

    /** @return the text of the manifest this index would publish, for tests and diagnostics */
    public static String render(File gameDir, LoadIndex index, String reason) {
        return GSON.toJson(build(gameDir, index, reason));
    }

    private static Map<String, Object> build(File gameDir, LoadIndex index, String reason) {
        final List<File> sources = index.order();
        final Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", FORMAT);
        root.put("builtAt", Instant.now().toString());
        root.put("reason", reason);
        root.put("gameDir", absolute(gameDir));
        root.put("jvm", jvm());
        root.put("keys", keys(index, gameDir, sources));
        root.put("unknownFrom", unknownFrom());

        final List<Object> listed = new ArrayList<>(sources.size());
        boolean anyDetail = false;
        boolean dev = false;
        int position = 0;
        for (File source : sources) {
            final Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("pos", position++);
            entry.put("path", path(gameDir, source));
            entry.put("kind", source.isDirectory() ? "dir" : "jar");
            entry.put("role", role(gameDir, index, source));
            dev |= source.isDirectory();
            final JarFingerprint fingerprint = source.isDirectory() ? null : index.fingerprintFor(source);
            if (fingerprint != null) {
                entry.put("fingerprint", Long.toHexString(fingerprint.value()));
                entry.put("classEntries", fingerprint.classEntryCount());
            }
            final Map<String, Object> sections = sections(gameDir, index, source);
            anyDetail |= sections.containsKey("detail");
            entry.put("sections", sections);
            listed.add(entry);
        }
        root.put("sources", listed);

        final List<Object> excluded = new ArrayList<>();
        for (SkippedJars.Skipped skipped : SkippedJars.all()) {
            final Map<String, Object> entry = new LinkedHashMap<>();
            // a nested entry is not a file of its own: "jar!/name" is already the shape a reader needs
            final String raw = skipped.path();
            entry.put("path", raw.contains("!/") ? raw.replace('\\', '/') : path(gameDir, new File(raw)));
            entry.put("reason", skipped.reason());
            excluded.add(entry);
        }
        root.put("excluded", excluded);

        final Map<String, Object> flags = new LinkedHashMap<>();
        flags.put("dev", dev);
        flags.put("detail", anyDetail);
        flags.put("registry", false);
        flags.put("mcpNames", false);
        root.put("flags", flags);
        return root;
    }

    /** The three keys, each derived only from what it describes. */
    private static Map<String, Object> keys(LoadIndex index, File gameDir, List<File> sources) {
        final Map<String, Object> keys = new LinkedHashMap<>();
        keys.put("chunks", sha256("format=" + FORMAT + ";mapping=" + index.mappingSalt()));
        final StringBuilder order = new StringBuilder();
        int position = 0;
        for (File source : sources) {
            final JarFingerprint fingerprint = source.isDirectory() ? null : index.fingerprintFor(source);
            order.append(position++).append('|').append(path(gameDir, source)).append('|')
                    .append(fingerprint == null ? "dir" : Long.toHexString(fingerprint.value())).append('\n');
        }
        keys.put("order", sha256(order.toString()));
        return keys;
    }

    private static Map<String, Object> jvm() {
        final Map<String, Object> jvm = new LinkedHashMap<>();
        jvm.put("home", absolute(new File(System.getProperty("java.home", ""))));
        jvm.put("version", System.getProperty("java.version", ""));
        jvm.put("vendor", System.getProperty("java.vendor", ""));
        jvm.put("classFileVersion", Runtime.version().feature());
        return jvm;
    }

    /**
     * @return the first class path position the index cannot model, or null when it can model all of them; a reader
     *         past that position has to treat its answer as best effort
     */
    /**
     * @return the first class path position the index cannot model, or null when it can model all of them; a reader
     *         past that position has to treat its answer as best effort
     */
    @Nullable
    private static Integer unknownFrom() {
        final int unknown = net.minecraft.launchwrapper.Launch.classLoader.classPathUnknownFrom();
        return unknown < 0 ? null : unknown;
    }

    private static String role(File gameDir, LoadIndex index, File source) {
        if (source.isDirectory()) {
            return "dir";
        }
        final File vanilla = index.vanillaArchive();
        if (vanilla != null && vanilla.getAbsoluteFile().equals(source.getAbsoluteFile())) {
            return "vanilla";
        }
        final String path = source.getAbsolutePath().replace('\\', '/');
        if (path.contains("/mods/memory_repo/")) {
            return "contained";
        }
        final String mods = new File(gameDir, "mods").getAbsolutePath().replace('\\', '/') + '/';
        return path.startsWith(mods) ? "mod" : "library";
    }

    private static Map<String, Object> sections(File gameDir, LoadIndex index, File source) {
        final Map<String, Object> sections = new LinkedHashMap<>();
        final File headers = index.headersFileFor(source);
        if (headers == null) {
            return sections;
        }
        putSection(gameDir, sections, "headers", headers);
        putSection(gameDir, sections, "members", new File(headers.getPath() + ".members"));
        putSection(gameDir, sections, "detail", index.detailFileFor(source));
        return sections;
    }

    private static void putSection(File gameDir, Map<String, Object> sections, String name, @Nullable File file) {
        if (file == null || !file.isFile()) {
            return;
        }
        final Map<String, Object> section = new LinkedHashMap<>();
        section.put("file", path(gameDir, file));
        section.put("size", file.length());
        final String hash = sha256(file);
        if (hash != null) {
            section.put("sha256", hash);
        }
        sections.put(name, section);
    }

    private static String path(File gameDir, File file) {
        try {
            final java.nio.file.Path base = gameDir.getAbsoluteFile().toPath().normalize();
            final java.nio.file.Path target = file.getAbsoluteFile().toPath().normalize();
            if (target.startsWith(base)) {
                return base.relativize(target).toString().replace('\\', '/');
            }
        } catch (RuntimeException ignored) {
            // fall through to the absolute path
        }
        return absolute(file);
    }

    private static String absolute(File file) {
        try {
            return file.getAbsolutePath().replace('\\', '/');
        } catch (RuntimeException e) {
            return file.getPath();
        }
    }

    @Nullable
    private static String sha256(File file) {
        try {
            return sha256(Files.readAllBytes(file.toPath()));
        } catch (IOException e) {
            return null;
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return hex(digest.digest(bytes));
        } catch (Exception e) {
            return "";
        }
    }

    private static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String hex(byte[] bytes) {
        final StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(String.format(Locale.ROOT, "%02x", b));
        }
        return out.toString();
    }
}
