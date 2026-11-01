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

package net.minecraftforge.fml.relauncher.libraries;

import java.io.*;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.commons.lang3.tuple.Pair;
import org.apache.maven.artifact.versioning.ArtifactVersion;

import com.google.common.io.ByteStreams;
import com.google.common.io.Files;
import net.minecraft.launchwrapper.Launch;
import net.minecraftforge.common.ForgeEarlyConfig;
import net.minecraftforge.common.ForgeVersion;
import net.minecraftforge.fml.common.FMLLog;
import net.minecraftforge.fml.common.MetadataCollection;
import net.minecraftforge.fml.common.discovery.BundledLibrarySelection;
import net.minecraftforge.fml.common.discovery.JarDeclaration;
import net.minecraftforge.fml.relauncher.FMLLaunchHandler;

public class LibraryManager
{
    public static final boolean DISABLE_EXTERNAL_MANIFEST = Boolean.parseBoolean(System.getProperty("forge.disable_external_manifest", "false"));
    public static final boolean ENABLE_AUTO_MOD_MOVEMENT = Boolean.parseBoolean(System.getProperty("forge.enable_auto_mod_movement", "false"));
    private static final String LIBRARY_DIRECTORY_OVERRIDE = System.getProperty("forge.lib_folder", null);
    private static final List<String> skipContainedDeps = Arrays.asList(System.getProperty("fml.skipContainedDeps","").split(",")); //TODO: Is this used by anyone in the real world? TODO: Remove in 1.13.
    private static final FilenameFilter MOD_FILENAME_FILTER  = (dir, name) -> name.endsWith(".jar") || name.endsWith(".zip"); //TODO: Disable support for zip in 1.13
    /**
     * Whether jars one directory deeper are collected too.
     *
     * <p>Read from the config on demand rather than captured in a static initialiser: this class is initialised during
     * launch, and a static read would silently depend on whether the config happened to be parsed first.
     */
    private static boolean oneLevelScan()
    {
        return ForgeEarlyConfig.MOD_SCAN_DEPTH == ForgeEarlyConfig.ModScanDepth.ONE_LEVEL;
    }

    public static final Attributes.Name MODSIDE = JarDeclaration.MANIFEST_SIDE;
    public static final Attributes.Name MODCONTAINSDEPS = new Attributes.Name("ContainedDeps");
    private static final Attributes.Name MAVEN_ARTIFACT = new Attributes.Name("Maven-Artifact");
    private static final Attributes.Name TIMESTAMP = new Attributes.Name("Timestamp");
    private static final Attributes.Name MD5 = new Attributes.Name("MD5");
    private static Repository libraries_dir = null;
    private static Set<File> processed = new HashSet<File>();
    private static File minecraftHome;
    private static List<File> candidates;
    /**
     * Mods that bundle other jars, recorded while the mods directory is cleaned and extracted the first time the loader
     * asks which files exist.
     *
     * <p>Deferred because extraction is an assembly step, not part of tidying a directory: doing it here means the jar is
     * opened once, at the moment its bundled jars become relevant, instead of as a side effect of a method named after
     * cleaning.
     */
    private static final Set<File> pendingBundledDeps = new LinkedHashSet<>();
    /**
     * Bundled jars a declaration marked as libraries: extracted next to the mods, but never offered as candidates.
     *
     * <p>Kept so the early classpath injection can find them. They are on disk rather than virtual because a coremod may
     * want a {@code File} for its own reasons, and because not extracting them would only pay off together with the rest
     * of the zip filesystem work.
     */
    private static final Set<File> bundledLibraries = new LinkedHashSet<>();
    /**
     * Set while the mods directory is being cleaned. Extraction of bundled jars is an assembly step, so it is recorded
     * and performed the first time the loader asks which files exist rather than while a method named after tidying is
     * running.
     */
    private static boolean deferringBundledDeps;

    /**
     * Which bundled copy of each library goes on the classpath. Computed once, immediately before extraction, because
     * every candidate has to be known before any of them can be chosen.
     */
    private static BundledLibrarySelection bundledSelection = BundledLibrarySelection.empty();

    /**
     * Bundled libraries that are served straight out of their parent jar rather than extracted.
     *
     * <p>Keyed by the file name the declaration names, which is also how the early injection looks them up.
     */
    private static final Map<String, URL> mountedLibraries = new LinkedHashMap<>();
    /** The list bundled jars are registered with once extraction runs, kept from setup so the two phases agree. */
    private static ModList deferredModList;

