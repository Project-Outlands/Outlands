package net.minecraftforge.fml.common.discovery.index;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.minecraftforge.fml.common.FMLLog;

/**
 * The SRG to MCP half of a member's name: the table a script consults when it wants to say {@code getWorldInfo} and
 * the classes it runs against are named {@code func_72959_q}.
 *
 * <p>Only one direction needs a table. Measured over a 1.12.2 mapping set: 21,622 distinct SRG member names map to
 * exactly one MCP name each, while 2,145 MCP names cover more than one SRG name. So the stored form is a single flat
 * map, and the reverse question is answered by walking the owner's members and asking this table about each of them —
 * which is also what keeps the answer owner-scoped, the thing a name-only reverse lookup gets wrong.
 *
 * <p>The data is MCP's and cannot be redistributed, so nothing here is bundled: the table comes from a file the user
 * placed, from the development run's own mapping file, or from a fetch that is off unless asked for. Without it
 * {@link State#ABSENT} says so, every lookup is empty, and callers fall back to the SRG names they already have.
 */
public final class Mappings {

    public enum State {
        /** No table was found; names stay as the loader defines them. */
        ABSENT,
        /** A table is being read on a background thread; {@link #awaitReady} waits for it. */
        PENDING,
        /** The table is in memory. */
        READY
    }

    /** Where a user can drop a mapping file; the first readable one wins. */
    public static final String CONFIG_DIR = "config/outlands/mappings";

    private static final Mappings INSTANCE = new Mappings();

    public static Mappings get() {
        return INSTANCE;
    }

    private volatile State state = State.ABSENT;
    private volatile Map<String, String> srgToMcp = Map.of();
    private volatile String source = "";
    private volatile boolean started;
    private volatile boolean warnedMissing;

    private Mappings() {
    }

    /** @return a table that is not the process one, so a test can load a file the process never reads */
    static Mappings fresh() {
        return new Mappings();
    }

    /**
     * Starts the one load this process does. Calling it again is free, and so is calling it when there is no table.
     *
     * @param gameDir where {@value #CONFIG_DIR} is, or null when there is no game directory yet
     */
    public synchronized void start(File gameDir) {
        if (started) {
            return;
        }
        started = true;
        final List<Path> found = candidates(gameDir);
        if (found.isEmpty()) {
            state = State.ABSENT;
            FMLLog.log.debug("No MCP name table found; scripts that ask for MCP names will be told so once");
            return;
        }
        state = State.PENDING;
        final Thread loader = new Thread(() -> load(found), "Outlands mapping table");
        loader.setDaemon(true);
        loader.start();
    }

    /** @return whether the table is in memory now; false when this process has no table or the wait ran out */
    public boolean awaitReady(long timeoutMs) {
        final long deadline = System.currentTimeMillis() + timeoutMs;
        while (state == State.PENDING && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(2L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return state == State.READY;
            }
        }
        return state == State.READY;
    }

    public State state() {
        return state;
    }

    /** @return whether {@link #start(File)} has run, so a caller does not restart a load that already concluded */
    public boolean started() {
        return started;
    }

    /** @return the file the table was read from, for a diagnostic that can tell the user what to place */
    public String source() {
        return source;
    }

    /** @return how many names the table renames; zero when there is no table */
    public int size() {
        return srgToMcp.size();
    }

    /**
     * @param srgName a name the loader defines, such as {@code func_72959_q}
     * @return the MCP name for it, or empty when MCP does not name it or there is no table
     */
    public Optional<String> mcpNameOf(String srgName) {
        return Optional.ofNullable(srgToMcp.get(srgName));
    }

    /**
     * Records, once, that a name could not be resolved because there is no table. The alternative is a warning per
     * lookup, which on a script that resolves thousands of names is a log nobody can read.
     */
    void warnMissingOnce() {
        if (!warnedMissing) {
            warnedMissing = true;
            FMLLog.log.warn("A script asked for MCP member names, but no name table is available; "
                    + "place an mcp_stable-*.zip or a *.tsrg in {}", CONFIG_DIR);
        }
    }

    private void load(List<Path> candidates) {
        for (Path candidate : candidates) {
            try {
                final Map<String, String> table = read(candidate);
                if (!table.isEmpty()) {
                    srgToMcp = table;
                    source = candidate.toString();
                    state = State.READY;
                    FMLLog.log.debug("Read {} MCP name mappings from {}", table.size(), candidate);
                    return;
                }
            } catch (IOException | RuntimeException e) {
                FMLLog.log.warn("Could not read the MCP name table {}", candidate, e);
            }
        }
        state = State.ABSENT;
        FMLLog.log.info("None of the mapping files that were found could be read; MCP names stay unavailable");
    }

