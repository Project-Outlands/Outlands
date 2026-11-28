package net.minecraftforge.mixin.service;

import org.objectweb.asm.commons.Remapper;
import org.spongepowered.asm.logging.ILogger;
import org.spongepowered.asm.mixin.extensibility.IRemapper;
import org.spongepowered.asm.service.MixinService;
import org.spongepowered.asm.util.ObfuscationUtil;

import net.minecraftforge.fml.common.asm.transformers.deobf.FMLDeobfuscatingRemapper;

/**
 * Presents the loader's own remapper to mixin, which works in mapped names. A name mixin already
 * has is looked up first and left alone when the remapper does not know it, so that a name which
 * is already mapped is not unmapped again and looked up a second time.
 */
public class FMLRemapper<T extends Remapper> implements IRemapper, ObfuscationUtil.IClassRemapper {

    private static final ILogger logger = MixinService.getService().getLogger("mixin");

    private final T remapper;
    private final Unmapper unmapper;

    public FMLRemapper(T remapper) {
        this.remapper = remapper;
        this.unmapper = remapper instanceof Unmapper ? (Unmapper) remapper : null;
    }

    /**
     * A remapper which can turn a mapped name back into the obfuscated one, which is what makes
     * the second lookup below possible.
     */
    public interface Unmapper {

        String unmap(String typeName);

    }

    @Override
    public String mapMethodName(String owner, String name, String desc) {
        if (owner == null || name == null || desc == null) {
            return name;
        }
        String newName = this.remapper.mapMethodName(owner, name, desc);
        if (!newName.equals(name) || this.unmapper == null) {
            return newName;
        }
        String obfOwner = this.unmapper.unmap(owner);
        String obfDesc = this.unmapDesc(desc);
        logger.debug("Remapping obfuscated method {}{} for {}", name, obfDesc, obfOwner);
        return this.remapper.mapMethodName(obfOwner, name, obfDesc);
    }

    @Override
    public String mapFieldName(String owner, String name, String desc) {
        if (owner == null || name == null || desc == null) {
            return name;
        }
        String newName = this.remapper.mapFieldName(owner, name, desc);
        if (!newName.equals(name) || this.unmapper == null) {
            return newName;
        }
        String obfOwner = this.unmapper.unmap(owner);
        String obfDesc = this.unmapDesc(desc);
        logger.debug("Remapping obfuscated field {}{} for {}", name, obfDesc, obfOwner);
        return this.remapper.mapFieldName(obfOwner, name, obfDesc);
    }

    @Override
    public String map(String typeName) {
        return typeName == null ? null : this.remapper.map(typeName);
    }

    @Override
    public String unmap(String typeName) {
        return typeName == null || this.unmapper == null ? typeName : this.unmapper.unmap(typeName);
    }

    @Override
    public String mapDesc(String desc) {
        return desc == null ? null : this.remapper.mapDesc(desc);
    }

    @Override
    public String unmapDesc(String desc) {
        if (desc == null) {
            return null;
        }
        String unmapped = ObfuscationUtil.unmapDescriptor(desc, this);
        return unmapped != null ? unmapped : desc;
    }

    @Override
    public String toString() {
        return this.getClass().getSimpleName();
    }

}