    public static void setup(File minecraftHome)
    {
        LibraryManager.minecraftHome = minecraftHome;
        File libDir = findLibraryFolder(minecraftHome);
        FMLLog.log.debug("Determined Minecraft Libraries Root: {}", libDir);
        Repository old = Repository.replace(libDir, "libraries");
        if (old != null)
            FMLLog.log.debug("  Overwriting Previous: {}", old);
        libraries_dir = Repository.get("libraries");

        File mods = new File(minecraftHome, "mods");
        File mods_ver = new File(mods, ForgeVersion.mcVersion);

        ModList memory = null;
        if (!ENABLE_AUTO_MOD_MOVEMENT)
        {
            Repository repo = new LinkRepository(new File(mods, "memory_repo"));
            memory = new MemoryModList(repo);
            ModList.cache.put("MEMORY", memory);
            Repository.cache.put("MEMORY", repo);
        }

        for (File dir : new File[]{mods, mods_ver})
            cleanDirectory(dir, ENABLE_AUTO_MOD_MOVEMENT ? ModList.create(new File(dir, "mod_list.json"), minecraftHome) : memory, mods_ver, mods);

        deferredModList = memory;
        deferringBundledDeps = true;
        try
        {
            for (ModList list : ModList.getKnownLists(minecraftHome))
            {
                Repository repo = list.getRepository() == null ? libraries_dir : list.getRepository();
                List<Artifact> artifacts = list.getArtifacts();
                // extractPacked adds artifacts to the list. As such, we can't use an Iterator to traverse it.
                for (int i = 0; i < artifacts.size(); i++)
                {
                    Artifact artifact = artifacts.get(i);
                    Artifact resolved = repo.resolve(artifact);
                    if (resolved != null)
                    {
                        File target = repo.getFile(resolved.getPath());
                        if (target.exists())
                            extractPacked(target, list, mods_ver, mods);
                    }
                }
            }
        }
        finally
        {
            deferringBundledDeps = false;
        }
        candidates = null;

    }

    /**
     * Extracts the bundled jars recorded during setup.
     *
     * <p>Runs on the first request for candidates, which is the last moment before the loader decides what exists - late
     * enough that extraction is not a side effect of tidying the mods directory, early enough that a bundled prerequisite
     * mod is still discovered like any other.
     *
     * <p>Idempotent: the recorded set is drained first, so a second call does nothing.
     */
    private static void extractPendingBundledDeps()
    {
        if (pendingBundledDeps.isEmpty())
        {
            return;
        }
        List<File> pending = new ArrayList<>(pendingBundledDeps);
        pendingBundledDeps.clear();

        bundledSelection = BundledLibrarySelection.select(collectLibraryClaims(pending));
        for (String note : bundledSelection.notes())
        {
            FMLLog.log.warn(note);
        }
        // Named once per launch so a bug report says which mode produced it.
        FMLLog.log.info("Bundled libraries are handled by {}", NestedLibraryJars.enabled()
                ? "serving them from inside their parent jar (no extraction)"
                : "extracting them next to the mods (-Doutlands.nestedJars=extract)");

        File mods = new File(minecraftHome, "mods");
        File mods_ver = new File(mods, ForgeVersion.mcVersion);
        ModList list = deferredModList == null ? ModList.cache.get("MEMORY") : deferredModList;
        for (File file : pending)
        {
            if (!file.isFile())
            {
                continue;
            }
            try (JarFile jar = new JarFile(file))
            {
                extractPacked(jar, list, mods_ver, mods);
            }
            catch (IOException e)
            {
                FMLLog.log.error("Unable to read {} while extracting its bundled jars", file.getName(), e);
            }
        }
    }

    private static File findLibraryFolder(File minecraftHome)
    {
        if (LIBRARY_DIRECTORY_OVERRIDE != null)
        {
            FMLLog.log.error("System variable set to override Library Directory: {}", LIBRARY_DIRECTORY_OVERRIDE);
            return new File(LIBRARY_DIRECTORY_OVERRIDE);
        }

        CodeSource source = ArtifactVersion.class.getProtectionDomain().getCodeSource();
        if (source == null)
        {
            FMLLog.log.error("Unable to determine codesource for {}. Using default libraries directory.", ArtifactVersion.class.getName());
            return new File(minecraftHome, "libraries");
        }

        try
        {
            File apache = new File(source.getLocation().toURI());
            if (apache.isFile())
                apache = apache.getParentFile(); //Get to a directory, this *should* always be the case...
            apache = apache.getParentFile(); //Skip the version folder. In case we ever update the version, I don't want to edit this code again.
            String comp = apache.getAbsolutePath().toLowerCase(Locale.ENGLISH).replace('\\', '/');
            if (!comp.endsWith("/"))
                comp += '/';

            if (!comp.endsWith("/org/apache/maven/maven-artifact/"))
            {
                FMLLog.log.error("Apache Maven library folder was not in the format expected. Using default libraries directory.");
                FMLLog.log.error("Full: {}", new File(source.getLocation().toURI()));
                FMLLog.log.error("Trimmed: {}", comp);
                return new File(minecraftHome, "libraries");
            }
            //     maven-artifact  /maven          /apache         /org            /libraries
            return apache          .getParentFile().getParentFile().getParentFile().getParentFile();
        }
        catch (URISyntaxException e)
        {
            FMLLog.log.error(FMLLog.log.getMessageFactory().newMessage("Unable to determine file for {}. Using default libraries directory.", ArtifactVersion.class.getName()), e);
        }

        return new File(minecraftHome, "libraries"); //Everything else failed, return the default.
    }

