/*
 * Minecraft Forge
 * Copyright (c) 2016-2020.
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation version 2.1
 * of the License.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301  USA
 */

package net.minecraftforge.fml.common.discovery;

import net.minecraftforge.cleanmix.CleanMixModContainer;
import net.minecraftforge.fml.relauncher.FMLLaunchHandler;
import net.minecraftforge.fml.common.FMLLog;
import net.minecraftforge.fml.common.LanguageAdapterRegistry;
import com.google.common.base.Strings;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.SetMultimap;
import com.google.common.primitives.Ints;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;
import net.minecraftforge.classloading.FMLForgePlugin;
import net.minecraftforge.common.ForgeEarlyConfig;
import net.minecraftforge.common.ForgeVersion;
import net.minecraftforge.fml.common.*;
import net.minecraftforge.fml.common.asm.FMLSanityChecker;
import net.minecraftforge.fml.common.asm.transformers.ModAccessTransformer;
import net.minecraftforge.fml.common.discovery.asm.ASMModParser;
import net.minecraftforge.fml.common.discovery.cache.ClassScanRecord;
import net.minecraftforge.fml.common.discovery.cache.JarFingerprint;
import net.minecraftforge.fml.common.discovery.cache.JarScanRecord;
import net.minecraftforge.fml.common.discovery.cache.RecordFile;
import net.minecraftforge.fml.common.launcher.FMLTweaker;
import net.minecraftforge.fml.relauncher.CoreModManager;
import net.minecraftforge.fml.relauncher.libraries.LibraryManager;

import javax.annotation.Nullable;
import org.apache.commons.io.IOUtils;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.launch.platform.container.ContainerHandleURI;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.util.Constants.ManifestAttributes;
import zone.rong.mixinbooter.MixinBooterModContainer;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;

public final class ModDiscoverer {

    private static final ModDiscoverer INSTANCE = new ModDiscoverer();

    private static final String TWEAK_CLASS = ManifestAttributes.TWEAKER;
    private static final String MIXIN_CONFIGS = ManifestAttributes.MIXINCONFIGS;
    private static final String MIXIN_CONNECTOR = ManifestAttributes.MIXINCONNECTOR;
    private static final String MIXIN_TWEAKER = "org.spongepowered.asm.launch.MixinTweaker";
    private static final String FORCE_LOAD_AS_MOD = "ForceLoadAsMod";
    private static final String COREMOD_CONTAINS_FML_MOD = "FMLCorePluginContainsFMLMod";
    private static final String FML_CORE_PLUGIN = "FMLCorePlugin";
    /** Manifest attribute of a mod that used to rewrite other jars' classes at load time. No longer honoured. */
    private static final String BANSOUKOU = "Bansoukou";

    public static ModDiscoverer instance() {
        return INSTANCE;
    }

    private final List<ModContainer> builtInMods = List.of(new MixinBooterModContainer(), new ConfigAnytimeContainer());
    private final List<String> builtInModIds = builtInMods.stream().map(ModContainer::getModId).toList();
    private final SetMultimap<String, File> modIdToFiles = HashMultimap.create();
    private final SetMultimap<File, String> fileToModIds = LinkedHashMultimap.create();
    private final Map<File, DiscoveredMod> discoveredFiles = new LinkedHashMap<>();
    private final Map<File, JarScanRecord> scanRecords = new HashMap<>();
    private final Map<File, MetadataCollection> modMetadata = new HashMap<>();
    /** Bundled library id to the first declaration of it, for the report that two jars bundle different versions. */
    private final ASMDataTable asmDataTable = new ASMDataTable();

    private List<File> nonModLibs = List.of();

    private boolean hasForgeMods;

    private ModDiscoverer() {
        this.discover();
    }

    public boolean hasForgeMods() {
        return hasForgeMods;
    }

    public boolean isModPresent(String modId) {
        return modIdToFiles.containsKey(modId);
    }

    public Set<String> presentMods() {
        return Collections.unmodifiableSet(modIdToFiles.keySet());
    }

    public Set<File> modSources(String modId) {
        return Collections.unmodifiableSet(modIdToFiles.get(modId));
    }

    public String modFromSource(File source) {
        Set<String> ids = modsFromSource(source);
        return ids.isEmpty() ? null : ids.iterator().next();
    }

    public Set<String> modsFromSource(File source) {
        return Collections.unmodifiableSet(fileToModIds.get(source.getAbsoluteFile()));
    }

