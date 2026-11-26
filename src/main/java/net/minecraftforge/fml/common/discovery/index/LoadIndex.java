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
import net.minecraftforge.fml.common.discovery.cache.MemberFile;
import net.minecraftforge.fml.common.discovery.cache.MemberRecord;
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

    private static final MemberRecord[] NO_MEMBERS = new MemberRecord[0];

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
    /**
     * The loader's own search order, read on demand: mods are added to the class loader after discovery starts, so a
     * snapshot taken at construction would order them before the jars they actually come after.
     */
    private final java.util.function.Supplier<List<File>> orderSource;
    private List<File> order = List.of();
    private int orderSignature;

    /** Scans discovery produced in this run, by absolute file. */
    private final Map<File, JarScanRecord> scans = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<File, ClassIndex> indexes = new ConcurrentHashMap<>();
    /** Archives that answered with no record; asking again would only cost a file read. */
    private final Set<File> withoutRecord = ConcurrentHashMap.newKeySet();

    /** name -> the archive whose class the loader would define; what a member query starts from. */
    private final Map<String, File> origins = new ConcurrentHashMap<>();
    private final Set<String> misses = ConcurrentHashMap.newKeySet();
    /** Per-archive member tables, read on demand: a full table is an order of magnitude larger than the headers. */
    private final Map<File, Map<String, MemberRecord[]>> memberTables = new ConcurrentHashMap<>();
    private final Set<File> withoutMembers = ConcurrentHashMap.newKeySet();
    /** Archives indexed from a file outside the per-jar cache tree, mapped to the index file that was used. */
    private final Map<File, File> vanillaIndexFiles = new ConcurrentHashMap<>();

    /** Null until a caller replaces the process table; a test does, a run does not. */
    @Nullable
    private Mappings mappingTable;

    public LoadIndex(File gameDir, List<File> order) {
        this(gameDir, () -> order);
        this.order = List.copyOf(order);
    }

    public LoadIndex(File gameDir, java.util.function.Supplier<List<File>> orderSource) {
        this.gameDir = gameDir;
        this.orderSource = orderSource;
        refreshOrder();
    }

    /**
     * Re-reads the search order and drops what was answered under the old one. Mods reach the class loader as
     * discovery proceeds, so an archive that was not on the class path yet can appear here later.
     */
    private boolean refreshOrder() {
        final List<File> current = List.copyOf(orderSource.get());
        final int signature = current.hashCode();
        if (signature == orderSignature && current.size() == order.size()) {
            return false;
        }
        order = current;
        orderSignature = signature;
        origins.clear();
        misses.clear();
        return true;
    }

    /** Registers the scan of one archive; a later scan of the same file replaces it. */
    public void register(File archive, JarScanRecord scan) {
        final File key = archive.getAbsoluteFile();
        scans.put(key, scan);
        indexes.remove(key);
        withoutRecord.remove(key);
        memberTables.remove(key);
        withoutMembers.remove(key);
        origins.clear();
        misses.clear();
    }

    /** Registers a scan with the active index, if there is one; the index is optional for discovery. */
    public static void registerIfActive(@Nullable File archive, @Nullable JarScanRecord scan) {
        final LoadIndex index = active;
        if (index != null && archive != null && scan != null) {
            index.register(archive, scan);
        }
    }

    /**
     * Indexes the archive of the class path whose names the loader renames (the minecraft jar), from the cache when it
     * still matches the archive and otherwise by reading its class headers. One pass, once per install.
     *
     * @param naming the mapping the loader applies to names and descriptors
     * @param salt         a token that changes when the mapping does, so an index of another mapping is not reused
     * @return whether an archive was indexed
     */
    public boolean indexMinecraftJar(ClassNaming naming, String salt) {
        refreshOrder();
        for (File archive : order) {
            if (!archive.isFile() || !VanillaIndex.looksLikeMinecraftJar(archive)) {
                continue;
            }
            final JarScanRecord scan = scanWithCache(archive, naming, salt);
            if (scan != null) {
                register(archive, scan);
                return true;
            }
        }
        return false;
    }

    @Nullable
    private JarScanRecord scanWithCache(File archive, ClassNaming naming, String salt) {
        final JarFingerprint fingerprint = fingerprintOf(archive);
        if (fingerprint == null) {
            return null;
        }
        final File cacheFile = vanillaCacheFile(archive, fingerprint, salt);
        if (cacheFile != null) {
            vanillaIndexFiles.put(archive.getAbsoluteFile(), cacheFile);
        }
        final JarScanRecord cached = cacheFile == null ? null : RecordFile.read(cacheFile, fingerprint);
        if (cached != null) {
            return cached;
        }
        try {
            final VanillaIndex.Scanned scanned = VanillaIndex.scan(archive, naming);
            if (cacheFile != null) {
                RecordFile.write(cacheFile, scanned.index());
                final File memberFile = memberFileTarget(archive, cacheFile);
                if (memberFile != null) {
                    MemberFile.write(memberFile, scanned.index().fingerprint(), scanned.members());
                }
            }
            // keep the table this pass produced, so the first query after it does not read it back
            memberTables.put(archive.getAbsoluteFile(), scanned.members());
            return scanned.index();
        } catch (IOException | RuntimeException e) {
            FMLLog.log.warn("Could not build the class index of {}", archive, e);
            return null;
        }
    }

    /**
     * The minecraft jar lives outside the game directory, so {@link RecordFile#fileFor} does not place it; it gets a
     * file of its own next to the per-jar records, named by the fingerprint it was taken from and the mapping salt.
     */
    private static File memberCacheFile(File indexFile) {
        return new File(indexFile.getPath() + ".members");
    }

    @Nullable
    private File vanillaCacheFile(File archive, JarFingerprint fingerprint, String salt) {
        if (gameDir == null) {
            return null;
        }
        final File root = new File(RecordFile.cacheDir(gameDir), "vanilla");
        return new File(root, salt + "-" + Long.toHexString(fingerprint.value()) + "-" + fingerprint.classEntryCount() + ".bin");
    }

    /** @return the archives this index searches, in class path order */
    public List<File> order() {
        refreshOrder();
        return order;
    }

    /**
     * @param internalName a name such as {@code net/minecraft/world/World}
     * @return the class the class path resolves that name to, or null when no archive has a record of it
     */
    @Nullable
    public ClassScanRecord classInfo(String internalName) {
        final File origin = archiveOf(internalName);
        if (origin == null) {
            return null;
        }
        final ClassIndex index = indexOf(origin);
        return index == null ? null : index.byName(internalName);
    }

    /**
     * @param internalName a name such as {@code net/minecraft/world/World}
     * @return the members that class declares, or an empty array when the class or its member table is unknown
     */
    public MemberRecord[] membersOf(String internalName) {
        final File origin = archiveOf(internalName);
        if (origin == null) {
            return NO_MEMBERS;
        }
        final Map<String, MemberRecord[]> table = memberTableOf(origin);
        final MemberRecord[] members = table == null ? null : table.get(internalName);
        return members == null ? NO_MEMBERS : members;
    }

    // ---- 名字视图：SRG 是索引里的名字，MCP 是查询时的投影 ----

    /** @return the table that turns an SRG member name into the MCP one, empty when the user placed none */
    public Mappings mappings() {
        if (mappingTable != null) {
            return mappingTable;
        }
        final Mappings table = Mappings.get();
        if (!table.started()) {
            // discovery starts it when it builds the index; a test that builds its own does not
            table.start(gameDir);
        }
        return table;
    }

    /** Uses {@code table} for name queries instead of the process one; the process one is the default. */
    void useMappings(Mappings table) {
        this.mappingTable = table;
    }

    /**
     * @param owner a name such as {@code net/minecraft/world/World}
     * @param name the name the loader defines, such as {@code func_72959_q}
     * @return the MCP name for that member, or empty when MCP does not name it, the owner does not declare it, or
     *         there is no table at all
     */
    public java.util.Optional<String> srgToMcp(String owner, String name, String descriptor) {
        for (MemberRecord member : membersOf(owner)) {
            // the owner and the descriptor are checked, not used: they are what makes the answer this member and not
            // another one that happens to share the SRG name
            if (member.name().equals(name) && member.descriptor().equals(descriptor)) {
                return mappings().mcpNameOf(name);
            }
        }
        return java.util.Optional.empty();
    }

    /**
     * @param owner a name such as {@code net/minecraft/world/World}
     * @param mcpName the MCP name a script used, such as {@code getWorldInfo}
     * @param descriptor when given, narrows the answer to members the compiler could call with it
     * @return every member of {@code owner} that MCP calls {@code mcpName}, in the order the class declares them
     */
    public List<MemberRef> mcpToSrg(String owner, String mcpName, @Nullable String descriptor) {
        final Mappings table = mappings();
        final MemberRecord[] members = membersOf(owner);
        if (members.length == 0) {
            return List.of();
        }
        final List<MemberRef> found = new ArrayList<>();
        for (MemberRecord member : members) {
            if (descriptor != null && !descriptor.equals(member.descriptor())) {
                continue;
            }
            // a member MCP leaves alone is called the same thing in both worlds, so it answers to its own name
            final String mapped = table.mcpNameOf(member.name()).orElse(member.name());
            if (mapped.equals(mcpName)) {
                found.add(new MemberRef(owner, member.name(), member.descriptor(), member.method()));
            }
        }
        if (found.isEmpty() && table.state() == Mappings.State.ABSENT) {
            table.warnMissingOnce();
        }
        return found;
    }

    /** @return the archive whose class the loader would define for {@code internalName}, or null */
    @Nullable
    private File archiveOf(String internalName) {
        final File known = origins.get(internalName);
        if (known != null) {
            return known;
        }
        if (misses.contains(internalName)) {
            return null;
        }
        final File found = search(internalName);
        if (found != null) {
            origins.put(internalName, found);
            return found;
        }
        // Nothing answered under the order that was current. A jar added since then sits at the end of the search
        // order, so it can only answer names that had no answer before: re-read the order once before giving up.
        if (refreshOrder()) {
            final File late = search(internalName);
            if (late != null) {
                origins.put(internalName, late);
                return late;
            }
        }
        misses.add(internalName);
        return null;
    }

    @Nullable
    private File search(String internalName) {
        for (File archive : order) {
            final ClassIndex index = indexOf(archive);
            if (index != null && index.byName(internalName) != null) {
                return archive;
            }
        }
        return null;
    }

    @Nullable
    private Map<String, MemberRecord[]> memberTableOf(File archive) {
        final File key = archive.getAbsoluteFile();
        final Map<String, MemberRecord[]> known = memberTables.get(key);
        if (known != null) {
            return known;
        }
        if (withoutMembers.contains(key)) {
            return null;
        }
        final JarFingerprint fingerprint = fingerprintOf(key);
        final File file = fingerprint == null ? null
                : memberFileTarget(key, vanillaIndexFiles.get(key.getAbsoluteFile()));
        Map<String, MemberRecord[]> table = file == null ? null : MemberFile.read(file, fingerprint);
        if (table == null) {
            withoutMembers.add(key);
            return null;
        }
        memberTables.put(key, table);
        return table;
    }

    /**
     * @return where an archive's member table belongs: beside its record when it has one, and beside its index file
     *         otherwise, which is the case for the minecraft jar outside the game directory
     */
    @Nullable
    private File memberFileTarget(File archive, @Nullable File indexFile) {
        final File record = RecordFile.fileFor(gameDir, archive);
        if (record != null) {
            return new File(record.getPath() + ".members");
        }
        return indexFile == null ? null : memberCacheFile(indexFile);
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
        refreshOrder();
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
        // the class loader already has most of these open; asking it first is what keeps this a read, not a reopen
        try (BorrowedArchive borrow = BorrowedArchive.of(archive, false)) {
            return JarFingerprint.compute(Collections.list(borrow.jar().entries()));
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