    /** @return the files to try, most specific first; the caller reads the first one that yields a table */
    private static List<Path> candidates(File gameDir) {
        final List<Path> found = new ArrayList<>();
        final String explicit = System.getProperty("outlands.mappings.file");
        if (explicit != null && !explicit.isBlank()) {
            found.add(Path.of(explicit));
        }
        // a development run already has the SRG to MCP file the remapper loads, so read the same one
        final String dev = System.getProperty("net.minecraftforge.gradle.GradleStart.srg.srg-mcp");
        if (dev != null && !dev.isBlank()) {
            found.add(Path.of(dev));
        }
        if (gameDir != null) {
            final Path dir = gameDir.toPath().resolve(CONFIG_DIR);
            if (Files.isDirectory(dir)) {
                try (var listing = Files.list(dir)) {
                    // one file wins: a user who drops two would otherwise get whichever the file system answered first
                    listing.filter(Files::isRegularFile)
                            .filter(path -> names(path.getFileName().toString()))
                            .sorted(Comparator.comparing(Path::toString))
                            .forEach(found::add);
                } catch (IOException e) {
                    FMLLog.log.debug("Could not list {}", dir, e);
                }
            }
        }
        return found;
    }

    private static boolean names(String file) {
        final String lower = file.toLowerCase();
        return lower.endsWith(".tsrg") || lower.endsWith(".zip") || lower.endsWith(".csv");
    }

    /**
     * @return the SRG name to MCP name pairs in {@code file}, which is a TSRG file, a mapping zip, or one CSV
     */
    static Map<String, String> read(Path file) throws IOException {
        final String lower = file.getFileName().toString().toLowerCase();
        if (lower.endsWith(".zip")) {
            final Map<String, String> table = new HashMap<>();
            try (ZipFile zip = new ZipFile(file.toFile())) {
                final List<? extends ZipEntry> entries = new ArrayList<>(java.util.Collections.list(zip.entries()));
                entries.sort(Comparator.comparing(ZipEntry::getName));
                for (ZipEntry entry : entries) {
                    final String name = entry.getName().toLowerCase();
                    if (name.endsWith("methods.csv") || name.endsWith("fields.csv") || name.endsWith(".tsrg")) {
                        try (InputStream in = zip.getInputStream(entry)) {
                            readInto(in, name, table);
                        }
                    }
                }
            }
            return Map.copyOf(table);
        }
        final Map<String, String> table = new HashMap<>();
        try (InputStream in = Files.newInputStream(file)) {
            readInto(in, lower, table);
        }
        return Map.copyOf(table);
    }

    private static void readInto(InputStream in, String name, Map<String, String> table) throws IOException {
        final boolean csv = name.endsWith("methods.csv") || name.endsWith("fields.csv");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.charAt(0) == '#') {
                    continue;
                }
                if (csv) {
                    readCsvLine(line, table);
                } else {
                    readTsrgLine(line, table);
                }
            }
        }
    }

    /** {@code field_100013_f,isTrapdoor,0,} — the name MCP gives this SRG name, and the rest of the row ignored. */
    private static void readCsvLine(String line, Map<String, String> table) {
        final int first = line.indexOf(',');
        if (first <= 0) {
            return;
        }
        final int second = line.indexOf(',', first + 1);
        final String srgName = line.substring(0, first).trim();
        final String mcpName = (second < 0 ? line.substring(first + 1) : line.substring(first + 1, second)).trim();
        put(table, srgName, mcpName);
    }

    /**
     * {@code \tname desc newName} for a method and {@code \tname newName} for a field; a class line names a type and
     * is not a member, so it is skipped. In a 1.12.2 SRG set every class line is an identity pair, and a member whose
     * two names are equal is already MCP named.
     */
    private static void readTsrgLine(String line, Map<String, String> table) {
        if (line.isEmpty() || line.charAt(0) != '\t') {
            return;
        }
        final String[] parts = line.substring(1).trim().split("\\s+");
        if (parts.length == 2) {
            put(table, parts[0], parts[1]);
        } else if (parts.length == 3) {
            put(table, parts[0], parts[2]);
        }
    }

    private static void put(Map<String, String> table, String srgName, String mcpName) {
        // a name that does not change is not worth an entry: the lookup falls back to the name it was given
        if (!srgName.isEmpty() && !mcpName.isEmpty() && !srgName.equals(mcpName)) {
            table.put(srgName, mcpName);
        }
    }
}