    private static void cleanDirectory(File dir, ModList modlist, File... modDirs)
    {
        if (!dir.exists())
            return;

        FMLLog.log.debug("Cleaning up mods folder: {}", dir);
        for (File file : dir.listFiles(f -> f.isFile() && f.getName().endsWith(".jar")))
        {
            Pair<Artifact, byte[]> ret = extractPacked(file, modlist, modDirs);
            if (ret != null)
            {
                Artifact artifact = ret.getLeft();
                Repository repo = modlist.getRepository() == null ? libraries_dir : modlist.getRepository();
                File moved = repo.archive(artifact, file, ret.getRight());
                processed.add(moved);
            }
        }

        try
        {
            if (modlist.changed())
                modlist.save();
        }
        catch (IOException e)
        {
            FMLLog.log.error(FMLLog.log.getMessageFactory().newMessage("Error updating modlist file {}", modlist.getName()), e);
        }
    }

    /**
     * Reads every pending jar's declarations and returns the libraries they claim to bundle.
     *
     * <p>Reads once, before anything is extracted, because choosing a winner needs all the candidates at the same time.
     * That is the whole reason this is a separate pass rather than a check at extraction time.
     */
    private static List<BundledLibrarySelection.Claim> collectLibraryClaims(List<File> pending)
    {
        List<BundledLibrarySelection.Claim> claims = new ArrayList<>();
        for (File file : pending)
        {
            if (!file.isFile())
            {
                continue;
            }
            try (JarFile jar = new JarFile(file))
            {
                JarDeclaration declaration = declaredContainedDeps(jar);
                if (declaration == null)
                {
                    continue;
                }
                for (JarDeclaration.ContainedDep dep : declaration.containedDeps())
                {
                    if (dep.kind() == JarDeclaration.ContainedDepKind.LIB)
                    {
                        claims.add(claimOf(dep, file.getName()));
                    }
                }
            }
            catch (IOException e)
            {
                FMLLog.log.debug("Could not read the bundled dependency declarations of {}", file.getName(), e);
            }
        }
        return claims;
    }

    private static BundledLibrarySelection.Claim claimOf(JarDeclaration.ContainedDep dep, String sourceName)
    {
        return new BundledLibrarySelection.Claim(dep.id(), dep.version(), dep.path(), sourceName);
    }

    /**
     * @return whether {@code dep}, declared by {@code source}, lost to another jar's copy of the same library and is
     *         therefore deliberately absent from the classpath. Calls out the difference between "another version won"
     *         and "nothing extracted it", which are the same absence and very different problems.
     */
    public static boolean isBundledLibraryShadowed(File source, JarDeclaration.ContainedDep dep)
    {
        return dep.kind() == JarDeclaration.ContainedDepKind.LIB
                && bundledSelection.isShadowed(claimOf(dep, source.getName()));
    }

    /**
     * @return the classpath URL for a bundled library, whether it is mounted inside its parent jar or extracted to disk,
     *         or {@code null} when neither exists
     */
    @javax.annotation.Nullable
    public static URL bundledLibraryUrl(File source, JarDeclaration.ContainedDep dep)
            throws java.net.MalformedURLException
    {
        URL mounted = mountedLibraries.get(dep.fileName());
        if (mounted != null)
        {
            return mounted;
        }
        File extracted = bundledLibraryNamed(dep.fileName());
        return extracted == null ? null : extracted.getAbsoluteFile().toURI().toURL();
    }

    /** @return the bundled libraries that are served from inside their parent jar, in the order they were found */
    public static List<URL> mountedLibraries()
    {
        return List.copyOf(mountedLibraries.values());
    }

