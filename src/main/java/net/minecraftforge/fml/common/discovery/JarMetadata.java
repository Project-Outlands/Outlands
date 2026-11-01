package net.minecraftforge.fml.common.discovery;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import javax.annotation.Nullable;

import net.minecraftforge.fml.common.FMLLog;
import net.minecraftforge.fml.common.LoaderException;
import net.minecraftforge.fml.common.MetadataCollection;

/**
 * One jar's {@code mcmod.info}, read once per launch.
 *
 * <p>Two passes need it and they do not need the same amount of it: assembling the classpath only asks whether the
 * jar declares a {@code jar} section, discovery asks for the mod list as well. The entry is read once either way, so
 * both answers come from the same bytes; the parse stays lazy, because a jar that declares no section never needs one.
 *
 * <p>Cached per file path for the rest of the process. Nothing invalidates it, which is correct rather than a
 * shortcoming: a jar cannot change while the game it belongs to is starting.
 */
public final class JarMetadata {

    private static final Map<File, JarMetadata> BY_FILE = new ConcurrentHashMap<>();

    private final String fileName;
    /** The entry's bytes, or {@code null} when the jar has no {@code mcmod.info}. */
    @Nullable
    private final byte[] raw;

    private boolean metadataParsed;
    @Nullable
    private MetadataCollection metadata;

    private boolean declarationParsed;
    @Nullable
    private JarDeclaration declaration;

    private JarMetadata(String fileName, @Nullable byte[] raw) {
        this.fileName = fileName;
        this.raw = raw;
    }

    /** @return the metadata of {@code jar}, reading the entry on first use */
    public static JarMetadata of(JarFile jar) {
        File absolute = new File(jar.getName()).getAbsoluteFile();
        JarMetadata known = BY_FILE.get(absolute);
        if (known != null) {
            return known;
        }
        byte[] raw;
        try {
            raw = readEntry(jar);
        } catch (IOException e) {
            FMLLog.log.debug("Could not read the mcmod.info of {}", absolute.getName(), e);
            raw = null;
        }
        final byte[] bytes = raw;
        return BY_FILE.computeIfAbsent(absolute, key -> new JarMetadata(key.getName(), bytes));
    }

    /** @return the metadata of {@code file}, opening the jar on first use */
    public static JarMetadata of(File file) {
        File absolute = file.getAbsoluteFile();
        JarMetadata known = BY_FILE.get(absolute);
        if (known != null) {
            return known;
        }
        return BY_FILE.computeIfAbsent(absolute, key -> {
            try (JarFile jar = new JarFile(key)) {
                return new JarMetadata(key.getName(), readEntry(jar));
            } catch (IOException e) {
                FMLLog.log.debug("Could not read the mcmod.info of {}", key.getName(), e);
                return new JarMetadata(key.getName(), null);
            }
        });
    }

    @Nullable
    private static byte[] readEntry(JarFile jar) throws IOException {
        JarEntry entry = jar.getJarEntry("mcmod.info");
        if (entry == null) {
            return null;
        }
        try (InputStream in = jar.getInputStream(entry)) {
            return in.readAllBytes();
        }
    }

    /** @return whether the jar carries a {@code mcmod.info} at all */
    public boolean isPresent() {
        return raw != null;
    }

    /**
     * @return the parsed metadata, or {@code null} when the jar declares none or it cannot be read
     * @throws LoaderException when the {@code jar} section is present but unusable: the jar claimed to follow the
     *                         current schema and then did not, so dropping what it declared silently is worse than
     *                         stopping
     */
    @Nullable
    public synchronized MetadataCollection metadata() {
        if (!metadataParsed) {
            metadata = raw == null ? null : parse();
            metadataParsed = true;
        }
        return metadata;
    }

    /**
     * @return the {@code jar} section, or {@code null} when the jar declares none
     *
     * <p>Searches the text for the section name first: most files in the mods directory predate the section, and for
     * those the answer is "none" without parsing anything. A false positive only costs the parse that would have
     * happened without the check.
     */
    @Nullable
    public synchronized JarDeclaration declaration() {
        if (!declarationParsed) {
            if (raw != null && new String(raw, StandardCharsets.UTF_8).indexOf("\"jar\"") >= 0) {
                MetadataCollection parsed = metadata();
                declaration = parsed == null ? null : parsed.getJarDeclaration();
            }
            declarationParsed = true;
        }
        return declaration;
    }

    @Nullable
    private MetadataCollection parse() {
        try {
            return MetadataCollection.from(new ByteArrayInputStream(raw), fileName);
        } catch (LoaderException e) {
            throw e;
        } catch (Throwable t) {
            FMLLog.log.error("Failed to parse mcmod.info for {}", fileName, t);
            return null;
        }
    }
}
