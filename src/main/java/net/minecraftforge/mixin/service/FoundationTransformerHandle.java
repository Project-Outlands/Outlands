package net.minecraftforge.mixin.service;

import org.spongepowered.asm.service.ITransformer;

import net.minecraft.launchwrapper.IClassTransformer;

/**
 * Presents a class transformer to mixin as one of its own, so that the transformer chain can be
 * replayed over mixin bytecode.
 */
final class FoundationTransformerHandle implements ITransformer {

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

}