    /**
     * The early scan's record for {@code source}, or {@code null} when the source was not scanned. Callers must
     * treat {@code null} as "not classified yet", never as "not a mod".
     */
    @Nullable
    public DiscoveredMod discoveredMod(File source) {
        return discoveredFiles.get(source.getAbsoluteFile());
    }

    public boolean isModBuiltIn(String modId) {
        return this.builtInModIds.contains(modId);
    }

    public ASMDataTable getASMTable() {
        return asmDataTable;
    }

    public List<File> getNonModLibs() {
        return nonModLibs;
    }

    public void discoverMixinMods() {
        for (DiscoveredMod mod : discoveredFiles.values()) {
            if (!mod.hasMixinManifestAttributes()) {
                continue;
            }
            if (mod.coremod() == null) {
                try {
                    Launch.classLoader.addURL(mod.file().toURI().toURL());
                } catch (MalformedURLException e) {
                    FMLLog.log.error("Failed to manually load {} as a mixin mod {}.", mod.file().getName(), e);
                }
            }
            FMLLog.log.debug("Submitting mixin container for {}", mod.file().getName());
            MixinBootstrap.getPlatform().addContainer(new ContainerHandleURI(mod.file().toURI()));
        }
        // TODO: evaluate if this is a good place for this
        String devConfigs = System.getProperty("outlands.dev.mixin");
        if (!Strings.isNullOrEmpty(devConfigs)) {
            for (String singleMixinConfig : devConfigs.split(",")) {
                Mixins.addConfiguration(singleMixinConfig.trim());
            }
        }
    }

    public void discoverCoreMods(File minecraftDirectory, LaunchClassLoader classLoader, FMLTweaker tweaker) {
        File modsDir = setupCoreModDir(minecraftDirectory);
        findDerpMods(modsDir);

        FMLLog.log.debug("Discovering coremods");
        File containedDepsDir = new File(new File(Launch.minecraftHome, "mods"), ForgeVersion.mcVersion);
        for (DiscoveredMod discoveredMod : CoremodLoadOrder.sort(discoveredFiles.values(), this::loadOrderRank)) {
            if (discoveredMod.isDirectoryBased()) {
                FMLLog.log.debug("Ignoring folder {} in coremod searching", discoveredMod.file().getAbsolutePath());
                continue;
            }
            discoverCoreMod(classLoader, tweaker, discoveredMod, containedDepsDir);
        }
    }

    /** @return the declared coremod discovery position of {@code file}, {@code 0} when it declares none */
    private int loadOrderRank(File file) {
        MetadataCollection metadata = modMetadata.get(file.getAbsoluteFile());
        if (metadata == null) {
            return 0;
        }
        JarDeclaration declaration = metadata.getJarDeclaration();
        return declaration == null ? 0 : declaration.loadOrder().rank();
    }

    public IdentifiedMods identifyMods(ModClassLoader modClassLoader, List<String> injectedContainers, List<ModContainer> builtInMods) {
        List<ModCandidate> modCandidates = new ArrayList<>();
        List<File> nonModLibs = new ArrayList<>();

        List<ModContainer> mods = new ArrayList<>(builtInMods);
        FMLLog.log.debug("Building injected Mod Containers {}", injectedContainers);
        for (String containerClass : injectedContainers) {
            ModContainer modContainer;
            try {
                modContainer = (ModContainer) Class.forName(containerClass, true, modClassLoader).getConstructor().newInstance();
            } catch (Exception e) {
                FMLLog.log.error("A problem occurred instantiating the injected mod container {}", containerClass, e);
                throw new LoaderException(e);
            }
            mods.add(new InjectedModContainer(modContainer, modContainer.getSource()));
        }

        FMLLog.log.debug("Attempting to load mods contained in the minecraft jar file and associated classes");
        Set<File> seenCandidates = new HashSet<>();
        addClasspathCandidates(modClassLoader, modCandidates, seenCandidates);
        FMLLog.log.debug("Minecraft jar mods loaded successfully");
        addLibraryCandidates(modCandidates, seenCandidates);
        scanRecords.clear();
        modMetadata.clear();

        mods.addAll(exploreModCandidates(modCandidates, nonModLibs));
        this.nonModLibs = List.copyOf(nonModLibs);
        // Every container has been built by now, so no mod can still be missing an adapter: a registration from
        // here on would be invisible to mods that were already resolved. Close the registry instead of accepting it.
        LanguageAdapterRegistry.freeze();
        return new IdentifiedMods(mods, nonModLibs);
    }

