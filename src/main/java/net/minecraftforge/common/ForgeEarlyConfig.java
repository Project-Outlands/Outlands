package net.minecraftforge.common;

import net.minecraftforge.common.config.Config;
import zone.rong.mixinbooter.Tags;

@Config(modid = ForgeVersion.MOD_ID, name = ForgeVersion.MOD_ID + "_early")
public class ForgeEarlyConfig {

    public static int OPENGL_VERSION_MAJOR = 4;
    public static int OPENGL_VERSION_MINOR = 6;
    public static boolean OPENGL_COMPAT_PROFILE = true;

    public static boolean RAW_INPUT = true;

    //TODO : make CATEGORY?
    //TODO : make the config display name lowcase?
    
    public static boolean WINDOW_START_MAXIMIZED = false;
    public static boolean WINDOW_START_FOCUSED = true;
    public static boolean WINDOW_START_ICONIFIED = false;

    @Config.Comment({
        "Linux-only - which SDL video driver to use: 'auto', 'x11' or 'wayland'.",
        "'auto' prefers XWayland in a Wayland session (IME input methods reach X11 clients through XIM) and",
        "falls back to native Wayland when the X11 driver cannot be initialised.",
        "The SDL_VIDEO_DRIVER environment variable still overrides this setting."
    })
    public static String LINUX_VIDEO_DRIVER = "auto";

    @Config.Comment({
        "Keep SDL's text input session open whenever a GUI screen is open, instead of only while a text field",
        "has focus. SDL detaches the operating system's input method from the window while text input is off, so",
        "with this disabled the input method cannot be switched to (Ctrl+Space) until a text field takes focus.",
        "Only text fields consume the committed text; an open screen without a focused field just keeps the",
        "input method switchable."
    })
    public static boolean IME_AVAILABLE_IN_GUI = true;

    public static boolean WINDOW_CENTERED = true;

    @Config.Comment("Should the window have decorations (titlebar, border, close button)")
    public static boolean WINDOW_DECORATED = true;

    @Config.Comment("Windows-only - should borderless window have height increased by 1 to solve flickering on un-focusing")
    public static boolean WINDOW_BORDERLESS_WINDOWS_COMPATIBILITY = true;

    @Config.Comment("Enable KHR_debug in the OpenGL context for advanced debugging capabilities")
    public static boolean OPENGL_DEBUG_CONTEXT = false;

    @Config.Comment("Make the framebuffer use the sRGB color space")
    public static boolean OPENGL_SRGB_CONTEXT = false;

    @Config.Comment("Make the framebuffer double-buffered (will cause visual artifacts if disabled)")
    public static boolean OPENGL_DOUBLEBUFFER = true;

    @Config.Comment("Enable GL_KHR_no_error to use faster driver code, but which can cause memory corruption in case of OpenGL errors")
    public static boolean OPENGL_CONTEXT_NO_ERROR = false;

    public static boolean DECORATED = true;

    public static boolean INPUT_INVERT_WHEEL = false;

    public static double INPUT_SCROLL_SPEED = 1.0;

    @Config.Comment("Allow text character input when Ctrl+Left Alt are pressed (disables special escape code handling for this combination of keys)")
    public static boolean INPUT_CTRL_ALT_TEXT = false;

    @Config.Comment("Allows AltGr use in Ctrl+key special key combinations (disables text character input handling when AltGr is pressed)")
    public static boolean INPUT_ALTGR_ESCAPE_CODES = false;

    @Config.Comment({
        "When several bindings share a key and match equally well, fire all of them instead of only the first.",
        "Off restores the older behaviour, which some mods rely on when they bind a key that another mod already uses."
    })
    public static boolean INPUT_KEYBIND_ALL_MATCHING = true;

    @Config.Comment({
        "Enable modern command completions, ghost text and argument highlighting.",
        "Vanilla Tab completion remains available when this is disabled."
    })
    public static boolean COMMAND_COMPLETION = false;

    @Config.Comment("Linux-only - change the X11 class name, which is used by your window manager to identify the running application")
    public static String X11_CLASS_NAME = "minecraft";

