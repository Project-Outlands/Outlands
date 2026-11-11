package net.minecraftforge.common.config;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Reads and replaces one config file.
 *
 * <p>Every write goes through {@link #writeAtomic}: the text is written next to the target under a temporary name and
 * then moved into place. A crash halfway through then leaves the previous file intact instead of a truncated one, which
 * matters because this file is the only copy of a player's settings.
 */
public final class ConfigFile {

    private ConfigFile() {}

    /** @return the file's text, or {@code null} when it does not exist */
    public static String read(File file) throws IOException {
        if (!file.isFile())
            return null;
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    /**
     * Replaces {@code file} with {@code text} in one step.
     *
     * <p>The temporary file is created in the same directory so the final move stays on one file system, which is what
     * makes the atomic variant available in the first place.
     */
    public static void writeAtomic(File file, String text) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory())
            throw new IOException("Could not create the directory " + parent);

        Path target = file.toPath();
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (hasContents(target, bytes))
            return;
        Path temporary = Files.createTempFile(target.toAbsolutePath().getParent(), ".c-" + file.getName(), ".tmp");
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // Some file systems (network shares, mostly) cannot do it. Losing the atomicity is still better than
                // losing the file.
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static boolean hasContents(Path target, byte[] bytes) {
        try {
            if (Files.size(target) != bytes.length)
                return false;
            try (InputStream input = Files.newInputStream(target)) {
                byte[] buffer = new byte[8192];
                int offset = 0;
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (count > bytes.length - offset)
                        return false;
                    for (int index = 0; index < count; index++)
                        if (buffer[index] != bytes[offset + index])
                            return false;
                    offset += count;
                }
                return offset == bytes.length;
            }
        } catch (IOException unavailable) {
            // Missing/unreadable files retain the original atomic write and its diagnostics.
            return false;
        }
    }
}