    public void addBuiltInModContainers(List<ModContainer> mods, ModContainer minecraft, ModContainer mcp) {
        // Minecraft
        mods.add(minecraft);
        // Minecraft Coder Pack
        mods.add(new InjectedModContainer(mcp, new File("minecraft.jar")));
        // The loader itself
        mods.add(new InjectedModContainer(new LoaderContainer(), FMLSanityChecker.fmlLocation));
        mods.add(new InjectedModContainer(new CleanMixModContainer(), CleanMixModContainer.location()));
        // Included Mods
        this.builtInMods.stream().map(mc -> new InjectedModContainer(mc, FMLSanityChecker.fmlLocation)).forEach(mods::add);
    }

    public void rescueDroppedCoremods() {
        for (DiscoveredMod discovered : discoveredFiles.values()) {
            if (!discovered.mixinTweakerForceMod()) {
                continue;
            }
            String coremod = discovered.coremod();
            if (coremod == null || CoreModManager.isCoreModLoaded(coremod)) {
                continue;
            }
            if (CoreModManager.loadCoreModFromDiscoveredJar(Launch.classLoader, coremod, discovered.file())) {
                FMLLog.log.warn("{} declares both a MixinTweaker TweakClass and FMLCorePlugin. Forge skips the coremod in this case and {} was loaded by Outlands.", discovered.file().getName(), coremod);
            } else {
                FMLLog.log.error("Failed to manually load coremod {} from {}.", coremod, discovered.file().getName());
            }
            if (discovered.coreModContainsMod()) {
                CoreModManager.getReparseableCoremods().add(discovered.file().getName());
                FMLLog.log.warn("Added {} back to reparseable collection, ready to be identified as a mod.", discovered.file().getName());
            }
        }
    }

    private void discover() {
        FMLLog.log.info("Discovering mods");
        long startTime = System.nanoTime();

        for (File candidate : LibraryManager.getCandidates()) {
            scan(candidate);
        }
        for (URL url : Launch.classLoader.getURLs()) {
            try {
                File file = new File(url.toURI()).getAbsoluteFile();
                if (isArchive(file) && !discoveredFiles.containsKey(file)) {
                    scan(file);
                }
            } catch (URISyntaxException ignored) { }
        }

        long elapsedMillis = (System.nanoTime() - startTime) / 1_000_000L;
        FMLLog.log.info("Finished discovering mods. Found {} discovered mod ids in {} ms.", modIdToFiles.keySet().size(), elapsedMillis);
        FMLLog.log.debug("Discovered mod ids: {}", String.join(", ", modIdToFiles.keySet()));
    }

