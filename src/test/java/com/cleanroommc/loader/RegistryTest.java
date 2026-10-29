package com.cleanroommc.loader;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the two properties of {@link Registry} that a refactor could silently break: a duplicate id is a conflict
 * rather than an overwrite, and dropping the id index on {@link Registry#freeze()} must not break lookups.
 *
 * <p>The freeze path is the interesting one - it is a deliberate data-structure swap (map to array scan), so a bug
 * there shows up as a mod silently getting the wrong adapter, or none, after discovery ends.
 */
class RegistryTest {

    private static Registry<String> newRegistry() {
        return new Registry<>("test entries", String.class);
    }

    @Test
    void duplicateIdIsRejectedRatherThanOverwritten() {
        Registry<String> registry = newRegistry();
        registry.register("scala", "first");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> registry.register("scala", "second"));

        // The message has to name the clash: two jars claiming one language is a conflict the user must resolve,
        // and keeping whichever registered first hides it forever.
        assertTrue(error.getMessage().contains("scala"), error.getMessage());
        assertEquals(1, registry.size(), "the rejected registration must not replace the existing one");
        assertSame("first", registry.get(0));
    }

    @Test
    void registrationAfterFreezeIsRejected() {
        Registry<String> registry = newRegistry();
        registry.register("java", "adapter");
        registry.freeze();

        assertTrue(registry.isFrozen());
        assertThrows(IllegalStateException.class, () -> registry.register("scala", "late"));
        assertEquals(1, registry.size(), "the late registration must not be stored");
    }

    @Test
    void lookupsStillWorkAfterTheIndexIsDropped() {
        Registry<String> registry = newRegistry();
        int scala = registry.register("scala", "scala-adapter");
        int kotlin = registry.register("kotlin", "kotlin-adapter");
        assertEquals(scala + 1, kotlin);

        registry.freeze();

        // Before the freeze this hits the id index, afterwards the linear scan over the live slots. Both must agree.
        assertEquals(scala, registry.slotOf("scala"));
        assertEquals(kotlin, registry.slotOf("kotlin"));
        assertEquals("kotlin-adapter", registry.get(registry.slotOf("kotlin")));
        assertEquals("scala", registry.idOf(scala));
        assertEquals(-1, registry.slotOf("clojure"));
        assertNull(registry.get(99));
        assertEquals(java.util.List.of("scala", "kotlin"), registry.ids());
    }

    @Test
    void blankIdAndWrongTypeAreRejected() {
        Registry<String> registry = newRegistry();
        assertThrows(IllegalArgumentException.class, () -> registry.register("", "value"));
        assertThrows(IllegalArgumentException.class, () -> registry.register("  ", "value"));
        assertThrows(IllegalArgumentException.class, () -> registry.register("null-value", null));
        assertEquals(0, registry.size());
        assertFalse(registry.isFrozen());
    }
}
