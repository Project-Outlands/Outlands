package net.minecraftforge.mixin.service;

import org.spongepowered.asm.service.ILegacyClassTransformer;

import net.minecraft.launchwrapper.IClassTransformer;

/**
 * Presents a class transformer to mixin as one of its own, so that the transformer chain can be
 * replayed over mixin bytecode.
 *
 * <p>This has to be a legacy transformer, not merely a transformer: the replay only collects
 * {@link ILegacyClassTransformer} instances, and a wrapped transformer which does not answer that
 * question is dropped without a word. The replay silently applying nothing is worse than it sounds
 * -- the deobfuscation transformer is part of the chain, so mixin reads the game's classes in the
 * names they have in the jar (obfuscated) while everything at runtime has the names it is given
 * (srg), and a mixin which names a vanilla superclass fails to find it in a target's hierarchy.
 */
final class FoundationTransformerHandle implements ILegacyClassTransformer {

    private final IClassTransformer transformer;

    FoundationTransformerHandle(IClassTransformer transformer) {
        this.transformer = transformer;
    }

    @Override
    public String getName() {
        return this.transformer.getClass().getName();
    }

    @Override
    public boolean isDelegationExcluded() {
        return false;
    }

    @Override
    public byte[] transformClassBytes(String name, String transformedName, byte[] basicClass) {
        return this.transformer.transform(name, transformedName, basicClass);
    }

}