    @Config.Comment("MAC OS-only - identifier used to save and restore the window position and size")
    public static String COCOA_FRAME_NAME = "minecraft";
    @Config.Comment("MAC OS & Wayland - create a high pixel density window so the framebuffer follows the OS content scale instead of the logical window size")
    public static boolean SCALE_FRAMEBUFFER = true;

    @Config.Comment({
        "Disable the Fugue/Scalar coremod presence check at startup.",
        "When enabled, the patch mod check is skipped and always returns true.",
        "When disabled, checks whether Fugue (https://www.curseforge.com/minecraft/mc-mods/fugue)",
        "and Scalar (https://www.curseforge.com/minecraft/mc-mods/scalar-legacy) are present.",
        "Only disable this if you know what you are doing!"
    })
    public static boolean DISABLE_PATCH_MOD_CHECK = false;

    @Config.Comment({
        "Ignore annotation cache, reparse everything and write down again"
    })
    @Config.RequiresMcRestart
    public static boolean IGNORE_SCAN_CACHE = false;

    public static boolean CUSTOM_BUILT_IN_MOD_VERSION = false;
    public static String CONFIG_ANY_TIME_VERSION = "3.0";
    public static String MIXIN_BOOTER_VERSION = Tags.VERSION;

    @Config.Comment("""
            Mods in this list have one or more of the problems list below:
            - Integrated into Cleanroom so shouldn't load it from mods
            - Too invasive or hacky that it is unpatchable
            - Have a better replacement that compats e.g. JEID vs REID
            """)
    public static String[] LOADING_PLUGIN_BLACKLIST = new String[] {
            "com.cleanroommc.configanytime.ConfigAnytimePlugin",
            "zone.rong.mixinbooter.MixinBooterPlugin",
            "ilib.asm.Loader",
            "org.dimdev.jeid.JEIDLoadingPlugin",
            "lain.mods.skins.init.forge.asm.Plugin",
            "advancedshader.core.Core",
            "net.shadowfacts.forgelin.preloader.ForgelinPlugin",
            "com.cleanroommc.relauncher.CleanroomEntrypoint",
    };

    public static CategoryOpenAlContext OPENAL_CONTEXT = new CategoryOpenAlContext();
    public static class CategoryOpenAlContext{
        @Config.Comment("Enable HRTF sound support")
        public boolean ENABLE_HRTF = false;
    }

    public static ModernWindowsStyles MODERN_WINDOWS_STYLES = new ModernWindowsStyles();
    public static class ModernWindowsStyles {
        @Config.Comment("Enable immersive dark mode, it will follow windows theme settings")
        public boolean USE_IMMERSIVE_DARK_MODE = true;
        @Config.Comment("Backdrop type [0:auto 1:none 2:mica 3:acrylic 4:tabbed]")
        public int SYSTEM_BACKDROP_TYPE;
        @Config.Comment("Window corner type [0:default 1:do_not_round 2:round 3:round_small]")
        public int WINDOW_CORNER;
        @Config.Comment("Use default corner")
        public boolean USE_DEFAULT_BORDER = true;
        @Config.Comment("Hide window border")
        public boolean HIDE_WINDOW_BORDER;
        @Config.Comment("Window border color [RGB hex in int]")
        public int BORDER_COLOR;
        @Config.Comment("Use default caption color")
        public boolean USE_DEFAULT_CAPTION = true;
        @Config.Comment("Window caption color [RGB hex in int]")
        public int CAPTION_COLOR;
        @Config.Comment("Use default text color")
        public boolean USE_DEFAULT_TEXT = true;
        @Config.Comment("Window text color [RGB hex in int]")
        public int TEXT_COLOR;
        @Config.Comment("Use cleanroom embed loading progressbar handling")
        public boolean UPDATE_WINDOWS_TASKBAR_PROGRESS = true;
        @Config.Comment("Disable flash after loaded")
        public boolean DISABLE_FLASH_AFTER_LOADED = false;
    }
}