    private void scan(File file) {
        File absolute = file.getAbsoluteFile();
        DiscoveredMod cached = discoveredFiles.get(absolute);
        if (cached != null) {
            return;
        }
        if (!isArchive(absolute)) {
            // A candidate that is not an archive is a directory on the classpath: the output directory of the module
            // being developed, built against the current environment. It has no metadata to parse and no LWJGL2 code
            // to rewrite, so it is the one source classified as modern without a declaration.
            DiscoveredMod info = new DiscoveredMod(absolute, null, List.of(), ManifestAttributes.CLEANROOMMODTYPE, true, false, false, false, null, null);
            discoveredFiles.put(absolute, info);
            return;
        }

        Attributes attributes = null;
        Set<String> modIds = new LinkedHashSet<>();
        String modType = ManifestAttributes.FORGEMODTYPE;
        String coremod = null, tweaker = null;
        boolean modernMetadata = false;
        boolean hasMixinManifestAttributes = false, mixinTweakerForceMod = false, coreModContainsMod = false;

        try (JarFile jarFile = new JarFile(absolute)) {
            attributes = readManifestAttributes(absolute, jarFile);
            if (attributes != null) {
                modType = attributes.getValue(ManifestAttributes.MODTYPE);
                if (Strings.isNullOrEmpty(modType)) {
                    modType = ManifestAttributes.FORGEMODTYPE;
                }
                coremod = attributes.getValue(FML_CORE_PLUGIN);
                tweaker = attributes.getValue(TWEAK_CLASS);
                hasMixinManifestAttributes = attributes.getValue(MIXIN_CONFIGS) != null || attributes.getValue(MIXIN_CONNECTOR) != null;
                mixinTweakerForceMod = "true".equalsIgnoreCase(attributes.getValue(FORCE_LOAD_AS_MOD)) && MIXIN_TWEAKER.equals(tweaker);
                coreModContainsMod = attributes.getValue(COREMOD_CONTAINS_FML_MOD) != null;
                if (attributes.getValue(BANSOUKOU) != null) {
                    // The loader no longer honours this: distributing modified class files to overwrite the originals
                    // is what mixins exist for, and it never worked without also bypassing signatures. Say so once
                    // per jar instead of letting the jar silently do nothing.
                    FMLLog.log.warn("{} declares the Bansoukou attribute, which this loader no longer supports; "
                            + "the jar will load normally, but its class replacements will not be applied", file.getName());
                }
                if ("optifine.OptiFineForgeTweaker".equals(tweaker)) {
                    modIds.add("optifine");
                } else if ("net.jan.moddirector.launchwrapper.ModDirectorTweaker".equals(tweaker)) {
                    modIds.add("moddirector");
                } else if ("git.jbredwards.jsonpaintings.mod.asm.ASMHandler".equals(coremod)) {
                    modIds.add("jsonpaintings");
                }
            }
            // Read the metadata entry for every jar, not only for the ones whose mod id is still unknown: the jar
            // section describes the jar, so it has to be reachable for jars identified from their manifest too (those
            // are exactly the ones that need to declare load order). Ids are only taken from it when the manifest did
            // not already provide any, so the special cases above keep their single derived id.
            ZipEntry entry = jarFile.getEntry("mcmod.info");
            if (entry != null)
            {
                // Kept for the container discovery, which would otherwise read and parse the very same entry again
                MetadataCollection metadata = parseMcmodInfo(file, jarFile.getInputStream(entry));
                if (metadata != null) {
                    modMetadata.put(absolute, metadata);
                    if (modIds.isEmpty()) {
                        collectModIds(file, metadata, modIds);
                    }
                    JarDeclaration declaration = metadata.getJarDeclaration();
                    if (declaration != null) {
                        // Declaring the section is the jar putting itself in the current world: current metadata schema,
                        // current environment, no LWJGL2 API. That is what the LWJGL2 filter reads, see DiscoveredMod.
                        modernMetadata = true;
                        if (declaration.schemaVersion() > JarDeclaration.CURRENT_SCHEMA) {
                            FMLLog.log.warn("{} declares jar schema version {}, this loader understands {}; reading it as {}",
                                    file.getName(), declaration.schemaVersion(), JarDeclaration.CURRENT_SCHEMA, JarDeclaration.CURRENT_SCHEMA);
                        }
                        if (!declaration.unknownKeys().isEmpty()) {
                            FMLLog.log.warn("{} declares jar keys this loader does not understand: {}",
                                    file.getName(), declaration.unknownKeys());
                        }
                        // Collected here, instantiated only when a mod actually asks for the language: unlike the
                        // manifest keys read below, this is a claim about code that would run before any mod does.
                        if (!declaration.providers().isEmpty()) {
                            LanguageAdapterRegistry.declareProviders(declaration, absolute.getPath());
                        }
                        FMLLog.log.debug("{} declares {}", file.getName(), declaration);
                    }
                }
            }
            // Reading the cache is what makes the records worth producing; ignoring it falls back to the pre-cache
            // parse pattern (classes are only read when the mod id is still unknown) and produces no records.
            boolean readCache = !ForgeEarlyConfig.IGNORE_SCAN_CACHE;
            boolean needClasses = readCache || modIds.isEmpty();
            List<JarEntry> entries = needClasses ? Collections.list(jarFile.entries()) : List.of();
            JarFingerprint fingerprint = readCache ? JarFingerprint.compute(entries) : null;
            JarScanRecord scan = readCache ? RecordFile.read(RecordFile.fileFor(Launch.minecraftHome, absolute), fingerprint) : null;
            if (scan == null && needClasses) {
                List<ClassScanRecord> records = new ArrayList<>();
                List<Integer> indices = new ArrayList<>();
                if (collectRecords(entries, jarFile, records, indices) && readCache) {
                    scan = JarScanRecord.of(fingerprint, indices, records);
                }
                if (modIds.isEmpty()) {
                    collectModIds(records, modIds);
                }
            } else if (scan != null && modIds.isEmpty()) {
                collectModIds(List.of(scan.records()), modIds);
            }
            if (scan != null) {
                scanRecords.put(absolute, scan);
            }
            for (String modId : modIds) {
                if (recordMod(modId, absolute)) {
                    return;
                }
            }
        } catch (IOException e) {
            FMLLog.log.error("Failed to read mod metadata from {}", absolute.getName(), e);
        }

        DiscoveredMod info = new DiscoveredMod(
                absolute,
                attributes,
                List.copyOf(modIds),
                modType,
                modernMetadata,
                hasMixinManifestAttributes,
                mixinTweakerForceMod,
                coreModContainsMod,
                coremod,
                tweaker);
        discoveredFiles.put(absolute, info);

        if (!hasForgeMods && ManifestAttributes.FORGEMODTYPE.equals(info.modType())) {
            hasForgeMods = true;
        }
    }

