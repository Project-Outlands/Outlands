package net.minecraftforge.mixin.service;

import java.util.Collection;
import java.util.Collections;

import org.spongepowered.asm.launch.platform.IMixinPlatformServiceAgent;
import org.spongepowered.asm.launch.platform.MixinPlatformManager;
import org.spongepowered.asm.launch.platform.container.IContainerHandle;
import org.spongepowered.asm.mixin.MixinEnvironment.Phase;
import org.spongepowered.asm.util.IConsumer;

import net.minecraftforge.fml.relauncher.FMLLaunchHandler;

/**
 * Tells mixin which side the game is on. The service cannot answer this itself, so it registers
 * this agent, which is the hook the service base class consults. Configs list sided mixins
 * separately and skip them with a warning when the side is unknown.
 */
public class MixinSideAgent implements IMixinPlatformServiceAgent {

    @Override
    public void init() {
    }

    @Override
    public String getSideName() {
        return FMLLaunchHandler.side().name();
    }

    @Override
    public Collection<IContainerHandle> getMixinContainers() {
        return Collections.emptyList();
    }

    @Override
    public void wire(Phase phase, IConsumer<Phase> phaseConsumer) {
    }

    @Override
    public void unwire() {
    }

    @Override
    public AcceptResult accept(MixinPlatformManager manager, IContainerHandle handle) {
        return AcceptResult.REJECTED;
    }

    @Override
    public void initPrimaryContainer() {
    }

    @Override
    public String getPhaseProvider() {
        return null;
    }

    @Override
    public void prepare() {
    }

    @Override
    public void inject() {
    }

}