    /**
     * @return where a bundled library called {@code fileName} was extracted, or {@code null} when nothing was extracted
     *         under that name - either because no declaration claimed it, or because another jar's copy of the same
     *         library won the version comparison (see {@link #isBundledLibraryShadowed}).
     *         <p>
     *         Searched by name because the early injection is driven by the declaration, which may name a path inside
     *         the bundling jar while what lands on disk is only the file name.
     */
    @javax.annotation.Nullable
    public static File bundledLibraryNamed(String fileName)
    {
        for (File file : bundledLibraries)
        {
            if (file.getName().equals(fileName))
            {
                return file;
            }
        }
        return null;
    }

    public static List<JarDeclaration.ContainedDep> declaredLibrariesOf(File source)
    {
        List<JarDeclaration.ContainedDep> libraries = new ArrayList<>();
        try (JarFile jar = new JarFile(source))
        {
            JarDeclaration declaration = declaredContainedDeps(jar);
            if (declaration == null)
            {
                return List.of();
            }
            for (JarDeclaration.ContainedDep dep : declaration.containedDeps())
            {
                if (dep.kind() == JarDeclaration.ContainedDepKind.LIB)
                {
                    libraries.add(dep);
                }
            }
        }
        catch (IOException e)
        {
            FMLLog.log.debug("Could not read the bundled dependency declarations of {}", source.getName(), e);
        }
        return List.copyOf(libraries);
    }

    /**
     * @return the jar section of {@code jar}'s {@code mcmod.info}, or {@code null} when it has none
     */
    @javax.annotation.Nullable
    private static JarDeclaration declaredContainedDeps(JarFile jar) throws IOException
    {
        JarEntry modInfo = jar.getJarEntry("mcmod.info");
        if (modInfo == null)
        {
            return null;
        }
        try (InputStream in = jar.getInputStream(modInfo))
        {
            return declarationOf(readAll(in), jar.getName());
        }
    }

    /**
     * Parses the {@code jar} section out of raw {@code mcmod.info} bytes, or returns {@code null} when there is none.
     *
     * <p>Runs for every jar in the mods directory while the classpath is assembled, and the overwhelming majority of
     * those files predate the section. Searching the text for the section name first turns that majority into one
     * pass over a few kilobytes; a false positive only costs the parse that would have happened without the check.
     */
    @javax.annotation.Nullable
    static JarDeclaration declarationOf(byte[] mcmodInfo, String sourceName)
    {
        if (new String(mcmodInfo, java.nio.charset.StandardCharsets.UTF_8).indexOf("\"jar\"") < 0)
        {
            return null;
        }
        return MetadataCollection.from(new ByteArrayInputStream(mcmodInfo), sourceName).getJarDeclaration();
    }

    private static Pair<Artifact, byte[]> extractPacked(File file, ModList modlist, File... modDirs)
    {
        if (processed.contains(file))
        {
            FMLLog.log.debug("File already proccessed {}, Skipping", file.getAbsolutePath());
            return null;
        }
        try (JarFile jar = new JarFile(file)) {
            try {
                FMLLog.log.debug("Examining file: {}", file.getName());
                processed.add(file);
                return extractPacked(jar, modlist, modDirs);
            } catch (IOException ioe) {
                FMLLog.log.error("Unable to read the jar file {} - ignoring", file.getName(), ioe);
            }
        } catch (IOException e) {
            FMLLog.log.error("Unable to read the file {} as jar file - ignoring", file.getName(), e);
        }
        return null;
    }
    private static byte[] bytesOf(JarFile jar, JarEntry entry, byte[] preloaded) throws IOException
    {
        if (preloaded != null)
        {
            return preloaded;
        }
        return readAll(jar.getInputStream(entry));
    }