    private void discoverCoreMod(LaunchClassLoader classLoader, FMLTweaker tweaker, DiscoveredMod discoveredMod, File containedDepsDir) {
        File file = discoveredMod.file();
        FMLLog.log.debug("Examining for coremod candidacy {}", file.getName());
        JarFile jar = null;
        Attributes attributes;
        String fmlCorePlugin;
        try {
            attributes = discoveredMod.attributes();
            if (attributes == null) {
                return;
            }

            String modSide = attributes.getValue(LibraryManager.MODSIDE);
            if (modSide != null && !"BOTH".equals(modSide) && !modSide.equals(FMLLaunchHandler.side().name())) {
                FMLLog.log.debug("Skipping {}, as we're on {} side and the mod is on {} side", file.getName(), FMLLaunchHandler.side(), modSide);
                CoreModManager.getIgnoredMods().add(file.getName());
                return;
            }

            String accessTransformers = attributes.getValue(ModAccessTransformer.FMLAT);
            if (accessTransformers != null && !accessTransformers.isEmpty()) {
                jar = new JarFile(file);
                ModAccessTransformer.addJar(jar, accessTransformers);
            }

            if (discoveredMod.tweaker() != null) {
                // Bundled libraries are put on the classpath before the tweaker runs, which is the same moment the old
                // NonModDeps attribute asked for. What changed is where the fact comes from: the jar's own declaration
                // says which of its bundled entries are libraries, instead of a jar-wide flag saying they all are.
                addDeclaredLibrariesToClasspath(classLoader, file, containedDepsDir);
                FMLLog.log.info("Cascading tweaker {} from {}", discoveredMod.tweaker(), file.getName());
                Integer sortOrder = Ints.tryParse(Strings.nullToEmpty(attributes.getValue("TweakOrder")));
                CoreModManager.injectDiscoveredCascadingTweaker(file, discoveredMod.tweaker(), classLoader, tweaker, sortOrder == null ? 0 : sortOrder);
                if (discoveredMod.mixinTweakerForceMod() || discoveredMod.coreModContainsMod()) {
                    CoreModManager.getReparseableCoremods().add(file.getName());
                }
                CoreModManager.getIgnoredMods().add(file.getName());
                return;
            }

            // The last reader of ModType, kept as it was: a jar built for a different loader is ignored rather than
            // rejected. It says nothing about the code inside - a jar declaring CRL was written while both LWJGL
            // generations were reachable - so the LWJGL2 filter decides on modernMetadata instead.
            String modType = discoveredMod.modType();
            if (!ManifestAttributes.FORGEMODTYPE.equals(modType) && !ManifestAttributes.CLEANROOMMODTYPE.equals(modType)) {
                FMLLog.log.warn("Adding {} to the list of things to skip. It is not an FML or Outlands mod, it is of type: {}", file.getName(), modType);
                CoreModManager.getIgnoredMods().add(file.getName());
                return;
            }

            fmlCorePlugin = discoveredMod.coremod();
            if (fmlCorePlugin == null) {
                return;
            }
            if (isCorePluginBlacklisted(fmlCorePlugin)) {
                CoreModManager.getIgnoredMods().add(file.getName());
                FMLLog.log.warn("Loading plugin {} for mod {} is in the forge_early.cfg blacklist and won't be loaded.", fmlCorePlugin, file.getName());
                return;
            }
        } catch (IOException ioe) {
            FMLLog.log.error("Unable to read the jar file {} - ignoring", file.getName(), ioe);
            return;
        } finally {
            try {
                if (jar != null) {
                    jar.close();
                }
            } catch (IOException ignored) { }
        }

        try {
            addDeclaredLibrariesToClasspath(classLoader, file, containedDepsDir);

            classLoader.addURL(file.toURI().toURL());

            if (!discoveredMod.coreModContainsMod() && !discoveredMod.mixinTweakerForceMod()) {
                FMLLog.log.trace("Adding {} to the list of known coremods, it will not be examined again", file.getName());
                CoreModManager.getIgnoredMods().add(file.getName());
            } else {
                FMLLog.log.debug("Found FMLCorePluginContainsFMLMod marker in {}.", file.getName());
                CoreModManager.getReparseableCoremods().add(file.getName());
            }
        } catch (MalformedURLException e) {
            FMLLog.log.error("Unable to convert file {} into a URL, weird", file.getName(), e);
            return;
        }

        CoreModManager.loadCoreModFromDiscoveredJar(classLoader, fmlCorePlugin, file);
    }

