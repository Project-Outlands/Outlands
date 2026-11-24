package net.minecraftforge.fml.common.discovery.index;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.annotation.Nullable;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Opcodes;

import net.minecraftforge.fml.common.discovery.ITypeDiscoverer;
import net.minecraftforge.fml.common.discovery.cache.ClassScanRecord;
import net.minecraftforge.fml.common.discovery.cache.JarFingerprint;
import net.minecraftforge.fml.common.discovery.cache.JarScanRecord;

/**
 * Builds the class index of an archive whose classes are named the way the class loader will not define them: the
 * minecraft jar ships obfuscated names and the loader renames them on the way in, so the index has to apply the same
 * mapping to be usable.
 *
 * <p>Only the class header is read, because that is all the index stores; a jar whose names the mapper leaves alone
 * needs no such pass, and {@link #needsMapping} answers that by looking at the first entries.
 */
final class VanillaIndex {

    private VanillaIndex() {}

    /**
     * The classes {@code FMLSanityChecker} already uses to recognise the minecraft jar; they are not obfuscated, so
     * they are present under this name in every shape the jar comes in.
     */
    private static final String[] MARKERS = {
            "net/minecraft/client/ClientBrandRetriever.class",
            "net/minecraft/server/MinecraftServer.class",
    };

    /** @return whether {@code archive} is the minecraft jar, wherever its classes come from */
    static boolean looksLikeMinecraftJar(File archive) {
        try (ZipFile zip = new ZipFile(archive)) {
            for (String marker : MARKERS) {
                if (zip.getEntry(marker) != null) {
                    return true;
                }
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
        return false;
    }

    /**
     * @return the index of {@code archive} with every name mapped the way the loader maps it
     */
    static JarScanRecord scan(File archive, UnaryOperator<String> map) throws IOException {
        final List<ZipEntry> entries = new ArrayList<>();
        final List<Integer> indices = new ArrayList<>();
        final List<ClassScanRecord> records = new ArrayList<>();
        try (ZipFile zip = new ZipFile(archive)) {
            int ordinal = -1;
            for (ZipEntry entry : Collections.list(zip.entries())) {
                entries.add(entry);
                if (!ITypeDiscoverer.shouldScan(entry.getName())) {
                    continue;
                }
                ordinal++;
                indices.add(ordinal);
                records.add(readHeader(zip.getInputStream(entry), map));
            }
        }
        return JarScanRecord.of(JarFingerprint.compute(entries), indices, records);
    }

    private static ClassScanRecord readHeader(java.io.InputStream stream, UnaryOperator<String> map) throws IOException {
        final Header header = new Header();
        new ClassReader(stream).accept(header, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        final String[] interfaces = new String[header.interfaces.length];
        for (int i = 0; i < interfaces.length; i++) {
            interfaces[i] = map.apply(header.interfaces[i]);
        }
        return new ClassScanRecord(
                map.apply(header.name),
                header.version,
                header.superName == null ? null : map.apply(header.superName),
                header.access,
                interfaces,
                new ClassScanRecord.Annotation[0]);
    }

    @Nullable
    private static String internalNameOf(String entryName) {
        if (!entryName.endsWith(".class")) {
            return null;
        }
        return entryName.substring(0, entryName.length() - ".class".length());
    }

    /** The four facts the index stores. */
    private static final class Header extends ClassVisitor {

        private String name;
        private String superName;
        private String[] interfaces = new String[0];
        private int access;
        private int version;

        private Header() {
            super(Opcodes.ASM9);
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            this.version = version;
            this.access = access;
            this.name = name;
            this.superName = superName;
            this.interfaces = interfaces == null ? new String[0] : interfaces;
        }
    }
}
