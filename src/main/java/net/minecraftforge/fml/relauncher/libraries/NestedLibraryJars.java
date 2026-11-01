package net.minecraftforge.fml.relauncher.libraries;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarFile;

import javax.annotation.Nullable;

/**
 * Serves a jar that is bundled inside another jar straight to the class loader, so it never has to be written to disk.
 *
 * <p>Bundled libraries used to be extracted next to the mods and then referenced as files. That worked, but it left a
 * copy of somebody else's library on the player's disk, which then had to be matched to the mod that bundled it, kept in
 * step with it, and cleaned up when it changed - and every one of those steps is a place where the two can disagree.
 * A jar inside a jar is already a readable archive; the only thing missing was a URL scheme that can address it.
 *
 * <p>The scheme is {@code fmlnested:<outer jar>!/<entry>!/} - a base URL that ends in {@code !/}, exactly like
 * {@code jar:file:...!/} does, so the ordinary {@code URLClassLoader} can append resource names to it without knowing
 * anything about this class. Two things it cannot do, and neither matters for a library:
 *
 * <ul>
 *   <li><b>Directory listing.</b> A lookup by name works; {@code getResource("some/package/")} does not, because the
 *       generic URL loader appends names rather than listing them. Code that scans its own package would see nothing.</li>
 *   <li><b>{@code CodeSource} of the form {@code file:}.</b> {@code getProtectionDomain().getCodeSource().getLocation()}
 *       returns this URL, so a library that turns its own location into a {@link File} will fail. That is the one
 *       behaviour worth knowing about, and it is why extraction is still reachable via
 *       {@code -Dcleanroom.nestedJars=extract}.</li>
 * </ul>
 *
 * <p>File systems are cached per jar and never closed: a {@link Path} keeps working after its file system is closed only
 * until something reads it, at which point it throws {@link java.nio.file.ClosedFileSystemException}. Class loaders hold
 * URLs for the life of the process, so "the life of the process" is the only lifetime that cannot produce that.
 */
public final class NestedLibraryJars {

    /** The scheme of the URLs this class produces. */
    public static final String PROTOCOL = "fmlnested";

    /** Set to {@code extract} to go back to writing bundled libraries next to the mods. */
    private static final String MODE_PROPERTY = "cleanroom.nestedJars";

    private static final Handler HANDLER = new Handler();
    /** Entry lookups, keyed by jar and declared path. Content cannot change under a running game. */
    private static final Map<String, String> ENTRIES = new ConcurrentHashMap<>();

    /** Guards the open file systems: they are opened once per jar and closed in reverse order. */
    private static final Object LOCK = new Object();
    private static final Map<String, FileSystem> FILE_SYSTEMS = new HashMap<>();
    private static final List<FileSystem> OPENED = new ArrayList<>();

    private NestedLibraryJars() {}

    /** @return whether bundled libraries should be served from inside their parent jar rather than extracted */
    public static boolean enabled() {
        return !"extract".equalsIgnoreCase(System.getProperty(MODE_PROPERTY, "zipfs").trim());
    }

    /**
     * Builds the class path URL for a jar bundled inside {@code outerJar}.
     *
     * @param declaredPath the entry the declaration names; the {@code META-INF/libraries/} spelling of the same file is
     *                     tried too, because a manifest may name the entry either way
     * @return the URL, or {@code null} when {@code outerJar} does not contain that entry
     */
    @Nullable
    public static URL urlFor(File outerJar, String declaredPath) {
        String entry = entryInside(outerJar, declaredPath);
        if (entry == null) {
            return null;
        }
        try {
            return new URL(null, PROTOCOL + ":" + outerJar.getAbsolutePath() + "!/" + entry + "!/", HANDLER);
        } catch (MalformedURLException e) {
            return null;
        }
    }

    /**
     * @return the entry name inside {@code outerJar} for a declared path, or {@code null} when the jar has no such entry
     */
    @Nullable
    public static String entryInside(File outerJar, String declaredPath) {
        String key = outerJar.getAbsolutePath() + '|' + declaredPath;
        String cached = ENTRIES.get(key);
        if (cached != null) {
            return cached.isEmpty() ? null : cached;
        }

        String found = null;
        try (JarFile jar = new JarFile(outerJar)) {
            found = resolveEntry(jar, declaredPath);
        } catch (IOException e) {
            found = null;
        }
        ENTRIES.put(key, found == null ? "" : found);
        return found;
    }