    /**
     * Puts the bundled entries a jar marked as libraries on the classpath, before its tweaker or coremod runs.
     *
     * <p>This is the job the {@code NonModDeps} manifest attribute used to do, at the same moment and for the same
     * reason: a coremod that ships a library it needs during {@code injectIntoClassLoader} cannot wait for the general
     * classpath injection, which happens long after coremods are loaded.
     *
     * <p>The difference is the granularity. {@code NonModDeps} was one flag for the whole jar, so a jar bundling a
     * prerequisite mod and a library had to choose; the declaration names each entry. Entries not marked as libraries are
     * left alone - they are extracted into the mods directory and discovered like any other mod.
     */
    private static void addDeclaredLibrariesToClasspath(LaunchClassLoader classLoader, File source, File containedDepsDir)
            throws MalformedURLException {
        for (JarDeclaration.ContainedDep dep : LibraryManager.declaredLibrariesOf(source)) {
            if (LibraryManager.isBundledLibraryShadowed(source, dep)) {
                // Not a failure: another jar bundles a higher version of the same library, and that one is on the
                // classpath instead. Saying so at debug keeps the decision traceable without alarming anyone.
                FMLLog.log.debug("{} bundles the library {}, which lost to another jar's higher version",
                        source.getName(), dep.id());
                continue;
            }
            // Either a URL into the parent jar or a file that was extracted beside the mods, depending on the mode.
            java.net.URL library = LibraryManager.bundledLibraryUrl(source, dep);
            if (library == null) {
                // Said out loud rather than skipped: the jar promised the dependency and the coremod is about to run
                // without it, which is the failure mode this early injection exists to prevent.
                FMLLog.log.warn("{} declares the bundled library {}, but it is neither mounted nor extracted",
                        source.getName(), dep.path());
                continue;
            }
            classLoader.addURL(library);
        }
    }

    private static boolean isCorePluginBlacklisted(String fmlCorePlugin) {
        for (String plugin : ForgeEarlyConfig.LOADING_PLUGIN_BLACKLIST) {
            if (plugin.equals(fmlCorePlugin)) {
                return true;
            }
        }
        return false;
    }

    private static File setupCoreModDir(File minecraftDirectory) {
        File coreModDir = new File(minecraftDirectory, "mods");
        try {
            coreModDir = coreModDir.getCanonicalFile();
        } catch (IOException e) {
            throw new RuntimeException(String.format("Unable to canonicalize the coremod dir at %s", minecraftDirectory.getName()), e);
        }
        if (!coreModDir.exists()) {
            coreModDir.mkdir();
        } else if (!coreModDir.isDirectory()) {
            throw new RuntimeException(String.format("Found a coremod file in %s that's not a directory", minecraftDirectory.getName()));
        }
        return coreModDir;
    }

    private void findDerpMods(File directory) {
        Path dirPath = directory.toPath();
        List<Path> derpMods = new ArrayList<>(), extractedPaths = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(dirPath, 2)) {
            stream.forEach(path -> {
                String name = path.getFileName().toString();
                if (name.endsWith(".jar.zip") && dirPath.equals(path.getParent())) {
                    derpMods.add(path);
                } else if ("META-INF".equals(name) && Files.isDirectory(path)) {
                    extractedPaths.add(path);
                }
            });
        } catch (IOException e) {
            FMLLog.log.error("Unable to scan {} for problematic mod files", directory.getName(), e);
        }

