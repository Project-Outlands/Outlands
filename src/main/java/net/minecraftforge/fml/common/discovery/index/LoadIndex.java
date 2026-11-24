package net.minecraftforge.fml.common.discovery.index;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarFile;

import javax.annotation.Nullable;

import net.minecraftforge.fml.common.FMLLog;
import net.minecraftforge.fml.common.discovery.cache.ClassScanRecord;
import net.minecraftforge.fml.common.discovery.cache.JarFingerprint;
import net.minecraftforge.fml.common.discovery.cache.JarScanRecord;
import net.minecraftforge.fml.common.discovery.cache.RecordFile;

/**
 * Answers questions about any loaded archive's classes without loading them: {@code classInfo} resolves a name
 * through the archives in class path order, so the answer is the class the class loader would define, and
 * {@code supertypes} follows a chain across archives.
 *
 * <p>The per-archive index comes from the scan discovery already took when it has one, and from the on-disk record
 * otherwise, so a class can be answered for a jar that this run never scanned. Nothing here reads a class file
 * twice or loads a class.
 *
 * <p>Populated during discovery; {@link #active()} is null before that, and every query below tolerates an archive
 * with no record by simply not answering from it.
 */
public final class LoadIndex {

    private static volatile LoadIndex active;

    /** @return the index discovery populated, or null when discovery has not started */
    @Nullable
    public static LoadIndex active() {
        return active;
    }

    /** Publishes {@code index} as the one {@link #active()} returns. */
    public static void activate(LoadIndex index) {
        active = index;
    }

    private final File gameDir;
    /** Class path order: the first archive that has a class is the one the class loader uses. */
    private final List<File> order;

    /** Scans discovery produced in this run, by absolute file. */
    private final Map<File, JarScanRecord> scans = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<File, ClassIndex> indexes = new ConcurrentHashMap<>();
    /** Archives that answered with no record; asking again would only cost a file read. */
    private final Set<File> withoutRecord = ConcurrentHashMap.newKeySet();

    private final Map<String, ClassScanRecord> hits = new ConcurrentHashMap<>();
    private final Set<String> misses = ConcurrentHashMap.newKeySet();

    public LoadIndex(File gameDir, List<File> order) {
        this.gameDir = gameDir;
        this.order = List.copyOf(order);
    }

    /** Registers the scan of one archive; a later scan of the same file replaces it. */
    public void register(File archive, JarScanRecord scan) {
        final File key = archive.getAbsoluteFile();
        scans.put(key, scan);
        indexes.remove(key);
        withoutRecord.remove(key);
        hits.clear();
        misses.clear();
    }

    /** Registers a scan with the active index, if there is one; the index is optional for discovery. */
    public static void registerIfActive(@Nullable File archive, @Nullable JarScanRecord scan) {
        final LoadIndex index = active;
        if (index != null && archive != null && scan != null) {
            index.register(archive, scan);
        }
    }

    /** @return the archives this index searches, in class path order */
    public List<File> order() {
        return order;
    }

    /**
     * @param internalName a name such as {@code net/minecraft/world/World}
     * @return the class the class path resolves that name to, or null when no archive has a record of it
     */
    @Nullable
    public ClassScanRecord classInfo(String internalName) {
        final ClassScanRecord cached = hits.get(internalName);
        if (cached != null) {
            return cached;
        }
        if (misses.contains(internalName)) {
            return null;
        }
        for (File archive : order) {
            final ClassIndex index = indexOf(archive);
            if (index == null) {
                continue;
            }
            final ClassScanRecord record = index.byName(internalName);
            if (record != null) {
                hits.put(internalName, record);
                return record;
            }
        }
        misses.add(internalName);
        return null;
    }