    /**
     * Extracts one bundled jar next to the mods, where it has always been.
     *
     * <p>The location is deliberate: a bundled jar is usually a prerequisite mod rather than a library, so it has to be
     * discovered like one, and {@code mods/<mcversion>/} is the directory the scan already reads. Parking them elsewhere
     * meant inventing a second pipeline for files that are ordinary mods.
     *
     * <p>Reused rather than rewritten: an existing file of the same name is left alone, so a pack that already has the
     * dependency unpacked keeps what it has.
     */
    private static void extractContained(byte[] bytes, String fileName, String declaredPath, JarDeclaration declaration,
                                         String fromJar, ModList modlist, File... modDirs)
    {
        for (File dir : modDirs)
        {
            File existing = new File(dir, fileName);
            if (existing.exists())
            {
                FMLLog.log.debug("Found existing extracted dependency {}, reusing it", existing);
                return;
            }
        }

        File target = new File(modDirs[0], fileName);
        try
        {
            Files.createParentDirs(target);
            java.nio.file.Files.write(target.toPath(), bytes);
            FMLLog.log.debug("Extracted bundled jar {} from {} to {}", fileName, fromJar, target);
            if (declaresAsLibrary(declaration, declaredPath))
            {
                // A library is not a mod candidate: it is put on the classpath where it is needed and that is all. Adding
                // it as a candidate would make the scan open it, look for @Mod classes and classify it as a non-mod
                // afterwards - the work this marking exists to avoid.
                bundledLibraries.add(target);
                return;
            }
            extractPacked(target, modlist, modDirs);
        }
        catch (IOException e)
        {
            FMLLog.log.error("An error occurred extracting bundled jar " + fileName + " from " + fromJar, e);
        }
    }

    /**
     * @return whether the declaration marks {@code path} as a library. A path may have been rewritten to
     *         {@code META-INF/libraries/...} while looking it up, so the file name is compared as well.
     */
    private static boolean declaresAsLibrary(JarDeclaration declaration, String path)
    {
        if (declaration == null)
        {
            return false;
        }
        String name = new File(path).getName();
        for (JarDeclaration.ContainedDep dep : declaration.containedDeps())
        {
            if (dep.kind() != JarDeclaration.ContainedDepKind.LIB)
            {
                continue;
            }
            if (dep.path().equals(path) || new File(dep.path()).getName().equals(name))
            {
                return true;
            }
        }
        return false;
    }