        if (!derpMods.isEmpty()) {
            FMLLog.log.error("Outlands detected files with .jar.zip as the extension. These are usually failed downloads or jars renamed as zips. Download them again as .jar files before continuing.");
            for (Path derpMod : derpMods) {
                FMLLog.log.error("Problem file: {}", derpMod.getFileName());
            }
            throw new RuntimeException("Outlands detected files with .jar.zip as the extension! Check the logs for more information!");
        }
        if (!extractedPaths.isEmpty()) {
            FMLLog.log.error("Outlands detected META-INF directories. These look like extracted mod jars and cannot be loaded correctly.");
            FMLLog.log.error("Remove these extracted directories and add the jar directly into the mods directory.");
            for (Path extractedPath : extractedPaths) {
                FMLLog.log.error("Directory {} contains META-INF entries.", extractedPath.getFileName());
            }
            throw new RuntimeException("Outlands detected extracted mod jars! Check the logs for more information!");
        }
    }


    private List<ModContainer> exploreModCandidates(List<ModCandidate> modCandidates, List<File> nonModLibs) {
        List<ModContainer> modList = new ArrayList<>();
        for (ModCandidate candidate : modCandidates) {
            try {
                List<ModContainer> mods = candidate.explore(asmDataTable);
                if (mods.isEmpty() && !candidate.isClasspath()) {
                    nonModLibs.add(candidate.getModContainer());
                } else {
                    modList.addAll(mods);
                }
            } catch (LoaderException le) {
                FMLLog.log.warn("Identified a problem with the mod candidate {}, ignoring this source", candidate.getModContainer(), le);
            }
        }
        return modList;
    }

    private void addCandidate(List<ModCandidate> modCandidates, Set<File> seen, ModCandidate candidate) {
        if (seen.add(candidate.getModContainer())) {
            candidate.setScanRecord(scanRecords.get(candidate.getModContainer().getAbsoluteFile()));
            candidate.setMetadata(modMetadata.get(candidate.getModContainer().getAbsoluteFile()));
            modCandidates.add(candidate);
        } else {
            FMLLog.log.trace("  Skipping already in list {}", candidate.getModContainer());
        }
    }

    private void addClasspathCandidates(ModClassLoader modClassLoader, List<ModCandidate> modCandidates, Set<File> seen) {
        Set<String> knownLibraries = new HashSet<>();
        knownLibraries.addAll(modClassLoader.getDefaultLibraries());
        knownLibraries.addAll(CoreModManager.getIgnoredMods());
        knownLibraries.addAll(CoreModManager.getReparseableCoremods());

        File[] minecraftSources = modClassLoader.getParentSources();
        List<File> devPaths = new ArrayList<>();
        for (File source : minecraftSources) {
            if (source.isFile()) {
                if (knownLibraries.contains(source.getName()) || modClassLoader.isDefaultLibrary(source)) {
                    FMLLog.log.trace("Skipping known library file {}", source.getAbsolutePath());
                } else {
                    FMLLog.log.debug("Found a minecraft related file at {}, examining for mod candidates", source.getAbsolutePath());
                    addCandidate(modCandidates, seen, new ModCandidate(source, source, ContainerType.JAR, false, true));
                }
            } else if (source.isDirectory()) {
                FMLLog.log.debug("Found a minecraft related directory at {}, collecting as developing mod", source.getAbsolutePath());
                devPaths.add(source);
            }
        }

        Map<String, List<File>> pathsBySourceSet = new HashMap<>();
        for (File path : devPaths) {
            if (path.equals(FMLForgePlugin.forgeLocation)) {
                continue;
            }
            pathsBySourceSet.computeIfAbsent(path.getName(), key -> new ArrayList<>()).add(path);
        }
        for (List<File> paths : pathsBySourceSet.values()) {
            List<File> classDirs = new ArrayList<>();
            File resourcesDir = null;
            for (File path : paths) {
                String type = path.getParentFile().getName();
                if ("resources".equals(type)) {
                    resourcesDir = path;
                } else {
                    classDirs.add(path);
                }
            }
            if (resourcesDir == null) {
                continue;
            }
            for (File classDir : classDirs) {
                FMLLog.log.debug("Adding path {} and {} as developing mod", classDir, resourcesDir);
                addCandidate(modCandidates, seen, new ModCandidate(classDir, resourcesDir));
            }
        }
    }

    private void addLibraryCandidates(List<ModCandidate> modCandidates, Set<File> seen) {
        for (DiscoveredMod discoveredMod : discoveredFiles.values()) {
            File mod = discoveredMod.file();
            if (CoreModManager.getIgnoredMods().contains(mod.getName()) && !CoreModManager.getReparseableCoremods().contains(mod.getName())) {
                FMLLog.log.trace("Skipping already parsed coremod or tweaker {}", mod.getName());
            } else if (mod.isDirectory()) {
                FMLLog.log.trace("Skipping directory {}", mod.getName());
            } else {
                FMLLog.log.debug("Found a candidate zip or jar file {}", mod.getName());
                addCandidate(modCandidates, seen, new ModCandidate(mod, mod, ContainerType.JAR));
            }
        }
    }

    private static boolean isArchive(File file) {
        if (!file.isFile()) {
            return false;
        }
        String name = file.getName();
        return name.endsWith(".jar") || name.endsWith(".zip");
    }

    private Attributes readManifestAttributes(File file, JarFile jarFile) {
        File externalManifest = new File(file.getAbsolutePath() + ".meta");
        if (!LibraryManager.DISABLE_EXTERNAL_MANIFEST && externalManifest.isFile()) {
            try (FileInputStream input = new FileInputStream(externalManifest)) {
                return new Manifest(input).getMainAttributes();
            } catch (IOException e) {
                FMLLog.log.error("Error reading {} as an external manifest.", externalManifest.getAbsolutePath(), e);
            }
        }
        try {
            Manifest manifest = jarFile.getManifest();
            return manifest == null ? null : manifest.getMainAttributes();
        } catch (IOException e) {
            FMLLog.log.error("Error reading manifest from {}.", file.getAbsolutePath(), e);
        }
        return null;
    }

    private boolean recordMod(String modId, File source) {
        if (this.builtInModIds.contains(modId)) {
            return true;
        }
        File abs = source.getAbsoluteFile();
        modIdToFiles.put(modId, abs);
        fileToModIds.put(abs, modId);
        return false;
    }

    /** Parses the metadata entry; ids are collected by the caller, which knows whether they are wanted. */
    private MetadataCollection parseMcmodInfo(File file, InputStream stream) {
        try {
            return MetadataCollection.from(stream, file.getName());
        } catch (LoaderException e) {
            // A declaration this loader refuses to read is not a parse failure to be tolerated: the jar claimed to
            // follow the current schema and then did not, so continuing would silently drop what it declared.
            throw e;
        } catch (Throwable t) {
            FMLLog.log.error("Failed to parse mcmod.info for {}", file.getName(), t);
            return null;
        } finally {
            IOUtils.closeQuietly(stream);
        }
    }

    private static void collectModIds(File file, MetadataCollection metadata, Set<String> ids) {
        for (String id : metadata.getIds()) {
            if (id == null || id.isBlank()) {
                FMLLog.log.warn("Skipping null/blank mod id from {}", file.getName());
                continue;
            }
            ids.add(id);
        }
    }

    private static boolean collectRecords(List<JarEntry> entries, JarFile jar, List<ClassScanRecord> out, List<Integer> indices) {
        List<JarEntry> scanned = new ArrayList<>();
        for (JarEntry entry : entries) {
            if (ITypeDiscoverer.shouldScan(entry.getName())) {
                scanned.add(entry);
            }
        }
        // Reading and parsing the class bytes runs on several threads; the loop below keeps central-directory order,
        // so the records of a jar are exactly the ones a sequential scan would have produced.
        List<ParallelJarParse.Parsed> parsed = ParallelJarParse.parse(scanned, jar);
        boolean complete = true;
        for (int ordinal = 0; ordinal < parsed.size(); ordinal++) {
            ParallelJarParse.Parsed result = parsed.get(ordinal);
            if (result.failure() != null) {
                // Same tolerance as before: a broken entry contributes nothing. The whole record is dropped so that
                // the container discovery re-reads the jar and reports the problem where it always did.
                complete = false;
                continue;
            }
            ASMModParser parser = result.parser();
            parser.validate();
            ClassScanRecord record = parser.toRecord();
            if (JarScanRecord.hasContent(record)) {
                indices.add(ordinal);
                out.add(record);
            }
        }
        return complete;
    }

    private static void collectModIds(List<ClassScanRecord> records, Set<String> modIds) {
        for (ClassScanRecord record : records) {
            for (ClassScanRecord.Annotation annotation : record.annotations()) {
                if (!ModContainerFactory.hasType(annotation)) {
                    continue;
                }
                Object modId = annotation.values() == null ? null : annotation.values().get("modid");
                if (modId instanceof String stringModId && !stringModId.isEmpty()) {
                    modIds.add(stringModId);
                }
            }
        }
    }
}
