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
import net.minecraftforge.fml.common.asm.transformers.AtRule;
import net.minecraftforge.fml.common.discovery.cache.ClassDetail;
import net.minecraftforge.fml.common.discovery.cache.ClassScanRecord;
import net.minecraftforge.fml.common.discovery.cache.DetailFile;
import net.minecraftforge.fml.common.discovery.cache.DetailRecord;
import net.minecraftforge.fml.common.discovery.cache.JarFingerprint;
import net.minecraftforge.fml.common.discovery.cache.JarScanRecord;
import net.minecraftforge.fml.common.discovery.cache.MemberFile;
import net.minecraftforge.fml.common.discovery.cache.MemberRecord;
import net.minecraftforge.fml.common.discovery.cache.RecordFile;

import top.outlands.foundation.boot.ArchiveIndex;
import top.outlands.foundation.boot.ArchiveSet;

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
    /** Per-archive detail, read on demand like the member tables: only a language tool ever asks for it. */
    private final Map<File, Map<String, ClassDetail>> detailTables = new ConcurrentHashMap<>();
    private final Map<File, AtRule[]> atRules = new ConcurrentHashMap<>();
    private final Set<File> withoutDetail = ConcurrentHashMap.newKeySet();
    /** Archives indexed from a file outside the per-jar cache tree, mapped to the index file that was used. */
    private final Map<File, File> vanillaIndexFiles = new ConcurrentHashMap<>();

    /** Null until a caller replaces the process table; a test does, a run does not. */
    @Nullable
    private Mappings mappingTable;

    /** Set when the minecraft jar is identified, so the asset view knows which archive is vanilla. */
    private volatile File vanillaArchive;
    /** What the vanilla index was built for; part of the manifest's chunk key, because it names the mapping. */
    @Nullable
    private volatile String mappingSalt;
    /** Fingerprints are a central-directory walk each; the manifest asks for every archive, so keep them. */
    private final Map<File, JarFingerprint> fingerprints = new ConcurrentHashMap<>();
    /** Created on first use; the export has no state of its own besides the single flight it guards. */
    private volatile IndexExport exporter;
    /** Built once per index; vanilla's asset answer does not change while the process runs. */
    private volatile AssetView vanillaAssets;

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
        detailTables.remove(key);
        atRules.remove(key);
        withoutDetail.remove(key);
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
                mappingSalt = salt;
                vanillaArchive = archive.getAbsoluteFile();
                vanillaAssets = null;
                return true;
            }
        }
        return false;
    }

    /** @return the archive the minecraft classes were read from, or null when this run did not identify one */
    @Nullable
    public File vanillaArchive() {
        return vanillaArchive;
    }

    /**
     * @return vanilla's assets as one view, the downloaded objects first and the jar second, in the order the game
     *         reads them; empty when neither half is known
     */
    public AssetView vanillaAssets() {
        AssetView known = vanillaAssets;
        if (known != null) {
            return known;
        }
        final AssetObjects objects = AssetObjects.fromLauncher();
        final File jar = vanillaArchive;
        if (jar == null) {
            // without the jar the downloaded half still answers for the sounds and languages it names
            known = objects.available() ? AssetView.of(List.of(AssetLayer.objects(objects))) : AssetView.NONE;
        } else {
            known = AssetView.vanilla(jar, objects);
        }
        vanillaAssets = known;
        return known;
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
                    DetailFile.write(new File(memberFile.getPath() + ".detail"), scanned.detail());
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

    /** @param reason what the manifest is being written for, {@code discovery} or {@code dump} */
    public void writeManifest(String reason) {
        IndexManifest.write(gameDir, this, reason);
    }

    /** Publishes the manifest without making the start wait for its hashes; nothing in the game reads it. */
    public void writeManifestAsync(String reason) {
        IndexManifest.writeAsync(gameDir, this, reason);
    }

    /** @return the handle a mod uses to ask for the file form of what only this process can produce */
    public IndexExport export() {
        IndexExport known = exporter;
        if (known == null) {
            synchronized (this) {
                known = exporter;
                if (known == null) {
                    known = new IndexDumper(this);
                    exporter = known;
                }
            }
        }
        return known;
    }

    /** @return the directory this index describes; the manifest and the per-jar files are placed relative to it */
    File gameDir() {
        return gameDir;
    }

    /** @return the salt the vanilla index was built with, or an empty string when it was not identified */
    String mappingSalt() {
        final String salt = mappingSalt;
        return salt == null ? "" : salt;
    }

    /** @return the fingerprint of an archive, remembered for the process; null when it cannot be read */
    @Nullable
    JarFingerprint fingerprintFor(File archive) {
        final File key = archive.getAbsoluteFile();
        final JarFingerprint known = fingerprints.get(key);
        if (known != null) {
            return known;
        }
        // Discovery already computed one for every archive it scanned, which is most of a class path: reuse it
        // instead of folding the central directory again for the manifest.
        final JarScanRecord scan = scans.get(key);
        final JarFingerprint fingerprint = scan != null ? scan.fingerprint() : fingerprintOf(key);
        if (fingerprint != null) {
            fingerprints.put(key, fingerprint);
        }
        return fingerprint;
    }

    /** @return the header file of an archive, which is where the vanilla ones live outside the game directory */
    @Nullable
    File headersFileFor(File archive) {
        final File vanilla = vanillaIndexFiles.get(archive.getAbsoluteFile());
        if (vanilla != null) {
            return vanilla;
        }
        return RecordFile.fileFor(gameDir, archive);
    }

    /**
     * @param internalName a name such as {@code net/minecraft/world/World}
     * @return the generic signatures, nested class identity and member signatures of that class; empty when the
     *         archive has no detail file (never asked for) or the class has none of it
     */
    public ClassDetail detailOf(String internalName) {
        final File origin = archiveOf(internalName);
        if (origin == null) {
            return ClassDetail.EMPTY;
        }
        final ClassDetail detail = detailTableOf(origin).get(internalName);
        return detail == null ? ClassDetail.EMPTY : detail;
    }

    /** @return every archive's access transformer rules, unioned in class path order */
    public List<AtRule> atRules() {
        final List<AtRule> result = new ArrayList<>();
        for (File archive : order()) {
            if (!archive.isDirectory()) {
                result.addAll(List.of(atRulesOf(archive)));
            }
        }
        return result;
    }

    private AtRule[] atRulesOf(File archive) {
        detailTableOf(archive);
        final AtRule[] rules = atRules.get(archive.getAbsoluteFile());
        return rules == null ? new AtRule[0] : rules;
    }

    /** @return the detail of one archive, read on demand and remembered; empty when it has no file */
    private Map<String, ClassDetail> detailTableOf(File archive) {
        final File key = archive.getAbsoluteFile();
        final Map<String, ClassDetail> known = detailTables.get(key);
        if (known != null) {
            return known;
        }
        if (withoutDetail.contains(key)) {
            return Map.of();
        }
        final File file = detailFileFor(key);
        final JarFingerprint fingerprint = fingerprintFor(key);
        final DetailRecord record = file == null || fingerprint == null ? null : DetailFile.read(file, fingerprint);
        if (record == null) {
            withoutDetail.add(key);
            return Map.of();
        }
        detailTables.put(key, record.classes());
        atRules.put(key, record.atRules());
        return record.classes();
    }

    /** @return the detail file of an archive, which for vanilla sits next to its index outside the game directory */
    @Nullable
    File detailFileFor(File archive) {
        final File headers = headersFileFor(archive);
        return headers == null ? null : new File(headers.getPath() + ".detail");
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

    // ---- 资源：同一个 L0，按层序回答"玩家看到的是哪一份" ----

    /** @return a view over the current search order, which is the class path and not the resource pack stack */
    public AssetView assets() {
        return AssetView.archives(order);
    }

    /**
     * @param layers the order to resolve in, first wins
     * @return a view over exactly those layers
     */
    public AssetView assets(List<File> layers) {
        return AssetView.archives(layers);
    }

    /**
     * Calls {@code sink} with every entry under {@code pathPrefix}, archive by archive, in search order.
     *
     * <p>This is the enumeration a class graph library pays for by opening every jar it was pointed at; here the
     * archives are the ones the class loader already has open, so a full pass costs one walk of each central
     * directory and nothing else.
     *
     * @param classesOnly whether to skip everything that is not a class file
     * @return how many entries matched
     */
    public int scan(String pathPrefix, boolean classesOnly, EntrySink sink) {
        int found = 0;
        for (File archive : order) {
            // a scan enumerates; it does not look anything up, so an archive the class loader does not have is opened
            // for its entry list and closed again rather than indexed (the index would walk the entries a second time
            // to build the package table)
            final ArchiveIndex shared = ArchiveSet.classPath().of(archive.toPath());
            if (shared != null) {
                found += scan(archive, shared.jarFile(), pathPrefix, classesOnly, sink);
                continue;
            }
            try (java.util.jar.JarFile jar = new java.util.jar.JarFile(archive, false)) {
                found += scan(archive, jar, pathPrefix, classesOnly, sink);
            } catch (IOException | RuntimeException e) {
                FMLLog.log.debug("Could not scan {}", archive, e);
            }
        }
        return found;
    }

    private static int scan(File archive, java.util.jar.JarFile jar, String pathPrefix, boolean classesOnly,
            EntrySink sink) {
        int found = 0;
        // enumerated in place: a catalog walks every entry of every layer, and materialising that list per layer per
        // call was the largest allocation the listing did
        for (final java.util.Iterator<java.util.jar.JarEntry> entries =
                java.util.Collections.list(jar.entries()).iterator(); entries.hasNext(); ) {
            final java.util.jar.JarEntry entry = entries.next();
            final String name = entry.getName();
            if (!name.startsWith(pathPrefix) || name.endsWith("/")) {
                continue;
            }
            if (classesOnly && !name.endsWith(".class")) {
                continue;
            }
            sink.accept(archive, name);
            found++;
        }
        return found;
    }

    /** Receives one entry of a scan: the archive it is in and its path inside it. */
    @FunctionalInterface
    public interface EntrySink {
        void accept(File archive, String name);
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
            final String known = table.mcpNameOrNull(member.name());
            final String mapped = known == null ? member.name() : known;
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
            // an Enumeration and not a list: the fingerprint folds the entries in one pass and nothing else needs them
            return JarFingerprint.compute(borrow.jar().entries());
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