    private static Pair<Artifact, byte[]> extractPacked(JarFile jar, ModList modlist, File... modDirs) throws IOException
    {
        // A jar needs no manifest to bundle anything: the declaration in its mcmod.info is a complete statement on its
        // own, and it is the only one available to a jar that carries no manifest at all.
        Attributes attrs = jar.getManifest() == null ? null : jar.getManifest().getMainAttributes();
        JarDeclaration declaration = declaredContainedDeps(jar);

        if (JarDeclaration.sideOf(attrs, declaration).excludes(FMLLaunchHandler.side().name()))
            return null;

        List<String> bundled = containedDepPaths(attrs, declaration, jar.getName());
        if (!bundled.isEmpty() && deferringBundledDeps)
        {
            // Extraction is an assembly step. Record the jar and let the first candidate request do it, so a directory is
            // not rewritten while it is being cleaned and the work happens once, at the moment it matters.
            pendingBundledDeps.add(new File(jar.getName()).getAbsoluteFile());
        }
        else if (!bundled.isEmpty())
        {
            for (String dep : bundled)
            {
                if (!dep.endsWith(".jar"))
                {
                    FMLLog.log.error("Contained Dep is not a jar file: {}", dep);
                    throw new IllegalStateException("Invalid contained dep, Must be jar: " + dep);
                }

                if (jar.getJarEntry(dep) == null && jar.getJarEntry("META-INF/libraries/" + dep) != null)
                    dep = "META-INF/libraries/" + dep;

                JarEntry depEntry = jar.getJarEntry(dep);
                if (depEntry == null)
                {
                    FMLLog.log.error("Contained Dep is not in the jar: {}", dep);
                    throw new IllegalStateException("Invalid contained dep, Missing from jar: " + dep);
                }

                // Another jar bundles a higher version of this library; extracting this copy would put two versions on
                // one class loader, where which one is visible depends on lookup order.
                JarDeclaration.ContainedDep declared = declaration == null ? null : declaration.depFor(dep);
                if (declared != null && isBundledLibraryShadowed(new File(jar.getName()), declared))
                {
                    FMLLog.log.debug("Not extracting {} from {}: a higher version of \"{}\" is bundled elsewhere ({})",
                            dep, new File(jar.getName()).getName(), declared.id(), bundledSelection.winnerVersion(declared.id()));
                    continue;
                }

                // A declared library never has to touch the disk: the class loader can read it out of this jar. This is
                // the whole point of NestedLibraryJars, and the reason nothing else in this loop needs to change.
                if (declared != null && declared.kind() == JarDeclaration.ContainedDepKind.LIB && NestedLibraryJars.enabled())
                {
                    URL mounted = NestedLibraryJars.urlFor(new File(jar.getName()), dep);
                    if (mounted != null)
                    {
                        mountedLibraries.put(declared.fileName(), mounted);
                        FMLLog.log.debug("Mounted the bundled library {} from {} without extracting it", dep, new File(jar.getName()).getName());
                        continue;
                    }
                    FMLLog.log.warn("{} declares the bundled library {}, but the entry is missing; extracting instead",
                            new File(jar.getName()).getName(), dep);
                }

                String depEndName = new File(dep).getName(); // extract last part of name
                if (skipContainedDeps.contains(dep) || skipContainedDeps.contains(depEndName))
                {
                    FMLLog.log.error("Skipping dep at request: {}", dep);
                    continue;
                }

                Attributes meta = null;
                byte[] data = null;
                byte[] manifest_data = null;

                JarEntry metaEntry = jar.getJarEntry(dep + ".meta");
                if (metaEntry != null)
                {
                    manifest_data = readAll(jar.getInputStream(metaEntry));
                    meta = new Manifest(new ByteArrayInputStream(manifest_data)).getMainAttributes();
                }
                else
                {
                    data = readAll(jar.getInputStream(depEntry));
                    try (ZipInputStream zi = new ZipInputStream(new ByteArrayInputStream(data))) //We use zip input stream directly, as the current Oracle implementation of JarInputStream only works when the manifest is the First/Second entry in the jar...
                    {
                        ZipEntry ze = null;
                        while ((ze = zi.getNextEntry()) != null)
                        {
                            if (ze.getName().equalsIgnoreCase(JarFile.MANIFEST_NAME))
                            {
                                manifest_data = readAll(zi);
                                meta = new Manifest(new ByteArrayInputStream(manifest_data)).getMainAttributes();
                                break;
                            }
                        }
                    }
                }

                if (meta == null || !meta.containsKey(MAVEN_ARTIFACT))
                {
                    // No coordinates. It goes next to the other mods, so it is discovered the same way - a bundled jar is
                    // usually a prerequisite mod, not a library.
                    extractContained(bytesOf(jar, depEntry, data), depEndName, dep, declaration, jar.getName(), modlist, modDirs);
                }
                else
                {
                    try
                    {
                        Artifact artifact = readArtifact(modlist.getRepository(), meta);
                        File target = artifact.getFile();
                        if (target.exists())
                        {
                            FMLLog.log.debug("Found existing ContainedDep {}({}) from {} extracted to {}, skipping extraction", dep, artifact.toString(), target.getCanonicalPath(), jar.getName());
                            if (!ENABLE_AUTO_MOD_MOVEMENT)
                            {
                                Pair<?, ?> child = extractPacked(target, modlist, modDirs); //If we're not building a real list we have to re-build the dep list every run. So search down.
                                if (child == null && metaEntry != null) //External meta with no internal name... If there is a internal name, we trust that that name is the correct one.
                                {
                                    modlist.add(artifact);
                                }
                            }
                        }
                        else
                        {
                            FMLLog.log.debug("Extracting ContainedDep {}({}) from {} to {}", dep, artifact.toString(), jar.getName(), target.getCanonicalPath());
                            Files.createParentDirs(target);
                            try
                            (
                                FileOutputStream out = new FileOutputStream(target);
                                InputStream in = data == null ? jar.getInputStream(depEntry) : new ByteArrayInputStream(data)
                            )
                            {
                                ByteStreams.copy(in, out);
                            }
                            FMLLog.log.debug("Extracted ContainedDep {}({}) from {} to {}", dep, artifact.toString(), jar.getName(), target.getCanonicalPath());

                            if (artifact.isSnapshot())
                            {
                                SnapshotJson json = SnapshotJson.create(artifact.getSnapshotMeta());
                                json.add(new SnapshotJson.Entry(artifact.getTimestamp(), meta.getValue(MD5)));
                                json.write(artifact.getSnapshotMeta());
                            }

                            if (!DISABLE_EXTERNAL_MANIFEST)
                            {
                                File meta_target = new File(target.getAbsolutePath() + ".meta");
                                Files.write(manifest_data, meta_target);
                            }
                            Pair<?, ?> child = extractPacked(target, modlist, modDirs);
                            if (child == null && metaEntry != null) //External meta with no internal name... If there is a internal name, we trust that that name is the correct one.
                            {
                                modlist.add(artifact);
                            }
                        }
                    }
                    catch (NumberFormatException nfe)
                    {
                        FMLLog.log.error(FMLLog.log.getMessageFactory().newMessage("An error occurred extracting dependency. Invalid Timestamp: {}", meta.getValue(TIMESTAMP)), nfe);
                    }
                    catch (IOException e)
                    {
                        FMLLog.log.error("An error occurred extracting dependency", e);
                    }
                }
            }
        }

        if (attrs != null && attrs.containsKey(MAVEN_ARTIFACT))
        {
            Artifact artifact = readArtifact(modlist.getRepository(), attrs);
            modlist.add(artifact);
            JarEntry manifestEntry = manifestEntry(jar);
            return manifestEntry == null ? null : Pair.of(artifact, readAll(jar.getInputStream(manifestEntry)));
        }
        return null;
    }

