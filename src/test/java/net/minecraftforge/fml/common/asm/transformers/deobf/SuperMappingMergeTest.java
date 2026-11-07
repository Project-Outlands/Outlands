package net.minecraftforge.fml.common.asm.transformers.deobf;

import com.google.common.collect.ImmutableBiMap;
import com.google.common.collect.ImmutableMap;
import net.minecraft.launchwrapper.LaunchClassLoader;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SuperMappingMergeTest {
    @Test
    void emptyHierarchiesReadObjectOnceAndReuseEmptyMaps() throws Exception {
        try (CountingLoader loader = new CountingLoader()) {
            FMLDeobfuscatingRemapper remapper = remapper(loader);
            for (int index = 0; index < 500; index++)
                remapper.mergeSuperMaps("test/C" + index, "java/lang/Object", new String[0]);
            assertEquals(1, loader.reads.get("java/lang/Object"));
            assertSame(maps(remapper, "methodNameMaps").get("test/C0"), maps(remapper, "methodNameMaps").get("test/C499"));
            assertEquals("unchanged", remapper.mapMethodName("test/C0", "unchanged", "()V"));
        }
    }

    @Test
    void interfacesAndOwnMapsRetainOverwriteOrderAndRawMapsStayIsolated() throws Exception {
        try (CountingLoader loader = new CountingLoader()) {
            FMLDeobfuscatingRemapper remapper = remapper(loader);
            for (String kind : new String[]{"methodNameMaps", "fieldNameMaps"}) {
                Map<String, Map<String, String>> maps = maps(remapper, kind);
                maps.put("Parent", ImmutableMap.of("same", "parent", "inherited", "yes"));
                maps.put("First", ImmutableMap.of("same", "first", "interface", "one"));
                maps.put("Second", ImmutableMap.of("same", "second", "interface", "two"));
            }
            Map<String, String> own = new HashMap<>(Map.of("same", "own"));
            maps(remapper, "rawMethodMaps").put("Child", own);
            remapper.mergeSuperMaps("Child", "Parent", new String[]{"First", "Second"});
            assertEquals(Map.of("same", "own", "inherited", "yes", "interface", "two"), maps(remapper, "methodNameMaps").get("Child"));
            assertEquals(Map.of("same", "second", "inherited", "yes", "interface", "two"), maps(remapper, "fieldNameMaps").get("Child"));
            remapper.mergeSuperMaps("OnlyParent", "Parent", new String[0]);
            assertSame(maps(remapper, "methodNameMaps").get("Parent"), maps(remapper, "methodNameMaps").get("OnlyParent"));
            own.put("same", "changed");
            assertEquals("own", maps(remapper, "methodNameMaps").get("Child").get("same"));
            assertThrows(UnsupportedOperationException.class, () -> maps(remapper, "methodNameMaps").get("Child").put("extra", "x"));
        }
    }

    @Test
    void missingAndFailedParentsRemainRetryable() throws Exception {
        try (CountingLoader loader = new CountingLoader()) {
            FMLDeobfuscatingRemapper remapper = remapper(loader);
            remapper.mergeSuperMaps("Before", "late/Parent", new String[0]);
            assertFalse(maps(remapper, "methodNameMaps").containsKey("late/Parent"));
            loader.classes.put("late/Parent", sample("late/Parent", "java/lang/Object"));
            maps(remapper, "rawMethodMaps").put("late/Parent", Map.of("m()V", "mapped"));
            loader.failNext = true;
            remapper.mergeSuperMaps("Failed", "late/Parent", new String[0]);
            remapper.mergeSuperMaps("After", "late/Parent", new String[0]);
            assertEquals(3, loader.reads.get("late/Parent"));
            assertEquals("mapped", remapper.mapMethodName("After", "m", "()V"));
            assertTrue(maps(remapper, "methodNameMaps").get("Before").isEmpty());
        }
    }

    private static FMLDeobfuscatingRemapper remapper(CountingLoader loader) throws Exception {
        var constructor = FMLDeobfuscatingRemapper.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        FMLDeobfuscatingRemapper result = constructor.newInstance();
        set(result, "classNameBiMap", ImmutableBiMap.of("obf", "mapped"));
        set(result, "classLoader", loader);
        for (String name : new String[]{"methodNameMaps", "fieldNameMaps", "rawMethodMaps", "rawFieldMaps"})
            set(result, name, new HashMap<String, Map<String, String>>());
        return result;
    }

    private static void set(Object owner, String name, Object value) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, String>> maps(FMLDeobfuscatingRemapper owner, String name) throws Exception {
        Field field = FMLDeobfuscatingRemapper.class.getDeclaredField(name);
        field.setAccessible(true);
        return (Map<String, Map<String, String>>) field.get(owner);
    }

    private static byte[] sample(String name, String parent) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, name, null, parent, null);
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static class CountingLoader extends LaunchClassLoader {
        final Map<String, byte[]> classes = new HashMap<>();
        final Map<String, Integer> reads = new HashMap<>();
        boolean failNext;
        CountingLoader() {
            super(new URL[0]);
            classes.put("java/lang/Object", sample("java/lang/Object", null));
        }
        @Override public byte[] getClassBytes(String name) throws IOException {
            reads.merge(name, 1, Integer::sum);
            if (failNext) {
                failNext = false;
                throw new IOException("test transient read failure");
            }
            return classes.get(name);
        }
    }
}
