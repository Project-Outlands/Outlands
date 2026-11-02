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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.util.Collections;
import java.util.List;
import java.util.Map.Entry;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import net.minecraft.launchwrapper.Launch;
import net.minecraftforge.fml.common.FMLLog;
import net.minecraftforge.fml.common.LoaderException;
import net.minecraftforge.fml.common.MetadataCollection;
import net.minecraftforge.fml.common.ModContainer;
import net.minecraftforge.fml.common.ModContainerFactory;
import net.minecraftforge.fml.common.discovery.ASMDataTable.ASMData;
import net.minecraftforge.fml.common.discovery.asm.ASMModParser;
import net.minecraftforge.fml.common.discovery.cache.ClassScanRecord;
import net.minecraftforge.fml.common.DeclarativeModContainer;
import net.minecraftforge.fml.common.ModMetadata;
import net.minecraftforge.fml.common.discovery.cache.JarFingerprint;
import net.minecraftforge.fml.common.discovery.cache.JarScanRecord;
import net.minecraftforge.fml.common.discovery.cache.RecordFile;
import net.minecraftforge.fml.common.discovery.json.JsonAnnotationLoader;

import java.util.Map;
import java.util.zip.ZipEntry;

import org.objectweb.asm.Type;

import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;

public class JarDiscoverer implements ITypeDiscoverer
{
    private static final boolean ENABLE_JSON_TEST = "true".equals(System.getProperty("fml.enableJsonAnnotations", "false"));

    @Override
    public List<ModContainer> discover(ModCandidate candidate, ASMDataTable table)
    {
        List<ModContainer> foundMods = Lists.newArrayList();
        FMLLog.log.debug("Examining file {} for potential mods", candidate.getModContainer().getName());
        try (JarFile jar = new JarFile(candidate.getModContainer()))
        {
            // Parsed by the discovery phase already; the fallback goes through the same shared read, for the jars it
            // did not look at at all.
            MetadataCollection mc = candidate.getMetadata();
            if (mc == null)
            {
                mc = JarMetadata.of(jar).metadata();
                if (mc == null)
                {
                    FMLLog.log.debug("The mod container {} appears to be missing an mcmod.info file", candidate.getModContainer().getName());
                    mc = MetadataCollection.from(null, "");
                }
            }

            if (ENABLE_JSON_TEST && jar.getEntry(JsonAnnotationLoader.ANNOTATION_JSON) != null)
                findClassesJSON(candidate, table, jar, foundMods, mc);
            else
                findClassesASM(candidate, table, jar, foundMods, mc);

            addDeclarativeContainers(candidate, table, foundMods, mc);
        }
        catch (Exception e)
        {
            FMLLog.log.warn("Zip file {} failed to read properly, it will be ignored", candidate.getModContainer().getName(), e);
        }
        return foundMods;
    }