    /**
     * @return the ancestors of {@code internalName} in class path order of appearance, nearest first, following
     *         the chain into other archives; empty when the class itself is unknown
     */
    public List<String> supertypes(String internalName) {
        if (classInfo(internalName) == null) {
            return List.of();
        }
        final List<String> result = new ArrayList<>();
        final List<String> pending = new ArrayList<>();
        final Set<String> seen = new java.util.HashSet<>();
        seen.add(internalName);
        enqueueParents(classInfo(internalName), pending, seen);
        for (int position = 0; position < pending.size(); position++) {
            final String current = pending.get(position);
            result.add(current);
            enqueueParents(classInfo(current), pending, seen);
        }
        return result;
    }

    /** @return whether {@code type} is {@code other} or descends from it, as far as the archives can tell */
    public boolean isSameOrSubtypeOf(String type, String other) {
        return type.equals(other) || supertypes(type).contains(other);
    }

    /**
     * @param packagePrefix a package in internal form ending with a slash, or {@code ""} for the default package
     * @return the classes directly in that package, first archive first, one entry per name
     */
    public List<ClassScanRecord> classesInPackage(String packagePrefix) {
        final Map<String, ClassScanRecord> found = new LinkedHashMap<>();
        for (File archive : order) {
            final ClassIndex index = indexOf(archive);
            if (index == null) {
                continue;
            }
            for (ClassScanRecord record : index.inPackage(packagePrefix)) {
                found.putIfAbsent(record.internalName(), record);
            }
        }
        return List.copyOf(found.values());
    }

    /** @return the index of one archive, or null when neither this run nor the cache has a record of it */
    @Nullable
    public ClassIndex indexOf(File archive) {
        final File key = archive.getAbsoluteFile();
        final ClassIndex known = indexes.get(key);
        if (known != null) {
            return known;
        }
        if (withoutRecord.contains(key)) {
            return null;
        }
        final JarScanRecord scan = scanOf(key);
        final ClassIndex built = scan == null ? null : ClassIndex.of(scan);
        if (built == null) {
            withoutRecord.add(key);
            return null;
        }
        final ClassIndex previous = indexes.putIfAbsent(key, built);
        return previous == null ? built : previous;
    }

    /** @return this run's scan of {@code archive}, or the cached one, or null when there is neither */
    @Nullable
    private JarScanRecord scanOf(File archive) {
        final JarScanRecord scanned = scans.get(archive);
        if (scanned != null) {
            return scanned;
        }
        try {
            final JarFingerprint fingerprint = fingerprintOf(archive);
            if (fingerprint == null) {
                return null;
            }
            return RecordFile.read(RecordFile.fileFor(gameDir, archive), fingerprint);
        } catch (RuntimeException e) {
            FMLLog.log.debug("No class index for {}", archive, e);
            return null;
        }
    }

    @Nullable
    private JarFingerprint fingerprintOf(File archive) {
        try (JarFile jar = new JarFile(archive, false)) {
            return JarFingerprint.compute(Collections.list(jar.entries()));
        } catch (IOException | RuntimeException e) {
            FMLLog.log.debug("Could not fingerprint {}", archive, e);
            return null;
        }
    }

    private static void enqueueParents(@Nullable ClassScanRecord record, List<String> pending, Set<String> seen) {
        if (record == null) {
            return;
        }
        if (record.superName() != null && seen.add(record.superName())) {
            pending.add(record.superName());
        }
        for (String intf : record.interfaces()) {
            if (seen.add(intf)) {
                pending.add(intf);
            }
        }
    }

    /** @return the archives of {@code urls} that are files, in order, for callers that only have URLs */
    public static List<File> filesOf(Iterable<java.net.URL> urls) {
        final List<File> files = new ArrayList<>();
        for (java.net.URL url : urls) {
            if (!"file".equalsIgnoreCase(url.getProtocol())) {
                continue;
            }
            try {
                files.add(new File(url.toURI()).getAbsoluteFile());
            } catch (Exception ignored) {
                // a URL we cannot turn into a file is not an archive this index can read
            }
        }
        return files;
    }
}
