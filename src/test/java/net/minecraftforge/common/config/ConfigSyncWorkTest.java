package net.minecraftforge.common.config;

import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.FMLLaunchHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConfigSyncWorkTest {
    @TempDir Path directory;
    private Side previousSide;
    @BeforeEach void rememberSide() { previousSide = FMLLaunchHandler.side(); }
    @AfterEach void reset() throws Exception {
        ConfigManager.setEnvironment(null);
        side(previousSide);
    }

    private static void side(Side value) throws Exception {
        Field field = FMLLaunchHandler.class.getDeclaredField("side");
        field.setAccessible(true);
        field.set(null, value);
    }

    @Test
    void identicalTextKeepsFileIdentityAndChangedTextStillReplacesIt() throws Exception {
        Path file = directory.resolve("nested/settings.cfg");
        String text = "value=中文\r\n".repeat(5000);
        ConfigFile.writeAtomic(file.toFile(), text);
        FileTime marker = FileTime.fromMillis(123456000L);
        Files.setLastModifiedTime(file, marker);
        ConfigFile.writeAtomic(file.toFile(), text);
        assertEquals(marker, Files.getLastModifiedTime(file));
        assertEquals(text, Files.readString(file));
        String changed = text.substring(0, text.length() - 2) + "xx";
        ConfigFile.writeAtomic(file.toFile(), changed);
        assertEquals(changed, Files.readString(file));
        Files.writeString(file, "externally replaced");
        ConfigFile.writeAtomic(file.toFile(), text);
        assertEquals(text, Files.readString(file));
        try (var files = Files.list(file.getParent())) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void repeatedSyncReadsCurrentObjectsAndMapsAndKeepsPresentationMetadata() throws Exception {
        side(Side.CLIENT);
        ConfigManager.setEnvironment(new ConfigEnvironment() {
            @Override public File configDir() { return directory.toFile(); }
            @Override public File userConfigDir() { return null; }
            @Override public ClassLoader modClassLoader() { return getClass().getClassLoader(); }
            @Override public boolean loading() { return true; }
            @Override public Side side() { return FMLLaunchHandler.side(); }
        });
        Values.nested = new Category();
        Values.map = new LinkedHashMap<>(Map.of("first", 1));
        Values.count = 2;
        ConfigManager.register(Values.class);
        Path path = directory.resolve("syncwork.cfg");
        String initial = Files.readString(path);
        assertTrue(initial.contains("a stable comment"));
        assertFalse(initial.contains("ignored"));
        assertFalse(initial.contains("serverValue"));
        FileTime marker = FileTime.fromMillis(123456000L);
        Files.setLastModifiedTime(path, marker);
        ConfigManager.sync(Values.class);
        assertEquals(marker, Files.getLastModifiedTime(path));
        Values.nested = new Category();
        Values.nested.value = 17;
        Values.map.put("added", 8);
        Values.count = 9;
        side(Side.SERVER);
        ConfigManager.sync(Values.class);
        String updated = Files.readString(path);
        assertTrue(updated.contains("I:value=17"), updated);
        assertTrue(updated.contains("I:added=8"), updated);
        assertTrue(updated.contains("I:count=9"), updated);
        assertTrue(updated.contains("serverValue"), updated);
        assertTrue(updated.contains("a stable comment"));
    }

    @Config(modid = "syncwork")
    public static class Values {
        @Config.Comment("a stable comment")
        @Config.LangKey("custom.count")
        @Config.RequiresMcRestart
        @Config.RangeInt(min = 0, max = 100)
        public static int count = 2;
        public static Category nested = new Category();
        public static Map<String, Integer> map = new LinkedHashMap<>();
        @Config.Ignore public static int ignored = 1;
        @Config.SideOnly(Config.Side.SERVER) public static int serverValue = 4;
    }

    public static class Category {
        public int value = 3;
    }
}
