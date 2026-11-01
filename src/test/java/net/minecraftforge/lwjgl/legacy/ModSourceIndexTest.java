package net.minecraftforge.lwjgl.legacy;

import java.io.File;
import java.util.List;

import net.minecraftforge.fml.common.discovery.DiscoveredMod;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The policy the LWJGL2 transformer applies: whose classes may be rewritten.
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
        assertEquals(ModSourceIndex.Kind.NON_MOD, ModSourceIndex.classify(mod("FML", List.of(), false)));
        // nor does an explicit CRL make a non-mod into a Cleanroom mod: the scan decides what is a mod
        assertEquals(ModSourceIndex.Kind.NON_MOD, ModSourceIndex.classify(mod("CRL", List.of(), false)));
    }

    @Test
    public void modIdsMakeItAForgeMod() {
        assertEquals(ModSourceIndex.Kind.FORGE_MOD, ModSourceIndex.classify(mod("FML", List.of("sample"), false)));
    }

    @Test
    public void modWithoutModTypeOrManifestIsStillAForgeMod() {
        // neither attribute exists in the wild for most mods; the mod id from mcmod.info or @Mod is what counts
        assertEquals(ModSourceIndex.Kind.FORGE_MOD, ModSourceIndex.classify(mod("FML", List.of("legacy"), false)));
    }

    @Test
    public void cleanroomModTypeWinsAmongMods() {
        assertEquals(ModSourceIndex.Kind.LOADER_MOD, ModSourceIndex.classify(mod("CRL", List.of("cleanroommod"), false)));
        assertEquals(ModSourceIndex.Kind.LOADER_MOD, ModSourceIndex.classify(mod("CRL", List.of(), true)));
    }

    @Test
    public void mixinOrCoremodMarkingMakesItAMod() {
        assertEquals(ModSourceIndex.Kind.FORGE_MOD, ModSourceIndex.classify(mod("FML", List.of(), true)));
    }

    @Test
    public void onlyCleanroomModsAreLeftAlone() {
        // the filter may only skip what is known to be written against the LWJGL3 API; everything else, including
        // sources that are not mods at all, keeps its rewrites (Minecraft's own OpenAL binding needs them)
        assertEquals(ModSourceIndex.Kind.NON_MOD, ModSourceIndex.classify(mod("FML", List.of(), false)));
        assertEquals(ModSourceIndex.Kind.FORGE_MOD, ModSourceIndex.classify(mod("FML", List.of("renderlib"), false)));
        assertEquals(ModSourceIndex.Kind.LOADER_MOD, ModSourceIndex.classify(mod("CRL", List.of("codechickenlib"), false)));
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

    private static DiscoveredMod mod(String modType, List<String> modIds, boolean mixinAttributes) {
        return new DiscoveredMod(SOURCE, null, modIds, modType, mixinAttributes, false, false, null, null);
    }
}
