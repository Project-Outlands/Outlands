package net.minecraftforge.fml.common.discovery.index;

import java.io.File;
import java.time.Instant;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import net.minecraftforge.fml.common.FMLLog;
import net.minecraftforge.fml.common.discovery.DetailScanner;
import net.minecraftforge.fml.common.discovery.cache.DetailFile;
import net.minecraftforge.fml.common.discovery.cache.DetailRecord;
import net.minecraftforge.fml.common.discovery.cache.JarFingerprint;

/**
 * The one implementation of {@link IndexExport}: rebuilds the sections a caller asked for on a thread of its own and
 * republishes the manifest.
 *
 * <p>Only detail is produced here. Discovery already writes the class records and the member tables, so asking for
 * those would be asking for work that has happened; the registry and the name table are not implemented yet and are
 * reported as such rather than silently skipped.
 */
final class IndexDumper implements IndexExport {

    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "Outlands index export");
        thread.setDaemon(true);
        return thread;
    });

    private final LoadIndex index;
    private CompletableFuture<Report> running;

    IndexDumper(LoadIndex index) {
        this.index = index;
    }

    @Override
    public CompletableFuture<Report> dump(EnumSet<Scope> scopes) {
        synchronized (this) {
            if (running != null && !running.isDone()) {
                return running;
            }
            final EnumSet<Scope> requested = scopes.clone();
            running = CompletableFuture.supplyAsync(() -> run(requested), WRITER);
            return running;
        }
    }

    private Report run(EnumSet<Scope> scopes) {
        final Map<Scope, Integer> counts = new EnumMap<>(Scope.class);
        final StringBuilder note = new StringBuilder();
        int files = 0;
        long bytes = 0;
        try {
            if (scopes.contains(Scope.DETAIL)) {
                final long[] written = dumpDetail();
                counts.put(Scope.DETAIL, (int) written[0]);
                files += (int) written[0];
                bytes += written[1];
            }
            if (scopes.contains(Scope.REGISTRY)) {
                note.append(note.length() == 0 ? "" : "; ").append("REGISTRY is not implemented in this build");
            }
            if (scopes.contains(Scope.NAMES)) {
                note.append(note.length() == 0 ? "" : "; ").append("NAMES is not implemented in this build");
            }
            index.writeManifest("dump");
            return new Report(Instant.now(), index.order().size(), files, bytes, counts,
                    note.length() == 0 ? null : note.toString());
        } catch (Throwable t) {
            FMLLog.log.warn("Could not write the index export", t);
            return new Report(Instant.now(), index.order().size(), files, bytes, counts, String.valueOf(t));
        }
    }

    /** @return how many files were written and their total size */
    private long[] dumpDetail() {
        int written = 0;
        long bytes = 0;
        for (File archive : index.order()) {
            if (archive.isDirectory()) {
                continue;
            }
            final File file = index.detailFileFor(archive);
            final JarFingerprint fingerprint = index.fingerprintFor(archive);
            if (file == null || fingerprint == null) {
                continue;
            }
            if (DetailFile.read(file, fingerprint) != null) {
                continue; // already there and taken from this jar
            }
            try {
                final DetailRecord record = DetailScanner.scan(archive);
                if (record.isEmpty()) {
                    continue;
                }
                DetailFile.write(file, record);
                if (file.isFile()) {
                    written++;
                    bytes += file.length();
                }
            } catch (Exception e) {
                FMLLog.log.debug("Could not read the detail of {}", archive, e);
            }
        }
        return new long[] {written, bytes};
    }
}