    private void findClassesASM(ModCandidate candidate, ASMDataTable table, JarFile jar, List<ModContainer> foundMods, MetadataCollection mc) throws IOException
    {
        File cacheFile = RecordFile.fileFor(Launch.minecraftHome, candidate.getModContainer());
        JarScanRecord scan = candidate.getScanRecord();
        if (scan != null)
        {
            replayScan(scan, candidate, table, jar, foundMods, mc);
            if (!scan.fromDisk())
            {
                RecordFile.write(cacheFile, scan);
            }
            candidate.setScanRecord(null);
            return;
        }

        List<JarEntry> entries = Collections.list(jar.entries());
        List<JarEntry> scanned = Lists.newArrayListWithCapacity(entries.size());
        for (JarEntry entry : entries)
        {
            if (ITypeDiscoverer.shouldScan(entry.getName()))
            {
                scanned.add(entry);
            }
        }
        // Reading and parsing the class bytes runs on several threads. The loop below must stay in central-directory
        // order: that order is what a cached replay reproduces.
        List<ParallelJarParse.Parsed> parsed = ParallelJarParse.parse(scanned, jar);
        boolean cacheable = cacheFile != null;
        List<Integer> indices = Lists.newArrayList();
        List<ClassScanRecord> records = Lists.newArrayList();

        for (int ordinal = 0; ordinal < scanned.size(); ordinal++)
        {
            JarEntry entry = scanned.get(ordinal);
            String entryName = entry.getName();
            ParallelJarParse.Parsed result = parsed.get(ordinal);
            if (result.failure() != null)
            {
                Exception failure = result.failure();
                if (failure instanceof LoaderException)
                {
                    FMLLog.log.error("There was a problem reading the entry {} in the jar {} - probably a corrupt zip", entryName, candidate.getModContainer().getPath(), failure);
                    jar.close();
                    throw (LoaderException) failure;
                }
                if (failure instanceof IOException)
                {
                    throw (IOException) failure;
                }
                throw new LoaderException(failure);
            }
            ASMModParser modParser = result.parser();
            candidate.addClassEntry(entryName);
            modParser.validate();
            modParser.sendToTable(table, candidate);
            if (cacheable)
            {
                ClassScanRecord record = modParser.toRecord();
                if (JarScanRecord.hasContent(record))
                {
                    indices.add(ordinal);
                    records.add(record);
                }
            }
            ModContainer container = ModContainerFactory.instance().build(modParser, candidate.getModContainer(), candidate);
            addContainer(container, table, foundMods, mc, modParser.getClassVersion());
        }

        if (cacheable)
        {
            // Fresh scan for this jar; the fingerprint covers the entries it was taken from.
            scan = JarScanRecord.of(JarFingerprint.compute(entries), indices, records);
            RecordFile.write(cacheFile, scan);
        }
    }

    /**
     * Sends a scan back to the table without parsing: every scanned entry contributes its class entry (taken from
     * the central directory, which the fingerprint covers), and the stored records contribute annotations,
     * interfaces and the class version.
     */
    private void replayScan(JarScanRecord scan, ModCandidate candidate, ASMDataTable table, JarFile jar, List<ModContainer> foundMods, MetadataCollection mc)
    {
        int[] indices = scan.indices();
        ClassScanRecord[] records = scan.records();
        int ordinal = -1;
        int next = 0;
        for (JarEntry entry : Collections.list(jar.entries()))
        {
            String entryName = entry.getName();
            if (!ITypeDiscoverer.shouldScan(entryName))
            {
                continue;
            }
            ordinal++;
            candidate.addClassEntry(entryName);
            if (next < indices.length && indices[next] == ordinal)
            {
                ClassScanRecord record = records[next++];
                record.sendToTable(candidate, table);
                ModContainer container = ModContainerFactory.instance().build(record, candidate.getModContainer(), candidate);
                addContainer(container, table, foundMods, mc, record.classVersion());
            }
        }
    }

    /**
     * Registers a container the factory built: it belongs in the table, in the discovered list, and needs its
     * metadata and class version set. The factory returns {@code null} for classes that opt out or fail to
     * construct, which is a no-op here. Both discovery paths must do exactly this much.
     */
    private static void addContainer(ModContainer container, ASMDataTable table, List<ModContainer> foundMods, MetadataCollection mc, int classVersion)
    {
        if (container == null)
        {
            return;
        }
        table.addContainer(container);
        foundMods.add(container);
        container.bindMetadata(mc);
        container.setClassVersion(classVersion);
    }

