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

package net.minecraftforge.fml.common.patcher;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.launchwrapper.LaunchClassLoader;
import net.minecraftforge.fml.common.FMLLog;
import net.minecraftforge.fml.relauncher.FMLLaunchHandler;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.repackage.com.nothome.delta.GDiffPatcher;
import LZMA.LzmaInputStream;

import com.google.common.base.Joiner;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Maps;
import com.google.common.hash.Hashing;
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;

public class ClassPatchManager {
    //Must be ABOVE INSTANCE so they get set in time for the constructor.
    public static final boolean dumpPatched = Boolean.parseBoolean(System.getProperty("fml.dumpPatchedClasses", "false"));
    public static final boolean DEBUG = Boolean.parseBoolean(System.getProperty("fml.debugClassPatchManager", "false"));

    public static final ClassPatchManager INSTANCE = new ClassPatchManager();

    private final GDiffPatcher patcher = new GDiffPatcher();
    private ListMultimap<String, ClassPatch> patches;

    private final Map<String,byte[]> patchedClasses = Maps.newHashMap();
    private File tempDir;
    private ClassPatchManager()
    {
        if (dumpPatched)
        {
            tempDir = Files.createTempDir();
            FMLLog.log.info("Dumping patched classes to {}",tempDir.getAbsolutePath());
        }
    }

    public byte[] getPatchedResource(String name, String mappedName, LaunchClassLoader loader) throws IOException
    {
        byte[] rawClassBytes = loader.getClassBytes(name);
        return applyPatch(name, mappedName, rawClassBytes);
    }
    public byte[] applyPatch(String name, String mappedName, byte[] inputData)
    {
        if (patches == null)
        {
            return inputData;
        }
        if (patchedClasses.containsKey(name))
        {
            return patchedClasses.get(name);
        }
        List<ClassPatch> list = patches.get(name);
        if (list.isEmpty())
        {
            return inputData;
        }
        boolean ignoredError = false;
        if (DEBUG)
            FMLLog.log.debug("Runtime patching class {} (input size {}), found {} patch{}", mappedName, (inputData == null ? 0 : inputData.length), list.size(), list.size()!=1 ? "es" : "");
        for (ClassPatch patch: list)
        {
            if (!patch.targetClassName.equals(mappedName) && !patch.sourceClassName.equals(name))
            {
                FMLLog.log.warn("Binary patch found {} for wrong class {}", patch.targetClassName, mappedName);
            }
            if (!patch.existsAtTarget && (inputData == null || inputData.length == 0))
            {
                inputData = new byte[0];
            }
            else if (!patch.existsAtTarget)
            {
                FMLLog.log.warn("Patcher expecting empty class data file for {}, but received non-empty", patch.targetClassName);
            }
            else if (patch.existsAtTarget && (inputData == null || inputData.length == 0))
            {
                FMLLog.log.fatal("Patcher expecting non-empty class data file for {}, but received empty.", patch.targetClassName);
                throw new RuntimeException(String.format("Patcher expecting non-empty class data file for %s, but received empty, your vanilla jar may be corrupt.", patch.targetClassName));
            }
            else
            {
                int inputChecksum = Hashing.adler32().hashBytes(inputData).asInt();
                if (patch.inputChecksum != inputChecksum)
                {
                    FMLLog.log.fatal("There is a binary discrepancy between the expected input class {} ({}) and the actual class. Checksum on disk is {}, in patch {}. Things are probably about to go very wrong. Did you put something into the jar file?", mappedName, name, Integer.toHexString(inputChecksum), Integer.toHexString(patch.inputChecksum));
                    if (!Boolean.parseBoolean(System.getProperty("fml.ignorePatchDiscrepancies","false")))
                    {
                        FMLLog.log.fatal("The game is going to exit, because this is a critical error, and it is very improbable that the modded game will work, please obtain clean jar files.");
                        System.exit(1);
                    }
                    else
                    {
                        FMLLog.log.fatal("FML is going to ignore this error, note that the patch will not be applied, and there is likely to be a malfunctioning behaviour, including not running at all");
                        ignoredError = true;
                        continue;
                    }
                }
            }
            synchronized (patcher)
            {
                try
                {
                    inputData = patcher.patch(inputData, patch.patch);
                }
                catch (IOException e)
                {
                    FMLLog.log.error("Encountered problem runtime patching class {}", name, e);
                    continue;
                }
            }
        }
        if (!ignoredError && DEBUG)
        {
            FMLLog.log.debug("Successfully applied runtime patches for {} (new size {})", mappedName, inputData.length);
        }
        if (dumpPatched)
        {
            try
            {
                Files.write(inputData, new File(tempDir,mappedName));
            }
            catch (IOException e)
            {
                FMLLog.log.error(FMLLog.log.getMessageFactory().newMessage("Failed to write {} to {}", mappedName, tempDir.getAbsolutePath()), e);
            }
        }
        patchedClasses.put(name,inputData);
        return inputData;
    }

