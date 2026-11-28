package net.minecraftforge.common;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;
import net.minecraftforge.fml.common.FMLLog;
import zone.rong.mixinbooter.Tags;

/**
 * Settings the game needs before the loader has finished starting: the SDL window, the OpenGL context, input handling
 * and the mod discovery pass.
 *
 * <p>These live in {@code config/forge.cfg} under {@code [early]}: the same file and the same
 * {@link Configuration} instance that {@link ForgeModContainer} owns. That is why this class does not carry
 * {@code @Config}: the classic {@code config.get(...)} API is what keeps one object as the only writer.
 *
 * <p>{@link #loadEarly(File)} is called from {@code FMLLaunchHandler.setupHome} before {@code LibraryManager.setup}
 * and before the window is created. It registers the properties and fills the fields but never saves; the file's
 * other sections only need their comments re-applied by {@code ForgeModContainer.syncConfig} before the first save.
 */
public final class ForgeEarlyConfig
{
    private static final String CATEGORY = "early";
    private static final String WINDOWS_STYLES = CATEGORY + ".modern_windows_styles";

    private static final String[] DEFAULT_LOADING_PLUGIN_BLACKLIST = new String[] {
            "com.cleanroommc.configanytime.ConfigAnytimePlugin",
            "zone.rong.mixinbooter.MixinBooterPlugin",
            "ilib.asm.Loader",
            "org.dimdev.jeid.JEIDLoadingPlugin",
            "ru.fewizz.neid.asm.Plugin",
            "lain.mods.skins.init.forge.asm.Plugin",
            "advancedshader.core.Core",
            "net.shadowfacts.forgelin.preloader.ForgelinPlugin",
            "com.cleanroommc.relauncher.CleanroomEntrypoint",
    };

    // Everything below is read directly by the patched LWJGL classes and the discovery code. Keep the names stable:
    // they are the runtime cache, not the config keys (the keys are the strings passed to config.get below).

    public static int openglVersionMajor = 4;
    public static int openglVersionMinor = 6;
    public static boolean openglCompatProfile = true;

    public static boolean openglDebugContext = false;
    public static boolean openglSrgbContext = false;
    public static boolean openglDoubleBuffer = true;
    public static boolean openglContextNoError = false;

    public static boolean windowStartMaximized = false;
    public static boolean windowStartIconified = false;
    public static boolean windowCentered = true;
    public static boolean windowDecorated = true;
    public static boolean scaleFramebuffer = true;

    public static String linuxVideoDriver = "auto";

    public static boolean imeAvailableInGui = true;

    public static boolean inputInvertWheel = false;
    public static double inputScrollSpeed = 1.0D;
    public static boolean inputCtrlAltText = false;
    public static boolean inputAltGrEscapeCodes = false;
    public static boolean inputKeybindAllMatching = true;
    public static boolean commandCompletion = false;

    public static boolean ignoreScanCache = false;
    public static ModScanDepth modScanDepth = ModScanDepth.FLAT;

    public static boolean customBuiltInModVersion = false;
    public static String configAnytimeVersion = "3.0";
    public static String mixinBooterVersion = Tags.VERSION;

    public static String[] loadingPluginBlacklist = DEFAULT_LOADING_PLUGIN_BLACKLIST.clone();

    public static ModernWindowsStyles modernWindowsStyles = new ModernWindowsStyles();

    /** How deeply the mods directory is searched for jars. */
    public enum ModScanDepth
    {
        /** Only jars directly in {@code mods/} and {@code mods/<mcversion>/}. The historical behaviour. */
        FLAT,
        /** Also jars one directory deeper, so packs can group files: {@code mods/thaumcraft/addons.jar}. */
        ONE_LEVEL
    }

    public static class ModernWindowsStyles
    {
        public boolean useImmersiveDarkMode = true;
        public int systemBackdropType;
        public int windowCorner;
        public boolean useDefaultBorder = true;
        public boolean hideWindowBorder;
        public int borderColor;
        public boolean useDefaultCaption = true;
        public int captionColor;
        public boolean useDefaultText = true;
        public int textColor;
        public boolean updateWindowsTaskbarProgress = true;
        public boolean disableFlashAfterLoaded = false;
    }

