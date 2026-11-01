package zone.rong.mixinbooter.util;

import net.minecraftforge.fml.relauncher.FMLLaunchHandler;

public class Environment {

    public static String minecraftVersion() {
        return "1.12.2";
    }

    public static boolean inDev() {
        return FMLLaunchHandler.isDeobfuscatedEnvironment();
    }

    public static String side() {
        return FMLLaunchHandler.side().name();
    }

}
