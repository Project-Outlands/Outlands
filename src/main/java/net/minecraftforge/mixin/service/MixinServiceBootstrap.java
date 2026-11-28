package net.minecraftforge.mixin.service;

import org.spongepowered.asm.service.IMixinServiceBootstrap;

public class MixinServiceBootstrap implements IMixinServiceBootstrap {

    private static final String OWN_SERVICE = "net.minecraftforge.mixin.service.MixinService";

    @Override
    public String getName() {
        return "mixin";
    }

    @Override
    public String getServiceClassName() {
        return OWN_SERVICE;
    }

    @Override
    public void bootstrap() { }

}
