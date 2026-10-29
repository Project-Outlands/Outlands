package net.minecraftforge.fml.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link ModContainer#hasInstance()}, the signal the loader uses to tell "this container has no mod object by
 * design" from "this mod failed to construct".
 *
 * <p>Worth asserting despite looking obvious: a container that overrides {@code hasInstance()} without agreeing with
 * {@code getMod()} would make the loader either report a spurious crash for declarative mods or silently accept a
 * genuinely broken one, and neither shows up in any other test.
 */
class ModContainerTest {

    @Test
    void defaultReflectsGetMod() {
        assertFalse(new DummyModContainer().hasInstance(), "DummyModContainer has no mod object");

        ModContainer withInstance = new DummyModContainer() {
            @Override
            public Object getMod() {
                return new Object();
            }
        };
        assertTrue(withInstance.hasInstance());
    }

    @Test
    void declarativeStyleContainerReportsItselfAsInstanceLess() {
        // The shape a declarative container will have: no mod object, but a real source file.
        ModContainer declarative = new DummyModContainer() {
            @Override
            public Object getMod() {
                return null;
            }
        };
        assertFalse(declarative.hasInstance());
    }
}
