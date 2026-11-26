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

package net.minecraftforge.fml.common.asm.transformers;

import net.minecraft.launchwrapper.IClassNameTransformer;
import net.minecraft.launchwrapper.IClassTransformer;
import net.minecraft.launchwrapper.Launch;
import net.minecraftforge.fml.common.asm.transformers.deobf.FMLDeobfuscatingRemapper;
import net.minecraftforge.fml.common.asm.transformers.deobf.FMLRemappingAdapter;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;

public class DeobfuscationTransformer implements IClassTransformer, IClassNameTransformer {

    private static final boolean RECALC_FRAMES = Boolean.parseBoolean(System.getProperty("FORGE_FORCE_FRAME_RECALC", "false"));
    private static final int WRITER_FLAGS = ClassWriter.COMPUTE_MAXS | (RECALC_FRAMES ? ClassWriter.COMPUTE_FRAMES : 0);
    private static final int READER_FLAGS = RECALC_FRAMES ? ClassReader.SKIP_FRAMES : ClassReader.EXPAND_FRAMES;
    /** A vanilla class's package: dotted as a launcher spells a name, slashed as a class file stores one. */
    private static final String VANILLA_DOTTED = "net.minecraft.";
    private static final String VANILLA_SLASHED = "net/minecraft/";
    // COMPUTE_FRAMES causes classes to be loaded, which could cause issues if the classes do not exist.
    // However in testing this has not happened. {As we run post SideTransformer}
    // If reported we need to add a custom implementation of ClassWriter.getCommonSuperClass
    // that does not cause class loading.

    private final boolean deobfuscatedEnvironment = (Boolean) Launch.blackboard.get("fml.deobfuscatedEnvironment");

    @Override
    public byte[] transform(String name, String transformedName, byte[] bytes)
    {
        if (bytes == null)
        {
            return null;
        }

        // The pair of names is whatever the caller had at hand, and callers disagree: the loader passes
        // (obfuscated, dotted deobf), while a coremod reaching this transformer through IClassTransformer may pass
        // them the other way round and in either spelling - EnderCore's NoClassloadClassWriter passes (slashed
        // deobf, obfuscated). Believing one of them alone silently skipped that jar's deobfuscation, so a name that
        // already says "vanilla" is taken as it is, and when neither does, the class file decides: the bytes are
        // what the remap acts on. Whatever is not vanilla is returned untouched, without the remap pass.
        final ClassReader classReader = new ClassReader(bytes);
        if (!isVanilla(name) && !isVanilla(transformedName)
                && !isVanilla(FMLDeobfuscatingRemapper.INSTANCE.map(classReader.getClassName())))
        {
            return bytes;
        }

        ClassWriter classWriter = new ClassWriter(WRITER_FLAGS);
        ClassRemapper remapAdapter = new FMLRemappingAdapter(classWriter);
        classReader.accept(remapAdapter, READER_FLAGS);
        return classWriter.toByteArray();
    }

    /**
     * @param name a class name in either spelling: a launcher hands over dotted binary names, a class file
     *             stores a slashed internal one
     * @return whether the name is a vanilla class, the only kind this transformer has anything to remap
     */
    private static boolean isVanilla(String name)
    {
        return name.startsWith(VANILLA_DOTTED) || name.startsWith(VANILLA_SLASHED);
    }

    @Override
    public String remapClassName(String name)
    {
        return FMLDeobfuscatingRemapper.INSTANCE.map(name.replace('.','/')).replace('/', '.');
    }

    @Override
    public String unmapClassName(String name)
    {
        return FMLDeobfuscatingRemapper.INSTANCE.unmap(name.replace('.', '/')).replace('/','.');
    }

}