    private static boolean loaded;

    private ForgeEarlyConfig() {}

    private static String comment(String... lines)
    {
        return String.join("\n", lines);
    }

    /**
     * Reads the {@code [early]} section of {@code forge.cfg}. Must run before {@code LibraryManager.setup} and
     * {@code Display.create}: it is the only chance to apply {@code modScanDepth}, the loading-plugin blacklist and the
     * window/OpenGL settings for this launch.
     */
    public static void loadEarly(File configDir)
    {
        if (loaded)
            return;
        loaded = true;
        sync(ForgeConfig.get(configDir), false);
    }

    /** Registers/reads the {@code [early]} properties. Saves only when {@code save} is set and something changed. */
    public static void sync(Configuration cfg, boolean save)
    {
        List<String> order = new ArrayList<>();
        Property p;

        p = cfg.get(CATEGORY, "openglVersionMajor", 4,
                "OpenGL major version requested when creating the game context; 2-4 are supported.", 2, 4);
        p.setRequiresMcRestart(true);
        p.setLanguageKey("forge.configgui.early." + p.getName());
        openglVersionMajor = p.getInt(4);
        order.add(p.getName());

        p = cfg.get(CATEGORY, "openglVersionMinor", 6,
                "OpenGL minor version requested when creating the game context; 0-6 are supported (clamped to the major version).", 0, 6);
        p.setRequiresMcRestart(true);
        p.setLanguageKey("forge.configgui.early." + p.getName());
        openglVersionMinor = p.getInt(6);
        order.add(p.getName());

        p = cfg.get(CATEGORY, "openglCompatProfile", true,
                "Use the compatibility profile instead of the core profile when the driver supports both.");
        p.setRequiresMcRestart(true);
        p.setLanguageKey("forge.configgui.early." + p.getName());
        openglCompatProfile = p.getBoolean(true);
        order.add(p.getName());

        p = cfg.get(CATEGORY, "openglDebugContext", false,
                "Enable KHR_debug in the OpenGL context for advanced debugging capabilities.");
        p.setRequiresMcRestart(true);
        p.setLanguageKey("forge.configgui.early." + p.getName());
        openglDebugContext = p.getBoolean(false);
        order.add(p.getName());

        p = cfg.get(CATEGORY, "openglSrgbContext", false,
                "Make the framebuffer use the sRGB color space.");
        p.setRequiresMcRestart(true);
        p.setLanguageKey("forge.configgui.early." + p.getName());
        openglSrgbContext = p.getBoolean(false);
        order.add(p.getName());

        p = cfg.get(CATEGORY, "openglDoubleBuffer", true,
                "Make the framebuffer double-buffered (will cause visual artifacts if disabled).");
        p.setRequiresMcRestart(true);
        p.setLanguageKey("forge.configgui.early." + p.getName());
        openglDoubleBuffer = p.getBoolean(true);
        order.add(p.getName());

        p = cfg.get(CATEGORY, "openglContextNoError", false,
                "Enable GL_KHR_no_error for faster driver code, at the cost of hiding memory corruption caused by OpenGL errors.");
        p.setRequiresMcRestart(true);
        p.setLanguageKey("forge.configgui.early." + p.getName());
        openglContextNoError = p.getBoolean(false);
        order.add(p.getName());

        p = cfg.get(CATEGORY, "windowStartMaximized", false,
                "Start the game window maximized.");
        p.setRequiresMcRestart(true);
        p.setLanguageKey("forge.configgui.early." + p.getName());
        windowStartMaximized = p.getBoolean(false);
        order.add(p.getName());

        p = cfg.get(CATEGORY, "windowStartIconified", false,
                "Start the game window minimized (created hidden).");
        p.setRequiresMcRestart(true);
        p.setLanguageKey("forge.configgui.early." + p.getName());
        windowStartIconified = p.getBoolean(false);
        order.add(p.getName());

        p = cfg.get(CATEGORY, "windowCentered", true,
                "Center the game window on the primary display.");
        p.setRequiresMcRestart(true);
        p.setLanguageKey("forge.configgui.early." + p.getName());
        windowCentered = p.getBoolean(true);
        order.add(p.getName());

        p = cfg.get(CATEGORY, "windowDecorated", true,
                "Should the window have decorations (titlebar, border, close button).");
        p.setRequiresMcRestart(true);
        p.setLanguageKey("forge.configgui.early." + p.getName());
        windowDecorated = p.getBoolean(true);
        order.add(p.getName());

        p = cfg.get(CATEGORY, "scaleFramebuffer", true,
                "Create a high pixel density window so the framebuffer follows the OS content scale.");
        p.setRequiresMcRestart(true);
        p.setLanguageKey("forge.configgui.early." + p.getName());
        scaleFramebuffer = p.getBoolean(true);
        order.add(p.getName());

        p = cfg.get(CATEGORY, "linuxVideoDriver", "auto", comment(
                "Linux-only - which SDL video driver to use: 'auto', 'x11' or 'wayland'.",
                "'auto' picks the Wayland driver whenever the session has a Wayland compositor, and leaves the choice",
                "to SDL (X11/XWayland included) otherwise. Text input, IME candidates and fractional scale work on",
                "either driver; 'x11' stays useful for compositor bugs and X-only capture tools.",
                "The SDL_VIDEO_DRIVER environment variable still overrides this setting."));
        p.setValidValues(new String[] { "auto", "x11", "wayland" });
        p.setRequiresMcRestart(true);
        p.setLanguageKey("forge.configgui.early." + p.getName());
        linuxVideoDriver = p.getString();
        order.add(p.getName());

        p = cfg.get(CATEGORY, "imeAvailableInGui", true, comment(
                "Keep SDL's text input session open whenever a GUI screen is open, instead of only while a text field",
                "has focus. SDL detaches the operating system's input method from the window while text input is off, so",
                "with this disabled the input method cannot be switched to (Ctrl+Space) until a text field takes focus.",
                "Only text fields consume the committed text; an open screen without a focused field just keeps the",
                "input method switchable."));
        p.setLanguageKey("forge.configgui.early." + p.getName());
        imeAvailableInGui = p.getBoolean(true);
        order.add(p.getName());

        p = cfg.get(CATEGORY, "inputInvertWheel", false,
                "Invert the mouse wheel direction.");
        p.setLanguageKey("forge.configgui.early." + p.getName());
        inputInvertWheel = p.getBoolean(false);
        order.add(p.getName());

        p = cfg.get(CATEGORY, "inputScrollSpeed", 1.0D,
                "Multiplier applied to mouse wheel scrolling.");
        p.setLanguageKey("forge.configgui.early." + p.getName());
        inputScrollSpeed = p.getDouble(1.0D);
        order.add(p.getName());

        p = cfg.get(CATEGORY, "inputCtrlAltText", false,
                "Allow text character input when Ctrl+Left Alt are pressed (disables special escape code handling for this key combination).");
        p.setLanguageKey("forge.configgui.early." + p.getName());
        inputCtrlAltText = p.getBoolean(false);
        order.add(p.getName());

        p = cfg.get(CATEGORY, "inputAltGrEscapeCodes", false,
                "Allow AltGr use in Ctrl+key special key combinations (disables text character input handling when AltGr is pressed).");
        p.setLanguageKey("forge.configgui.early." + p.getName());
        inputAltGrEscapeCodes = p.getBoolean(false);
        order.add(p.getName());

        p = cfg.get(CATEGORY, "inputKeybindAllMatching", true, comment(
                "When several bindings share a key and match equally well, fire all of them instead of only the first.",
                "Off restores the older behaviour, which some mods rely on when they bind a key that another mod already uses."));
        p.setLanguageKey("forge.configgui.early." + p.getName());
        inputKeybindAllMatching = p.getBoolean(true);
        order.add(p.getName());

        p = cfg.get(CATEGORY, "commandCompletion", false, comment(
                "Enable modern command completions, ghost text and argument highlighting.",
                "Vanilla Tab completion remains available when this is disabled."));
        p.setLanguageKey("forge.configgui.early." + p.getName());
        commandCompletion = p.getBoolean(false);
        order.add(p.getName());

        p = cfg.get(CATEGORY, "ignoreScanCache", false,
                "Ignore the annotation scan cache, reparse every jar and write the records again.");
        p.setRequiresMcRestart(true);
        p.setLanguageKey("forge.configgui.early." + p.getName());
        ignoreScanCache = p.getBoolean(false);
        order.add(p.getName());

        p = cfg.get(CATEGORY, "modScanDepth", "FLAT", comment(
                "How deeply to search the mods directory: FLAT or ONE_LEVEL.",
                "FLAT (default) reads only mods/*.jar and mods/<mcversion>/*.jar.",
                "ONE_LEVEL also reads mods/*/*.jar, so files can be grouped into folders.",
                "Directory names are labels only: they never affect load order or how a jar is treated."));
        p.setValidValues(new String[] { "FLAT", "ONE_LEVEL" });
        p.setRequiresMcRestart(true);
        p.setLanguageKey("forge.configgui.early." + p.getName());
        try
        {
            modScanDepth = ModScanDepth.valueOf(p.getString().trim().toUpperCase(Locale.ENGLISH));
        }
        catch (IllegalArgumentException e)
        {
            FMLLog.log.warn("Unknown modScanDepth '{}', expected FLAT or ONE_LEVEL; using FLAT.", p.getString());
            modScanDepth = ModScanDepth.FLAT;
            p.setValue("FLAT");
        }
        order.add(p.getName());

        p = cfg.get(CATEGORY, "customBuiltInModVersion", false,
                "Report the configured versions for the bundled MixinBooter and ConfigAnytime containers.");
        p.setRequiresMcRestart(true);
        p.setLanguageKey("forge.configgui.early." + p.getName());
        customBuiltInModVersion = p.getBoolean(false);
        order.add(p.getName());

        p = cfg.get(CATEGORY, "configAnytimeVersion", "3.0",
                "Version reported by the built-in ConfigAnytime container when customBuiltInModVersion is enabled.");
        p.setRequiresMcRestart(true);
        p.setLanguageKey("forge.configgui.early." + p.getName());
        configAnytimeVersion = p.getString();
        order.add(p.getName());

        p = cfg.get(CATEGORY, "mixinBooterVersion", Tags.VERSION,
                "Version reported by the built-in MixinBooter container when customBuiltInModVersion is enabled.");
        p.setRequiresMcRestart(true);
        p.setLanguageKey("forge.configgui.early." + p.getName());
        mixinBooterVersion = p.getString();
        order.add(p.getName());

        p = cfg.get(CATEGORY, "loadingPluginBlacklist", DEFAULT_LOADING_PLUGIN_BLACKLIST, comment(
                "Mods in this list have one or more of the problems below:",
                "- Integrated into Outlands so it must not be loaded from mods",
                "- Too invasive or hacky to be patchable",
                "- Have a better replacement (e.g. JEID vs REID)"));
        p.setRequiresMcRestart(true);
        p.setLanguageKey("forge.configgui.early." + p.getName());
        loadingPluginBlacklist = p.getStringList();
        order.add(p.getName());

        cfg.setCategoryLanguageKey(CATEGORY, "forge.configgui.ctgy.forgeEarlyConfig");
        cfg.setCategoryPropertyOrder(CATEGORY, order);

        List<String> styleOrder = new ArrayList<>();

        p = cfg.get(WINDOWS_STYLES, "useImmersiveDarkMode", true,
                "Enable immersive dark mode, following the Windows theme settings.");
        p.setRequiresMcRestart(true);
        p.setLanguageKey("forge.configgui.early.modern_windows_styles." + p.getName());
        modernWindowsStyles.useImmersiveDarkMode = p.getBoolean(true);
        styleOrder.add(p.getName());

        p = cfg.get(WINDOWS_STYLES, "systemBackdropType", 0,
                "Backdrop type [0:auto 1:none 2:mica 3:acrylic 4:tabbed].");
        p.setRequiresMcRestart(true);
        p.setLanguageKey("forge.configgui.early.modern_windows_styles." + p.getName());
        modernWindowsStyles.systemBackdropType = p.getInt(0);
        styleOrder.add(p.getName());

        p = cfg.get(WINDOWS_STYLES, "windowCorner", 0,
                "Window corner type [0:default 1:do_not_round 2:round 3:round_small].");
        p.setRequiresMcRestart(true);
        p.setLanguageKey("forge.configgui.early.modern_windows_styles." + p.getName());
        modernWindowsStyles.windowCorner = p.getInt(0);
        styleOrder.add(p.getName());

        p = cfg.get(WINDOWS_STYLES, "useDefaultBorder", true,
                "Use the default window border.");
        p.setRequiresMcRestart(true);
        p.setLanguageKey("forge.configgui.early.modern_windows_styles." + p.getName());
        modernWindowsStyles.useDefaultBorder = p.getBoolean(true);
        styleOrder.add(p.getName());

        p = cfg.get(WINDOWS_STYLES, "hideWindowBorder", false,
                "Hide the window border.");
        p.setRequiresMcRestart(true);
        p.setLanguageKey("forge.configgui.early.modern_windows_styles." + p.getName());
        modernWindowsStyles.hideWindowBorder = p.getBoolean(false);
        styleOrder.add(p.getName());

        p = cfg.get(WINDOWS_STYLES, "borderColor", 0,
                "Window border color [RGB hex in int].");
        p.setRequiresMcRestart(true);
        p.setLanguageKey("forge.configgui.early.modern_windows_styles." + p.getName());
        modernWindowsStyles.borderColor = p.getInt(0);
        styleOrder.add(p.getName());

        p = cfg.get(WINDOWS_STYLES, "useDefaultCaption", true,
                "Use the default window caption color.");
        p.setRequiresMcRestart(true);
        p.setLanguageKey("forge.configgui.early.modern_windows_styles." + p.getName());
        modernWindowsStyles.useDefaultCaption = p.getBoolean(true);
        styleOrder.add(p.getName());

        p = cfg.get(WINDOWS_STYLES, "captionColor", 0,
                "Window caption color [RGB hex in int].");
        p.setRequiresMcRestart(true);
        p.setLanguageKey("forge.configgui.early.modern_windows_styles." + p.getName());
        modernWindowsStyles.captionColor = p.getInt(0);
        styleOrder.add(p.getName());

        p = cfg.get(WINDOWS_STYLES, "useDefaultText", true,
                "Use the default window text color.");
        p.setRequiresMcRestart(true);
        p.setLanguageKey("forge.configgui.early.modern_windows_styles." + p.getName());
        modernWindowsStyles.useDefaultText = p.getBoolean(true);
        styleOrder.add(p.getName());

        p = cfg.get(WINDOWS_STYLES, "textColor", 0,
                "Window text color [RGB hex in int].");
        p.setRequiresMcRestart(true);
        p.setLanguageKey("forge.configgui.early.modern_windows_styles." + p.getName());
        modernWindowsStyles.textColor = p.getInt(0);
        styleOrder.add(p.getName());

        p = cfg.get(WINDOWS_STYLES, "updateWindowsTaskbarProgress", true,
                "Use the Outlands taskbar progress bar while the game loads.");
        p.setLanguageKey("forge.configgui.early.modern_windows_styles." + p.getName());
        modernWindowsStyles.updateWindowsTaskbarProgress = p.getBoolean(true);
        styleOrder.add(p.getName());

        p = cfg.get(WINDOWS_STYLES, "disableFlashAfterLoaded", false,
                "Do not flash the taskbar when loading finishes.");
        p.setLanguageKey("forge.configgui.early.modern_windows_styles." + p.getName());
        modernWindowsStyles.disableFlashAfterLoaded = p.getBoolean(false);
        styleOrder.add(p.getName());

        cfg.setCategoryLanguageKey(WINDOWS_STYLES, "forge.configgui.early.modern_windows_styles");
        cfg.setCategoryPropertyOrder(WINDOWS_STYLES, styleOrder);

        if (save && cfg.hasChanged())
            cfg.save();
    }

    /** Test seam: allows a test to point the next {@link #loadEarly(File)} at a different directory. */
    static void resetForTesting()
    {
        loaded = false;
    }
}
