package net.minecraftforge.client.modlist;

import net.minecraftforge.common.config.Config;

@Config(modid = ModListConstants.OWNER_MOD_ID, name = "catalogue", side = Config.Side.CLIENT)
public final class ModListConfig {
    @Config.Comment({
            "Whether to enable the mod list.",
            "Disabling it stops Outlands from redirecting Forge's mod list calls."
    })
    @Config.LangKey("outlands.config.enable")
    public static boolean enable = true;

    @Config.RequiresMcRestart
    @Config.Comment({
            "The list of library mods' mod ids.",
            "They will have grey names in the mod list."
    })
    @Config.LangKey("outlands.config.library_list")
    public static String[] libraryList = new String[]{
            "forge",
            "FML",
            "mcp",
            "outlands",
            "cleanmix",
            "configanytime",
            "mixinbooter",
            "fugue",
            "scalar"
    };

    @Config.RequiresMcRestart
    @Config.Comment({
            "The list of ignored dependencies' mod ids.",
            "They will not be displayed when searching for dependencies/dependants."
    })
    @Config.LangKey("outlands.config.ignored_dependencies_list")
    public static String[] ignoredDependenciesList = new String[]{
            "minecraft",
            "forge",
            "FML",
            "mcp",
            "outlands"
    };

    @Config.RequiresMcRestart
    @Config.Comment({
            "The list of mod ids that should always use the default item icon.",
            "They will not have random-picked item icons to avoid crashes."
    })
    @Config.LangKey("outlands.config.force_default_icon_list")
    public static String[] forceDefaultIconList = new String[]{};
}
