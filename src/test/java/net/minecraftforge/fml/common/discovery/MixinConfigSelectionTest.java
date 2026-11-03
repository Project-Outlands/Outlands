package net.minecraftforge.fml.common.discovery;

import java.io.File;
import java.util.List;

import net.minecraftforge.fml.relauncher.Side;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers which mixin configurations the early scan hands to Mixin itself.
 *
 * <p>Kept separate from {@link JarMixinsTest}: that one is about reading the declaration, this one about what the
 * loader does with it. Mixin's registry is static and unobservable from a unit test, so the decision is a plain
 * function over the scan record and the tests call it directly.
 */
class MixinConfigSelectionTest {

    private static DiscoveredMod jar(boolean mixinManifestAttributes, JarDeclaration.MixinConfig... mixins) {
        return new DiscoveredMod(new File("mods/test.jar"), null, List.of("testmod"), "FML", List.of(mixins),
                true, mixinManifestAttributes, false, false, null, null);
    }

    private static JarDeclaration.MixinConfig config(String name, JarDeclaration.Side side) {
        return new JarDeclaration.MixinConfig(name, side);
    }

    @Test
    void aManifestOnlyJarHasNothingToRegister() {
        // Mixin's own platform agent reads MixinConfigs when the container is prepared. Registering the same names again
        // would work (Mixin drops the second copy by name) and tell the log a story that did not happen.
        DiscoveredMod mod = jar(true);

        assertTrue(mod.declaresMixins(), "a manifest list still counts as declaring mixins");
        assertEquals(List.of(), mod.mixinConfigsFor(Side.CLIENT));
    }

    @Test
    void aDeclaredJarIsRegisteredByTheLoader() {
        DiscoveredMod mod = jar(false, config("mymod.mixins.json", JarDeclaration.Side.BOTH));

        assertTrue(mod.declaresMixins());
        assertEquals(List.of("mymod.mixins.json"), mod.mixinConfigsFor(Side.CLIENT));
    }

    @Test
    void onlyThisSidesConfigsAreRegistered() {
        // Registering a client config on a server is not harmless: its target classes may not exist there, so a
        // required mixin in it fails at the first transform instead of never applying.
        DiscoveredMod mod = jar(false,
                config("mymod.mixins.json", JarDeclaration.Side.BOTH),
                config("mymod.client.mixins.json", JarDeclaration.Side.CLIENT),
                config("mymod.server.mixins.json", JarDeclaration.Side.SERVER));

        assertEquals(List.of("mymod.mixins.json", "mymod.client.mixins.json"),
                mod.mixinConfigsFor(Side.CLIENT));
        assertEquals(List.of("mymod.mixins.json", "mymod.server.mixins.json"),
                mod.mixinConfigsFor(Side.SERVER));
    }

    @Test
    void declarationOrderIsKept() {
        // Not a sorting guarantee: Mixin sorts a batch by priority and breaks ties by registration order, so the order a
        // jar writes is the tie-break. Reordering it here would silently overrule the author.
        DiscoveredMod mod = jar(false,
                config("z.mixins.json", JarDeclaration.Side.BOTH),
                config("a.mixins.json", JarDeclaration.Side.BOTH),
                config("m.mixins.json", JarDeclaration.Side.BOTH));

        assertEquals(List.of("z.mixins.json", "a.mixins.json", "m.mixins.json"),
                mod.mixinConfigsFor(Side.CLIENT));
    }

    @Test
    void withNoKnownSideEverythingIsKept() {
        // Only reachable outside a launch (a tool, a test). Dropping declarations nobody can judge would be worse than
        // holding them.
        DiscoveredMod mod = jar(false,
                config("client.mixins.json", JarDeclaration.Side.CLIENT),
                config("server.mixins.json", JarDeclaration.Side.SERVER));

        assertEquals(2, mod.mixinConfigsFor(null).size());
    }

    @Test
    void aJarThatDeclaresNothingDeclaresNothing() {
        DiscoveredMod mod = jar(false);

        assertFalse(mod.declaresMixins());
        assertEquals(List.of(), mod.mixinConfigsFor(Side.CLIENT));
    }
}
