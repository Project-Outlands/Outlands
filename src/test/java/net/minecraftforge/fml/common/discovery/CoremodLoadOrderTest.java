package net.minecraftforge.fml.common.discovery;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

import net.minecraftforge.fml.common.MetadataCollection;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the ordering rule that decides which jar a coremod is read from first.
 *
 * <p>The rule is the whole feature, and it is also the only part that can be checked without building jars: what a jar
 * declares shifts it, and every jar that declares nothing keeps the position filename order gave it. Getting the
 * second half wrong would reorder coremod loading for every existing pack, which is not something a crash report would
 * make obvious - a transformer that no longer runs first just silently stops applying.
 */
class CoremodLoadOrderTest {

    private static DiscoveredMod mod(String path) {
        return new DiscoveredMod(new File(path), null, List.of(), "MOD", List.of(), false, false, false, false, null, null);
    }

    private static List<String> order(ToIntFunction<File> rankOf, DiscoveredMod... mods) {
        return CoremodLoadOrder.sort(List.of(mods), rankOf).stream()
                .map(mod -> mod.file().getName())
                .toList();
    }

    @Test
    void jarsDeclaringNothingKeepFilenameOrder() {
        // The property that makes this feature safe to ship: no declaration, no change.
        assertEquals(List.of("b.jar", "d.jar"), order(rank -> 0, mod("mods/d.jar"), mod("mods/b.jar")));
    }

    @Test
    void firstMovesAJarAheadOfEverythingUndeclared() {
        var order = order(
                rank -> rank.getName().equals("late.jar") ? -1 : 0,
                mod("mods/a.jar"), mod("mods/late.jar"));

        // Declared FIRST despite sorting last alphabetically: the point of the key is to stop needing "!a.jar".
        assertEquals(List.of("late.jar", "a.jar"), order);
    }

    @Test
    void lastMovesAJarBehindEverythingUndeclared() {
        var order = order(
                rank -> rank.getName().equals("a.jar") ? 1 : 0,
                mod("mods/a.jar"), mod("mods/z.jar"));

        assertEquals(List.of("z.jar", "a.jar"), order);
    }

    @Test
    void sameNameInDifferentDirectoriesStaysDeterministic() {
        // Two jars with one name: filename comparison cannot separate them, and the filesystem's own order is not
        // defined, so the path has to break the tie or the result varies between machines.
        var reversed = order(rank -> 0, mod("mods/z/core.jar"), mod("mods/a/core.jar"));
        var forward = order(rank -> 0, mod("mods/a/core.jar"), mod("mods/z/core.jar"));

        assertEquals(reversed, forward);
        assertEquals(2, reversed.size());
    }

    @Test
    void undeclaredJarsAroundADeclaredOneKeepTheirRelativeOrder() {
        var order = order(
                rank -> rank.getName().equals("m.jar") ? -1 : 0,
                mod("mods/b.jar"), mod("mods/m.jar"), mod("mods/a.jar"), mod("mods/c.jar"));

        assertEquals(List.of("m.jar", "a.jar", "b.jar", "c.jar"), order);
    }

    @Test
    void loadOrderIsParsedFromTheJarSection() {
        assertEquals(JarDeclaration.LoadOrder.FIRST, declaration("{\"loadOrder\":\"first\"}").loadOrder());
        assertEquals(JarDeclaration.LoadOrder.LAST, declaration("{\"loadOrder\":\"last\"}").loadOrder());
        assertEquals(JarDeclaration.LoadOrder.NORMAL, declaration("{\"loadOrder\":\"normal\"}").loadOrder());
        assertEquals(JarDeclaration.LoadOrder.NORMAL, declaration("").loadOrder());
        // Case and the default spelling are accepted; anything else is refused rather than silently treated as normal.
        assertEquals(JarDeclaration.LoadOrder.FIRST, declaration("{\"loadOrder\":\"FIRST\"}").loadOrder());
        assertThrows(net.minecraftforge.fml.common.LoaderException.class,
                () -> declaration("{\"loadOrder\":\"before-everything\"}"));
    }

    @Test
    void loadOrderIsNotTreatedAsAnUnknownKey() {
        assertTrue(declaration("{\"loadOrder\":\"first\"}").unknownKeys().isEmpty(),
                "a key the loader honours must not also be reported as unrecognised");
    }

    private static JarDeclaration declaration(String extra) {
        String jar = "{\"schemaVersion\":1,\"license\":\"MIT\""
                + (extra.isEmpty() ? "" : "," + extra.substring(1, extra.length() - 1)) + "}";
        return MetadataCollection.from(
                new java.io.ByteArrayInputStream(("{\"jar\":" + jar + "}").getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "test.jar").getJarDeclaration();
    }
}
