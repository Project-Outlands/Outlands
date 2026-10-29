package net.minecraftforge.fml.common.discovery;

import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;
import net.minecraftforge.fml.common.launcher.FMLTweaker;
import net.minecraftforge.fml.relauncher.CoreModManager;
import net.minecraftforge.fml.relauncher.libraries.LibraryManager;

import java.io.File;
import java.net.URL;
import java.util.HashMap;

public final class NeidProbe {

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected the game directory");
        }
        File gameDir = new File(args[0]);
        File coreMod = new File(new File(gameDir, "mods"), "neid.jar");

        Launch.minecraftHome = gameDir;
        Launch.blackboard = new HashMap<>();
        Launch.blackboard.put("forgeLaunchArgs", new HashMap<String, String>());
        LaunchClassLoader classLoader = new LaunchClassLoader(new URL[0]);
        try {
            Launch.classLoader = classLoader;
            LibraryManager.setup(gameDir);
            CoreModManager.getIgnoredMods().clear();

            ModDiscoverer discoverer = ModDiscoverer.instance();
            FMLTweaker tweaker = new FMLTweaker();
            discoverer.discoverCoreMods(gameDir, classLoader, tweaker);
            if (!CoreModManager.getIgnoredMods().contains(coreMod.getName())) {
                throw new AssertionError("NEID core plugin was not ignored");
            }
        } finally {
            classLoader.close();
        }
    }

    private NeidProbe() {
    }
}
