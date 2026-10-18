package zone.rong.mixinbooter.service;

import java.io.File;
import java.util.Set;

public final class ModDiscoverer {

    private ModDiscoverer() { }

    public static boolean isModPresent(String modId) {
        return net.minecraftforge.fml.common.discovery.ModDiscoverer.instance().isModPresent(modId);
    }

    public static Set<String> getPresentMods() {
        return net.minecraftforge.fml.common.discovery.ModDiscoverer.instance().presentMods();
    }

    public static Set<File> getModSources(String modId) {
        return net.minecraftforge.fml.common.discovery.ModDiscoverer.instance().modSources(modId);
    }

    public static String getModFromSource(File source) {
        return net.minecraftforge.fml.common.discovery.ModDiscoverer.instance().modFromSource(source);
    }

    public static Set<String> getModsFromSource(File source) {
        return net.minecraftforge.fml.common.discovery.ModDiscoverer.instance().modsFromSource(source);
    }

    public static void applyForceLoadAsMod() {
        // NO-OP
    }

    public static void rescueDroppedCoremods() {
        // NO-OP
    }

}
