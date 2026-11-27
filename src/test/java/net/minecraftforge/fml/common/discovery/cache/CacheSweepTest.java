package net.minecraftforge.fml.common.discovery.cache;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sweeping drops what belongs to a jar that is gone, and leaves alone what is keyed by something other than a path
 * below the game directory: the vanilla index is named after the jar's fingerprint.
 */
class CacheSweepTest {

    private static File touch(File file) throws Exception {
        //noinspection ResultOfMethodCallIgnored
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), new byte[] {1, 2, 3});
        return file;
    }

    private static File cacheRoot(File gameDir) {
        return RecordFile.cacheDir(gameDir);
    }

    @Test
    void theVanillaIndexSurvivesAndItsOrphansDoNot(@TempDir Path dir) throws Exception {
        final File gameDir = dir.toFile();
        final File vanilla = new File(cacheRoot(gameDir), "vanilla");
        final File complete = touch(new File(vanilla, "v2-1.12.2-abc-1193.bin"));
        final File completeMembers = touch(new File(vanilla, "v2-1.12.2-abc-1193.bin.members"));
        final File completeDetail = touch(new File(vanilla, "v2-1.12.2-abc-1193.bin.detail"));
        final File orphanMembers = touch(new File(vanilla, "v2-1.12.2-old-1193.bin.members"));
        final File orphanDetail = touch(new File(vanilla, "v2-1.12.2-old-1193.bin.detail"));

        RecordFile.sweep(gameDir);

        assertTrue(complete.isFile(), "the vanilla index is keyed by fingerprint, not by a path under the game dir");
        assertTrue(completeMembers.isFile());
        assertTrue(completeDetail.isFile());
        assertFalse(orphanMembers.isFile(), "a member table whose index is gone answers nothing");
        assertFalse(orphanDetail.isFile());
    }

    @Test
    void aRecordOfAMissingJarTakesItsSideFilesWithIt(@TempDir Path dir) throws Exception {
        final File gameDir = dir.toFile();
        final File record = touch(new File(new File(cacheRoot(gameDir), "mods"), "gone.jar.bin"));
        final File members = touch(new File(record.getPath() + MemberFile.SUFFIX));
        final File detail = touch(new File(record.getPath() + DetailFile.SUFFIX));

        RecordFile.sweep(gameDir);

        assertFalse(record.isFile());
        assertFalse(members.isFile());
        assertFalse(detail.isFile());
    }
}