    /**
     * The entries this jar says it bundles, in the order they should be extracted.
     *
     * <p>The declaration wins when it exists. It is the form that can also say <em>what</em> each entry is, and the
     * only one available to a jar with no manifest. The legacy manifest key stays as the fallback for every jar
     * written before the section existed - and for the plain library jars that will never have one.
     *
     * <p>Disagreeing with each other is reported rather than resolved silently: a jar that migrated to the declaration
     * but left a stale key behind would otherwise drop entries with no trace.
     */
    static List<String> containedDepPaths(Attributes attrs, JarDeclaration declaration, String jarName)
    {
        List<String> declared = new ArrayList<>();
        if (declaration != null)
        {
            for (JarDeclaration.ContainedDep dep : declaration.containedDeps())
            {
                declared.add(dep.path());
            }
        }

        List<String> legacy = new ArrayList<>();
        String value = attrs == null ? null : attrs.getValue(MODCONTAINSDEPS);
        if (value != null)
        {
            for (String path : value.split(" "))
            {
                if (!path.isBlank())
                {
                    legacy.add(path);
                }
            }
        }

        if (declared.isEmpty())
        {
            return legacy;
        }
        if (!legacy.isEmpty() && !Set.copyOf(declared).equals(Set.copyOf(legacy)))
        {
            FMLLog.log.warn("{} declares its bundled entries twice and the two lists differ: mcmod.info says {}, the"
                    + " ContainedDeps manifest attribute says {}. The mcmod.info declaration wins.", jarName, declared, legacy);
        }
        return declared;
    }

    /** The jar's manifest entry, matched the way {@link JarFile#getManifest()} matches it. */
    private static JarEntry manifestEntry(JarFile jar)
    {
        JarEntry entry = jar.getJarEntry(JarFile.MANIFEST_NAME);
        if (entry != null)
        {
            return entry;
        }
        return jar.stream().filter(e -> JarFile.MANIFEST_NAME.equalsIgnoreCase(e.getName())).findFirst().orElse(null);
    }

    private static Artifact readArtifact(Repository repo, Attributes meta)
    {
        String timestamp = meta.getValue(TIMESTAMP);
        if (timestamp != null)
            timestamp = SnapshotJson.TIMESTAMP.format(new Date(Long.parseLong(timestamp)));

        return new Artifact(repo, meta.getValue(MAVEN_ARTIFACT), timestamp);
    }

    private static byte[] readAll(InputStream in) throws IOException
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int read = -1;
        byte[] data = new byte[1024 * 16];

        while ((read = in.read(data, 0, data.length)) != -1)
            out.write(data, 0, read);

