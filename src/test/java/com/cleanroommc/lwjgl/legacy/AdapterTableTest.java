package com.cleanroommc.lwjgl.legacy;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Type;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Resolves every {@code CTOR}/{@code ADAPTER} entry against the real classes, so a rename of the shim or adapter
 * method cannot silently turn into a {@code NoSuchMethodError} at runtime.
 */
class AdapterTableTest {

    @Test
    void adapterEntriesResolveToStaticHelpers() throws Exception {
        assertTrue(RewriteTable.ADAPTER.size() > 0);
        for (Map.Entry<String, String> entry : RewriteTable.ADAPTER.entrySet()) {
            String[] source = entry.getKey().split("\0");
            String[] target = entry.getValue().split("\0");
            Class<?> adapter = Class.forName(target[0].replace('/', '.'));
            Method method = adapter.getDeclaredMethod(target[1], parameterTypes(target[2]));
            String what = source[0] + '.' + source[1] + source[2] + " -> " + target[0] + '.' + target[1];

            assertTrue(Modifier.isStatic(method.getModifiers()), "adapter must be static: " + what);
            assertTrue(Modifier.isPublic(method.getModifiers()), "adapter must be public: " + what);
            assertEquals(Type.getReturnType(target[2]).getClassName(), method.getReturnType().getName(),
                    "adapter return type: " + what);

            Type[] sourceArgs = Type.getArgumentTypes(source[2]);
            Type[] adapterArgs = Type.getArgumentTypes(target[2]);
            if (adapterArgs.length == sourceArgs.length + 1) {
                assertEquals(source[0], adapterArgs[0].getInternalName(),
                        "instance adapter must take the receiver first: " + what);
            }
        }
    }

    @Test
    void ctorEntriesResolveToAllocationSubclasses() throws Exception {
        assertTrue(RewriteTable.CTOR.size() > 0);
        for (Map.Entry<String, String> entry : RewriteTable.CTOR.entrySet()) {
            String[] source = entry.getKey().split("\0");
            Class<?> original = Class.forName(source[0].replace('/', '.'));
            Class<?> replacement = Class.forName(entry.getValue().replace('/', '.'));

            assertEquals("<init>", source[1], "allocation redirects only constructors");
            assertTrue(original.isAssignableFrom(replacement),
                    entry.getValue() + " must be usable where " + source[0] + " is expected");
            replacement.getDeclaredConstructor(parameterTypes(source[2]));
        }
    }

    private static Class<?>[] parameterTypes(String methodDesc) throws ClassNotFoundException {
        Type[] args = Type.getArgumentTypes(methodDesc);
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            types[i] = primitiveOrClass(args[i]);
        }
        return types;
    }

    private static Class<?> primitiveOrClass(Type type) throws ClassNotFoundException {
        switch (type.getSort()) {
            case Type.BOOLEAN: return boolean.class;
            case Type.BYTE: return byte.class;
            case Type.CHAR: return char.class;
            case Type.SHORT: return short.class;
            case Type.INT: return int.class;
            case Type.FLOAT: return float.class;
            case Type.LONG: return long.class;
            case Type.DOUBLE: return double.class;
            default: return Class.forName(type.getClassName());
        }
    }
}
