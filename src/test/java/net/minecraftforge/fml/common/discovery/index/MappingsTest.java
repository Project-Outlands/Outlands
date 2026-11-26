package net.minecraftforge.fml.common.discovery.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The table only ever renames; a name MCP leaves alone stays itself, and a file nobody placed is not an error.
 */
class MappingsTest {

    private static final String TSRG = """
            net/minecraft/world/World net/minecraft/world/World
            \tfield_72986_A isRemote
            \tfunc_72959_q ()Lnet/minecraft/world/storage/WorldInfo; getWorldInfo
            \tfunc_72907_a (I)V playSound
            \tgetName ()Ljava/lang/String; getName
            """;

    @Test
    void aTsrgFileGivesTheRenamedMembers(@TempDir Path dir) throws Exception {
        final Map<String, String> table = Mappings.read(file(dir, "srg2mcp.tsrg", TSRG));

        assertEquals("isRemote", table.get("field_72986_A"));
        assertEquals("getWorldInfo", table.get("func_72959_q"));
        assertEquals("playSound", table.get("func_72907_a"));
        assertFalse(table.containsKey("getName"), "a name that does not change is not an entry");
        assertEquals(3, table.size());
    }

    @Test
    void aCsvFileGivesTheRenamedMembers(@TempDir Path dir) throws Exception {
        final String csv = """
                func_72959_q,getWorldInfo,0,
                field_72986_A,isRemote,0,
                func_1,,0,unnamed stays unnamed
                """;

        final Map<String, String> table = Mappings.read(file(dir, "methods.csv", csv));

        assertEquals("getWorldInfo", table.get("func_72959_q"));
        assertEquals("isRemote", table.get("field_72986_A"));
        assertFalse(table.containsKey("func_1"), "a row with no name does not rename anything");
    }

    @Test
    void aZipIsReadForEveryTableInsideIt(@TempDir Path dir) throws Exception {
        final Path zip = dir.resolve("mcp_stable-39-1.12.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            entry(out, "methods.csv", "func_72959_q,getWorldInfo,0,\n");
            entry(out, "fields.csv", "field_72986_A,isRemote,0,\n");
            entry(out, "readme.txt", "not a table\n");
        }

        final Map<String, String> table = Mappings.read(zip);

        assertEquals("getWorldInfo", table.get("func_72959_q"));
        assertEquals("isRemote", table.get("field_72986_A"));
        assertEquals(2, table.size());
    }

    @Test
    void aFileInTheConfigDirectoryIsLoadedOnStart(@TempDir Path dir) throws Exception {
        final File gameDir = Files.createDirectories(dir.resolve("game")).toFile();
        final Path mappings = Files.createDirectories(gameDir.toPath().resolve(Mappings.CONFIG_DIR));
        Files.writeString(mappings.resolve("srg2mcp.tsrg"), TSRG, StandardCharsets.UTF_8);

        final Mappings table = Mappings.fresh();
        table.start(gameDir);

        assertTrue(table.awaitReady(5_000), "the table is read off the start path");
        assertEquals(Mappings.State.READY, table.state());
        assertEquals("getWorldInfo", table.mcpNameOf("func_72959_q").orElse(null));
        assertTrue(table.source().contains("srg2mcp.tsrg"));
    }

    @Test
    void aFetchedZipIsKeptWhereTheNextStartReadsIt(@TempDir Path dir) throws Exception {
        final File gameDir = Files.createDirectories(dir.resolve("game")).toFile();
        final Path zip = dir.resolve("mcp_stable-39-1.12.zip");
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
            entry(out, "methods.csv", "func_72959_q,getBiomeProvider,0,\n");
        }
        System.setProperty("outlands.mappings.fetch", zip.toUri().toString());
        try {
            final Mappings table = Mappings.fresh();
            table.start(gameDir);

            assertTrue(table.awaitReady(5_000), "the zip named by the property is fetched and read");
            assertEquals("getBiomeProvider", table.mcpNameOf("func_72959_q").orElse(null));
            assertTrue(Files.isRegularFile(gameDir.toPath().resolve(Mappings.CONFIG_DIR)
                    .resolve("mcp_stable-39-1.12.zip")), "and it stays there, so the next start reads it locally");
        } finally {
            System.clearProperty("outlands.mappings.fetch");
        }
    }

    @Test
    void withoutAFileTheTableIsAbsentAndQuiet(@TempDir Path dir) throws Exception {
        final File gameDir = Files.createDirectories(dir.resolve("game")).toFile();

        final Mappings table = Mappings.fresh();
        table.start(gameDir);

        assertEquals(Mappings.State.ABSENT, table.state());
        assertTrue(table.mcpNameOf("func_72959_q").isEmpty());
        assertEquals(0, table.size());
    }

    private static Path file(Path dir, String name, String content) throws IOException {
        return Files.writeString(dir.resolve(name), content, StandardCharsets.UTF_8);
    }

    private static void entry(ZipOutputStream out, String name, String content) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(content.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
    }
}