    /**
     * Adds a container for every mod the jar declares an entry point for.
     *
     * <p>Runs after the class scan so a jar that also has {@code @Mod} classes keeps them; a declared entry point whose
     * mod id is already taken is reported instead of silently disappearing, because two mods cannot share an id and the
     * order they were found in is not a reason to pick one.
     */
    private void addDeclarativeContainers(ModCandidate candidate, ASMDataTable table, List<ModContainer> foundMods, MetadataCollection mc)
    {
        JarDeclaration declaration = mc.getJarDeclaration();
        if (declaration == null || declaration.entrypoints().isEmpty())
        {
            return;
        }
        for (Map.Entry<String, String> entry : declaration.entrypoints().entrySet())
        {
            String modId = entry.getKey();
            ModContainer existing = findById(foundMods, modId);
            if (existing != null)
            {
                throw new LoaderException("The mcmod.info of " + candidate.getModContainer().getName() + " declares an entrypoint"
                        + " for mod id '" + modId + "', but that id is already used by " + existing.getClass().getName()
                        + " in the same jar. One id can only name one mod.");
            }
            ModMetadata metadata = mc.getMetadataForId(modId, Map.of());
            // An autogenerated entry has no version, and the version is compared during sorting - a null would reach
            // DefaultArtifactVersion and fail much later with nothing pointing back here. Same substitution the @Mod path
            // makes, including the warning, so a jar that forgot the metadata gets told once.
            if (metadata.version == null || metadata.version.isBlank())
            {
                FMLLog.log.warn("Mod {} is missing the required element 'version'. Substituting 1.0", modId);
                metadata.version = "1.0";
            }
            if (metadata.name == null || metadata.name.isBlank())
            {
                metadata.name = modId;
            }
            DeclarativeModContainer container = new DeclarativeModContainer(metadata, candidate.getModContainer(), entry.getValue());
            // The same hook the @Mod path uses, so a container needs to say "I am bound" only once, in one way, whichever
            // kind of mod it is.
            container.bindMetadata(mc);
            table.addContainer(container);
            foundMods.add(container);
            FMLLog.log.debug("Declared mod {} without a mod class, entry point {}", modId, entry.getValue());
        }
    }

    private static ModContainer findById(List<ModContainer> containers, String modId)
    {
        for (ModContainer container : containers)
        {
            if (modId.equals(container.getModId()))
            {
                return container;
            }
        }
        return null;
    }

    private void findClassesJSON(ModCandidate candidate, ASMDataTable table, JarFile jar, List<ModContainer> foundMods, MetadataCollection mc) throws IOException
    {
        FMLLog.log.info("Loading jar {} annotation data from json", candidate.getModContainer().getPath());
        ZipEntry json = jar.getEntry(JsonAnnotationLoader.ANNOTATION_JSON);
        Multimap<String, ASMData> annos = JsonAnnotationLoader.loadJson(jar.getInputStream(json), candidate, table);

        // Kept separate on purpose: only the dead fml.enableJsonAnnotations path uses this predicate.
        for (ZipEntry e : Collections.list(jar.entries()))
        {
            if (!e.getName().startsWith("__MACOSX") && !e.getName().startsWith("META-INF/") && e.getName().endsWith(".class"))
            {
                candidate.addClassEntry(e.getName());
            }
        }

        for (Entry<Type, Constructor<? extends ModContainer>> entry : ModContainerFactory.modTypes.entrySet())
        {
            Type type = entry.getKey();
            Constructor<? extends ModContainer> ctr = entry.getValue();

            for (ASMData data : annos.get(type.getClassName()))
            {
                FMLLog.log.debug("Identified a mod of type {} ({}) - loading", type.getClassName(), data.getClassName());
                try
                {
                    ModContainer ret = ctr.newInstance(data.getClassName(), candidate, data.getAnnotationInfo());
                    if (!ret.shouldLoadInEnvironment())
                        FMLLog.log.debug("Skipping mod {}, container opted to not load.", data.getClassName());
                    else
                    {
                        table.addContainer(ret);
                        foundMods.add(ret);
                        ret.bindMetadata(mc);
                        //ret.setClassVersion(classVersion); // Not really needed anymore as we're forcing J8. Maybe think of reinstating for J9 support? After LaunchWraper re-do.
                    }
                }
                catch (Exception e)
                {
                    FMLLog.log.error("Unable to construct {} container", data.getClassName(), e);
                }
            }
        }
    }
}
