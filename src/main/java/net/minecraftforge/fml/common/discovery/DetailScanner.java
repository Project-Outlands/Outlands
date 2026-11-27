package net.minecraftforge.fml.common.discovery;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.zip.ZipEntry;

import javax.annotation.Nullable;

import net.minecraftforge.fml.common.MetadataCollection;
import net.minecraftforge.fml.common.asm.transformers.AtRule;
import net.minecraftforge.fml.common.discovery.asm.ASMModParser;
import net.minecraftforge.fml.common.discovery.cache.ClassDetail;
import net.minecraftforge.fml.common.discovery.cache.DetailRecord;
import net.minecraftforge.fml.common.discovery.cache.JarFingerprint;
import net.minecraftforge.fml.common.discovery.index.BorrowedArchive;

/**
 * Reads an archive again for its detail alone, when a reader asked for it and no file exists.
 *
 * <p>This is the on-demand path of {@link net.minecraftforge.fml.common.discovery.cache.DetailFile}: the same entries,
 * the same flags and the same {@link ASMModParser} as discovery, so a rebuilt file says exactly what the discovery-time
 * one would have said. It has no side effects: it does not build containers and does not touch the annotation table.
 */
public final class DetailScanner {

    private DetailScanner() {}

    /** @return the detail of {@code archive}, read from its class files and its access transformer entries */
    public static DetailRecord scan(File archive) throws IOException {
        try (BorrowedArchive borrow = BorrowedArchive.of(archive, false)) {
            final java.util.jar.JarFile jar = borrow.jar();
            final List<JarEntry> entries = new ArrayList<>();
            for (ZipEntry listed : Collections.list(jar.entries())) {
                if (ITypeDiscoverer.shouldScan(listed.getName())) {
                    entries.add((JarEntry) listed);
                }
            }
            final List<ParallelJarParse.Parsed> parsed = ParallelJarParse.parse(entries, jar);
            final Map<String, ClassDetail> details = new HashMap<>();
            for (ParallelJarParse.Parsed result : parsed) {
                final ASMModParser parser = result.parser();
                if (parser == null) {
                    continue;
                }
                final ClassDetail detail = parser.toDetail();
                if (!detail.isEmpty()) {
                    details.put(parser.internalName(), detail);
                }
            }
            final MetadataCollection metadata = JarMetadata.of(jar).metadata();
            final AtRule[] rules = JarAtRules.of(jar, metadata);
            return new DetailRecord(JarFingerprint.compute(jar.entries()), details, rules);
        }
    }

    /** @return the archive's fingerprint, or null when it cannot be read */
    @Nullable
    public static JarFingerprint fingerprintOf(File archive) {
        try (BorrowedArchive borrow = BorrowedArchive.of(archive, false)) {
            return JarFingerprint.compute(borrow.jar().entries());
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