    @Nullable
    private static String resolveEntry(JarFile jar, String declaredPath) {
        if (jar.getJarEntry(declaredPath) != null) {
            return declaredPath;
        }
        String name = declaredPath.substring(declaredPath.lastIndexOf('/') + 1);
        for (String candidate : new String[] {"META-INF/libraries/" + name, "META-INF/jarjar/" + name}) {
            if (jar.getJarEntry(candidate) != null) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Closes every cached file system, innermost first. Only a test needs this; the game keeps them for its whole life.
     *
     * <p>The order is not cosmetic: a nested jar's {@link Path} lives inside the parent's file system, so closing the
     * parent first makes zipfs throw {@link java.nio.file.ClosedFileSystemException} from {@code close()} itself, as it
     * tries to unregister a path it can no longer resolve. Closing in reverse order of opening is what makes the outer
     * jar last.
     */
    public static void closeAll() {
        synchronized (LOCK) {
            for (int i = OPENED.size() - 1; i >= 0; i--) {
                try {
                    OPENED.get(i).close();
                } catch (IOException | RuntimeException ignored) {
                    // Best effort: nothing can be done about it here, and it must not hide the caller's own problem.
                }
            }
            OPENED.clear();
            FILE_SYSTEMS.clear();
            ENTRIES.clear();
        }
    }

    /** Where a nested jar's own file system lives, keyed so that the same jar is opened once. */
    private static FileSystem fileSystem(String key, Path jar) throws IOException {
        synchronized (LOCK) {
            FileSystem existing = FILE_SYSTEMS.get(key);
            if (existing != null) {
                return existing;
            }
            FileSystem created = FileSystems.newFileSystem(jar);
            FILE_SYSTEMS.put(key, created);
            OPENED.add(created);
            return created;
        }
    }

    /**
     * Reads {@code <outer jar>!/<entry>!/<resource>}.
     *
     * <p>Both levels go through zipfs, so the entry being read is decompressed exactly as the jar reader would do it and
     * nothing is copied to a temporary file on the way.
     */
    private static InputStream open(URL url) throws IOException {
        String path = url.getPath();
        int resourceAt = path.lastIndexOf("!/");
        int entryAt = path.indexOf("!/");
        if (resourceAt < 0 || entryAt < 0 || resourceAt == entryAt) {
            throw new IOException("Not a nested jar URL: " + url);
        }

        String outerFile = path.substring(0, entryAt);
        String entry = path.substring(entryAt + 2, resourceAt);
        String resource = path.substring(resourceAt + 2);
        if (resource.isEmpty()) {
            throw new IOException("Not a file inside a nested jar: " + url);
        }

        try {
            Path outerRoot = Path.of(outerFile);
            FileSystem outer = fileSystem(outerFile, outerRoot);
            Path nestedJar = outer.getPath("/" + entry);
            FileSystem nested = fileSystem(outerFile + "!/" + entry, nestedJar);

            Path file = nested.getPath("/" + resource);
            if (!Files.isRegularFile(file)) {
                throw new IOException("No entry " + resource + " in " + entry + " inside " + outerFile);
            }

            // Read eagerly: the stream is handed to a class loader that may outlive this call and never close it, and a
            // FileSystem channel held open by an unclosed stream is worse than the bytes.
            return new java.io.ByteArrayInputStream(Files.readAllBytes(file));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            // Anything else here means the file system layer refused the request - a corrupt jar, a provider that
            // cannot read this compression, or the ServiceLoader failure described in java.nio.file.FileSystem.
            throw new IOException("Could not read " + url, e);
        }
    }

    /** A handler instance is enough: {@link URL} resolves relative names against the base URL's own handler. */
    private static final class Handler extends URLStreamHandler {
        @Override
        protected URLConnection openConnection(URL url) {
            return new URLConnection(url) {
                @Override
                public void connect() {
                    connected = true;
                }

                @Override
                public InputStream getInputStream() throws IOException {
                    return open(url);
                }
            };
        }
    }

    /** Exposed for the log line that names the mode, so a bug report says which one is in use. */
    public static String mode() {
        return enabled() ? "zipfs" : "extract";
    }
}