        out.flush();
        return out.toByteArray();
    }

    public static List<Artifact> flattenLists(File mcDir)
    {
        List<Artifact> merged = new ArrayList<>();
        for (ModList list : ModList.getBasicLists(mcDir))
        {
            for (Artifact art : list.flatten())
            {
                Optional<Artifact> old = merged.stream().filter(art::matchesID).findFirst();
                if (!old.isPresent())
                {
                    merged.add(art);
                }
                else if (old.get().getVersion().compareTo(art.getVersion()) < 0)
                {
                    merged.add(merged.indexOf(old.get()), art);
                    merged.remove(old.get());
                }
            }
        }
        return merged;
    }

    public static List<File> gatherLegacyCanidates(File mcDir)
    {
        List<File> list = new ArrayList<>();
        Set<File> seen = new HashSet<>();

        String extrapath = FMLLaunchHandler.devExtraPath();
        if (extrapath != null) 
        {
            for (String mod : extrapath.split(File.pathSeparator))
            {
                File file = new File(mod);
                if (file.exists())
                {
                    list.add(file);
                    seen.add(file);
                    FMLLog.log.info("Adding extra mod file {}", file);
                }
                else
                {
                    FMLLog.log.debug("Mod file {} does not exist", file);
                }
            }
        }

        @SuppressWarnings("unchecked")
        Map<String,String> args = (Map<String, String>)Launch.blackboard.get("forgeLaunchArgs");
        String extraMods = args.get("--mods");
        if (extraMods != null)
        {
            FMLLog.log.info("Found mods from the command line:");
            for (String mod : extraMods.split(","))
            {
                File file = new File(mcDir, mod);
                if (!file.exists())
                {
                    FMLLog.log.info("  Failed to find mod file {} ({})", mod, file.getAbsolutePath());
                }
                else if (seen.add(file))
                {
                    FMLLog.log.debug("  Adding {} ({}) to the mod list", mod, file.getAbsolutePath());
                    list.add(file);
                }
                else
                {
                    FMLLog.log.debug("  Duplicate command line mod detected {} ({})", mod, file.getAbsolutePath());
                }
            }
        }

        for (String dir : new String[]{"mods", "mods" + File.separatorChar + ForgeVersion.mcVersion})
        {
            File base = new File(mcDir, dir);
            if (!base.isDirectory() || !base.exists())
                continue;

            FMLLog.log.info("Searching {} for mods", base.getAbsolutePath());
            collectModFiles(base, seen, list);
        }

        ModList memory = ModList.cache.get("MEMORY");
        if (!ENABLE_AUTO_MOD_MOVEMENT && memory != null && memory.getRepository() != null)
            memory.getRepository().filterLegacy(list);

        list.sort(ModFileOrder.INSTANCE);
        return list;
    }

    /**
     * Collects the jars directly inside {@code base} and, when {@code ONE_LEVEL} is configured, the ones one directory
     * deeper so packs can group files by mod.
     *
     * <p>Depth is deliberately fixed at one level rather than arbitrary: the point is grouping, and a recursive walk
     * would let a stray directory tree turn into an unbounded scan with no way to explain which jar was found where.
     */
    /**
     * Directories inside {@code mods/} that never hold mods the user put there.
     *
     * <p>Several launch forms keep their own working directories in the same place - the prism family generates
     * {@code .index}, Forge's own memory repository lives in {@code memory_repo}, and anything may leave a {@code .cache}
     * behind. Scanning them would pick up jars nobody meant to load and produce a warning for every one of them.
     *
     * <p>The Minecraft version directory is in the list because it is scanned on its own; reading it twice would be
     * harmless but pointless.
     */
    private static final Set<String> NOT_MOD_DIRECTORIES = Set.of(
            "memory_repo", ".cache", ".index", ".git", "meta-inf",
            ForgeVersion.mcVersion.toLowerCase(Locale.ENGLISH));

    /**
     * @return whether a subdirectory of the mods folder may be searched. Hidden directories are excluded outright: a name
     *         starting with a dot is a tool's private state by convention, whatever it is called.
     */
    static boolean isScannableDirectory(File directory)
    {
        String name = directory.getName();
        return !name.startsWith(".") && !NOT_MOD_DIRECTORIES.contains(name.toLowerCase(Locale.ENGLISH));
    }

    private static void collectModFiles(File base, Set<File> seen, List<File> target)
    {
        File[] direct = base.listFiles(MOD_FILENAME_FILTER);
        if (direct != null)
        {
            for (File f : direct)
            {
                if (seen.add(f))
                {
                    FMLLog.log.debug("  Adding {} to the mod list", f.getName());
                    target.add(f);
                }
            }
        }
        if (!oneLevelScan())
        {
            return;
        }
        File[] subdirectories = base.listFiles(File::isDirectory);
        if (subdirectories == null)
        {
            return;
        }
        for (File directory : subdirectories)
        {
            if (!isScannableDirectory(directory))
            {
                continue;
            }
            File[] nested = directory.listFiles(MOD_FILENAME_FILTER);
            if (nested == null)
            {
                continue;
            }
            for (File f : nested)
            {
                if (seen.add(f))
                {
                    FMLLog.log.debug("  Adding {} from {} to the mod list", f.getName(), directory.getName());
                    target.add(f);
                }
            }
        }
    }

    public static List<File> getCandidates() {
        extractPendingBundledDeps();
        if (candidates != null)
        {
            return candidates;
        }
        List<File> gathered = gatherLegacyCanidates(minecraftHome);
        Set<File> candidateSet = new HashSet<>(gathered);
        for (Artifact artifact : flattenLists(minecraftHome))
        {
            artifact = Repository.resolveAll(artifact);
            if (artifact != null)
            {
                File target = artifact.getFile();
                if (candidateSet.add(target))
                    gathered.add(target);
            }
        }
        // Nothing may rewrite the candidate list any more. It used to be mutable in place for Bansoukou, which
        // meant the set of files the loader considered was decided by whoever got to the list first, while the
        // ordering that coremod loading depends on came from somewhere else entirely.
        candidates = List.copyOf(gathered);
        return candidates;
    }

    public static Repository getDefaultRepo()
    {
        return libraries_dir;
    }

}
