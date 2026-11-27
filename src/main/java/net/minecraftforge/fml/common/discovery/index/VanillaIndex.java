package net.minecraftforge.fml.common.discovery.index;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.annotation.Nullable;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import net.minecraftforge.fml.common.discovery.ITypeDiscoverer;
import net.minecraftforge.fml.common.discovery.cache.ClassScanRecord;
import net.minecraftforge.fml.common.discovery.cache.JarFingerprint;
import net.minecraftforge.fml.common.discovery.cache.JarScanRecord;
import net.minecraftforge.fml.common.discovery.cache.MemberRecord;

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
        try (BorrowedArchive borrow = BorrowedArchive.of(archive, false)) {
            for (String marker : MARKERS) {
                if (borrow.jar().getEntry(marker) != null) {
                    return true;
                }
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
        return false;
    }

    /** The two things one pass produces: the class index and the member table it is queried with. */
    record Scanned(JarScanRecord index, Map<String, MemberRecord[]> members) {}

    /**
     * @return the index of {@code archive} with every name mapped the way the loader maps it, and its member table
     */
    static Scanned scan(File archive, ClassNaming naming) throws IOException {
        final List<ZipEntry> entries = new ArrayList<>();
        final List<Integer> indices = new ArrayList<>();
        final List<ClassScanRecord> records = new ArrayList<>();
        final Map<String, MemberRecord[]> members = new HashMap<>();
        try (BorrowedArchive borrow = BorrowedArchive.of(archive, false)) {
            final java.util.jar.JarFile zip = borrow.jar();
            int ordinal = -1;
            for (ZipEntry listed : Collections.list(zip.entries())) {
                // JarFile hands out JarEntry, which is what the index reads through
                final java.util.jar.JarEntry entry = (java.util.jar.JarEntry) listed;
                entries.add(entry);
                if (!ITypeDiscoverer.shouldScan(entry.getName())) {
                    continue;
                }
                ordinal++;
                indices.add(ordinal);
                final Header header = readHeader(borrow.index().read(entry), naming);
                records.add(header.record);
                members.put(header.record.internalName(), header.members());
            }
        }
        return new Scanned(JarScanRecord.of(JarFingerprint.compute(entries), indices, records), members);
    }

    private static Header readHeader(byte[] bytes, ClassNaming naming) throws IOException {
        final Header header = new Header(naming);
        // the bytes came out of the archive at the size its central directory records, so ASM does not have to buffer
        // them into a growing stream of its own first
        new ClassReader(bytes).accept(header, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        header.finish();
        return header;
    }

    @Nullable
    private static String internalNameOf(String entryName) {
        if (!entryName.endsWith(".class")) {
            return null;
        }
        return entryName.substring(0, entryName.length() - ".class".length());
    }

    /** The facts the index stores, and the member table beside it, both read from one visit. */
    private static final class Header extends ClassVisitor {

        private final ClassNaming naming;
        private final List<MemberRecord> members = new ArrayList<>();
        private ClassScanRecord record;

        private String name;
        /** The name the class file declares, which is what a member mapping is looked up by. */
        private String owner;
        private String superName;
        private String[] interfaces = new String[0];
        private int access;
        private int version;

        private Header(ClassNaming naming) {
            super(Opcodes.ASM9);
            this.naming = naming;
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            this.version = version;
            this.access = access;
            this.name = name;
            this.owner = name;
            this.superName = superName;
            this.interfaces = interfaces == null ? new String[0] : interfaces;
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            members.add(new MemberRecord(naming.member(owner, name, descriptor, false),
                    naming.descriptor(descriptor), access, false));
            return null;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                String[] exceptions) {
            members.add(new MemberRecord(naming.member(owner, name, descriptor, true),
                    naming.descriptor(descriptor), access, true));
            return null;
        }

        /** Turns what was visited into the record; called once the class has been walked. */
        private void finish() {
            final String[] mapped = new String[interfaces.length];
            for (int i = 0; i < mapped.length; i++) {
                mapped[i] = naming.type(interfaces[i]);
            }
            this.record = new ClassScanRecord(naming.type(name), version,
                    superName == null ? null : naming.type(superName), access, mapped,
                    new ClassScanRecord.Annotation[0]);
        }

        private MemberRecord[] members() {
            return members.toArray(new MemberRecord[0]);
        }
    }
}
