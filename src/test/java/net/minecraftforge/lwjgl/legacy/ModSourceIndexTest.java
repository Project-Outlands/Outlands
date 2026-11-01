package net.minecraftforge.lwjgl.legacy;

import java.io.File;
import java.util.List;

import net.minecraftforge.fml.common.discovery.DiscoveredMod;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The policy the LWJGL2 transformer applies: whose classes may be rewritten.
 *
 * <p>Two separate questions decide it. The scan decides what is a mod at all; the {@code mcmod.info} decides whether
 * that mod was built against an environment that still offers the LWJGL2 API.
 */
public class ModSourceIndexTest {

    private static final File SOURCE = new File("mods/sample.jar").getAbsoluteFile();

    @Test
    public void unscannedSourceMakesNoStatement() {
        assertEquals(ModSourceIndex.Kind.UNCLASSIFIED, ModSourceIndex.classify(null));
    }

    @Test
    public void manifestModTypeAloneIsNotEnough() {
        // a jar scanned but without any mod marking: ModType defaults to FML, which must not make it a mod
        assertEquals(ModSourceIndex.Kind.NON_MOD, ModSourceIndex.classify(mod("FML", List.of(), false, false)));
        // nor does an explicit CRL make a non-mod into a modern one: the scan decides what is a mod
        assertEquals(ModSourceIndex.Kind.NON_MOD, ModSourceIndex.classify(mod("CRL", List.of(), false, true)));
    }

    @Test
    public void modsWithoutCurrentMetadataAreLegacy() {
        // FML, no manifest at all, and CRL alike: all of them were written while both LWJGL generations were reachable
        assertEquals(ModSourceIndex.Kind.LEGACY, ModSourceIndex.classify(mod("FML", List.of("sample"), false, false)));
        assertEquals(ModSourceIndex.Kind.LEGACY, ModSourceIndex.classify(mod("FML", List.of("legacy"), false, false)));
        assertEquals(ModSourceIndex.Kind.LEGACY, ModSourceIndex.classify(mod("CRL", List.of("old"), false, false)));
    }

    @Test
    public void loaderModTypeNoLongerSkipsRewriting() {
        // the reason this policy exists: a jar that declared ModType: CRL could call both APIs, so its LWJGL2 call
        // sites are exactly what this transformer is for
        assertEquals(ModSourceIndex.Kind.LEGACY, ModSourceIndex.classify(mod("CRL", List.of("loadermod"), false, false)));
        assertEquals(ModSourceIndex.Kind.LEGACY, ModSourceIndex.classify(mod("CRL", List.of(), true, false)));
    }

    @Test
    public void currentMetadataSchemaIsTheOnlyThingThatSkips() {
        assertEquals(ModSourceIndex.Kind.MODERN, ModSourceIndex.classify(mod("FML", List.of("modern"), false, true)));
        assertEquals(ModSourceIndex.Kind.MODERN, ModSourceIndex.classify(mod("CRL", List.of("modern"), false, true)));
        // metadata does not decide what a mod is, only how to treat one
        assertEquals(ModSourceIndex.Kind.NON_MOD, ModSourceIndex.classify(mod("FML", List.of(), false, true)));
    }

    @Test
    public void mixinOrCoremodMarkingMakesItAMod() {
        assertEquals(ModSourceIndex.Kind.LEGACY, ModSourceIndex.classify(mod("FML", List.of(), true, false)));
        assertEquals(ModSourceIndex.Kind.MODERN, ModSourceIndex.classify(mod("FML", List.of(), true, true)));
    }

    @Test
    public void everythingButModernModsKeepsItsRewrites() {
        // Minecraft's own OpenAL binding needs the rewrite and is not a mod at all
        assertEquals(ModSourceIndex.Kind.NON_MOD, ModSourceIndex.classify(mod("FML", List.of(), false, false)));
        assertEquals(ModSourceIndex.Kind.LEGACY, ModSourceIndex.classify(mod("FML", List.of("renderlib"), false, false)));
        assertEquals(ModSourceIndex.Kind.LEGACY, ModSourceIndex.classify(mod("CRL", List.of("codechickenlib"), false, false)));
    }

    @Test
    public void classResourceResolvesToItsRoot() {
        assertEquals(new File("/game/mods/sample.jar"),
                ModSourceIndex.sourceOf(url("jar:file:/game/mods/sample.jar!/com/example/Sample.class"), "com/example/Sample"));
        assertEquals(new File("/game/build/classes/java/main"),
                ModSourceIndex.sourceOf(url("file:/game/build/classes/java/main/com/example/Sample.class"), "com/example/Sample"));
    }

    private static java.net.URL url(String external) {
        try {
            return new java.net.URL(null, external, new java.net.URLStreamHandler() {
                @Override
                protected java.net.URLConnection openConnection(java.net.URL u) {
                    throw new UnsupportedOperationException();
                }
            });
        } catch (java.net.MalformedURLException e) {
            throw new AssertionError(e);
        }
    }

    private static DiscoveredMod mod(String modType, List<String> modIds, boolean mixinAttributes, boolean modernMetadata) {
        return new DiscoveredMod(SOURCE, null, modIds, modType, modernMetadata, mixinAttributes, false, false, null, null);
    }
}
