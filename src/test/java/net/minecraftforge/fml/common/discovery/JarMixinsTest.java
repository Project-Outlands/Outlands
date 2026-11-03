package net.minecraftforge.fml.common.discovery;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import net.minecraftforge.fml.common.LoaderException;
import net.minecraftforge.fml.common.MetadataCollection;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@code jar.mixins}: the key that replaces the manifest's {@code MixinConfigs}.
 *
 * <p>The manifest attribute could list config names and nothing else. One object per config costs a few characters and
 * buys the fact the attribute had no room for - which side the configuration exists on.
 */
class JarMixinsTest {

    private static JarDeclaration parse(String jarSection) {
        String json = "{\"jar\":{\"schemaVersion\":1,\"license\":\"MIT\"" + jarSection + "}}";
        return MetadataCollection.from(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), "test.jar")
                .getJarDeclaration();
    }

    private static List<JarDeclaration.MixinConfig> mixins(String body) {
        return parse(",\"mixins\":" + body).mixins();
    }

    private static void rejected(String mixinsBody) {
        assertThrows(LoaderException.class, () -> mixins(mixinsBody), "expected this to be refused: " + mixinsBody);
    }

    @Test
    void noKeyMeansNoConfigs() {
        assertTrue(parse("").mixins().isEmpty());
        assertTrue(parse(",\"mixins\":[]").mixins().isEmpty());
    }

    @Test
    void oneObjectPerConfig() {
        List<JarDeclaration.MixinConfig> mixins = mixins("["
                + "{\"config\":\"mymod.mixins.json\"},"
                + "{\"config\":\"mymod.client.mixins.json\",\"side\":\"client\"}"
                + "]");

        assertEquals(2, mixins.size());
        assertEquals("mymod.mixins.json", mixins.get(0).config());
        assertSame(JarDeclaration.Side.BOTH, mixins.get(0).side(), "an entry with no side is for both sides");
        assertEquals("client", mixins.get(1).side().name().toLowerCase(java.util.Locale.ENGLISH));
        assertFalse(mixins.get(1).excludes("CLIENT"));
        assertTrue(mixins.get(1).excludes("SERVER"));
    }

    @Test
    void theSidesAreTheJarSectionsSides() {
        // Same vocabulary as jar.side, because they answer the same question about the same jar.
        assertSame(JarDeclaration.Side.CLIENT, mixins("[{\"config\":\"a.json\",\"side\":\"CLIENT\"}]").get(0).side());
        assertSame(JarDeclaration.Side.SERVER, mixins("[{\"config\":\"a.json\",\"side\":\"server\"}]").get(0).side());
        assertSame(JarDeclaration.Side.BOTH, mixins("[{\"config\":\"a.json\",\"side\":\"any\"}]").get(0).side());
    }

    @Test
    void aConfigNameThatCanNeverBeReadIsRefused() {
        // Mixin registers only names ending in .json and ignores everything else without a word.
        rejected("[{\"config\":\"mymod.mixins\"}]");
        rejected("[{\"config\":\"\"}]");
        rejected("[{\"side\":\"client\"}]");
    }

    @Test
    void aConfigIsDeclaredOnce() {
        rejected("[{\"config\":\"a.json\"},{\"config\":\"a.json\",\"side\":\"client\"}]");
    }

    @Test
    void theWrongShapesAreRefused() {
        rejected("\"a.json\"");
        rejected("[\"a.json\"]");
        rejected("[{\"config\":123}]");
        rejected("[{\"config\":\"a.json\",\"side\":\"server-and-client\"}]");
    }

    @Test
    void aConfigForTheOtherSideThanTheJarIsAcceptedWithAWarning() {
        // Contradictory but parseable: the jar is client-only, the config claims the server. Refusing would stop a pack
        // over a configuration that will never be registered anyway, so it is reported and kept.
        List<JarDeclaration.MixinConfig> mixins = parse(",\"side\":\"client\",\"mixins\":[{\"config\":\"a.json\",\"side\":\"server\"}]").mixins();
        assertSame(JarDeclaration.Side.SERVER, mixins.get(0).side());
    }

    @Test
    void aMissingConfigFileIsRefusedWhenTheJarIsOpen() {
        parse(",\"mixins\":[{\"config\":\"present.json\"}]").verifyMixinConfigs("test.jar", "present.json"::equals);

        JarDeclaration declaration = parse(",\"mixins\":[{\"config\":\"present.json\"},{\"config\":\"missing.json\"}]");
        LoaderException thrown = assertThrows(LoaderException.class,
                () -> declaration.verifyMixinConfigs("test.jar", "present.json"::equals));
        assertTrue(thrown.getMessage().contains("missing.json"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("test.jar"), thrown.getMessage());
    }

    @Test
    void theKeyIsNotReportedAsUnknown() {
        assertTrue(parse(",\"mixins\":[{\"config\":\"a.json\"}]").unknownKeys().isEmpty());
        assertFalse(JarDeclaration.knownKeys().isEmpty());
        assertTrue(JarDeclaration.knownKeys().contains("mixins"));
        assertEquals(Set.of("mixins"), Set.of("mixins"));
    }

    @Test
    void theDebugLineSaysHowMany() {
        assertEquals("JarDeclaration[schema=1, license=MIT, mixins=2]",
                parse(",\"mixins\":[{\"config\":\"a.json\"},{\"config\":\"b.json\"}]").toString());
    }
}