    public void setup(Side side)
    {
        if (alreadyPatched())
        {
            return;
        }
        final byte[] decompressed;
        try (InputStream binpatchesCompressed = getClass().getResourceAsStream("/binpatches.pack.lzma"))
        {
            if (binpatchesCompressed == null)
            {
                if (!FMLLaunchHandler.isDeobfuscatedEnvironment())
                {
                    FMLLog.log.fatal("The binary patch set is missing, things are not going to work!");
                } else {
                    FMLLog.log.error("The binary patch set is missing, because you are in a development environment!");
                }
                return;
            }
            try (LzmaInputStream binpatchesDecompressedLzma = new LzmaInputStream(binpatchesCompressed))
            {
                decompressed = ByteStreams.toByteArray(binpatchesDecompressedLzma);
            }
        }
        catch (Exception e)
        {
            throw new RuntimeException("Error occurred reading binary patches. Expect severe problems!", e);
        }

        patches = ArrayListMultimap.create();
        final List<ClassPatch> read;
        try
        {
            read = ClassPatchReader.read(decompressed, side.toString().toLowerCase(Locale.ENGLISH),
                    e -> FMLLog.log.warn(FMLLog.log.getMessageFactory().newMessage("Unable to read a binary patch entry - ignoring"), e));
        }
        catch (IOException e)
        {
            throw new RuntimeException("Error occurred reading binary patches. Expect severe problems!", e);
        }
        for (ClassPatch cp : read)
        {
            if (cp.patch.length > 0)
            {
                patches.put(cp.sourceClassName, cp);
            }
        }
        FMLLog.log.debug("Read {} binary patches", patches.size());
        if (DEBUG)
            FMLLog.log.debug("Patch list :\n\t{}", Joiner.on("\t\n").join(patches.asMap().entrySet()));
        patchedClasses.clear();
    }

    /**
     * The overlay jar the launcher bootstrap ({@code top.outlands.foundation}) installed, or {@code null} when this
     * is an ordinary run.
     *
     * <p>The system property is a hint and is verified here: a path that does not exist is treated as absent, so a
     * launcher that sets nothing, or sets something stale, still gets the patch pack inside this jar.
     */
    public static File prepatchedJar()
    {
        final String patched = System.getProperty("outlands.patchedJar");
        if (patched == null || patched.isEmpty())
        {
            return null;
        }
        final File jar = new File(patched);
        if (!jar.isFile() || jar.length() <= 0L)
        {
            return null;
        }
        return jar;
    }

    /**
     * True when the patches are already applied on disk. The classes reaching this class loader then carry the
     * patch, so patching them again would only trip the input checksum.
     */
    private boolean alreadyPatched()
    {
        final String requested = System.getProperty("outlands.patchedJar");
        if (requested == null || requested.isEmpty())
        {
            return false;
        }
        final File jar = prepatchedJar();
        if (jar == null)
        {
            FMLLog.log.warn("outlands.patchedJar points at {} which is not a usable file, falling back to the binary patch pack", requested);
            return false;
        }
        FMLLog.log.info("Minecraft was pre-patched ({}), the binary patch pack is not used", jar.getAbsolutePath());
        if (DEBUG)
            FMLLog.log.debug("Patches already applied on disk, the patch list stays empty");
        patches = null;
        patchedClasses.clear();
        return true;
    }
}
