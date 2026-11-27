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
import net.minecraftforge.fml.common.discovery.cache.ClassDetail;
import net.minecraftforge.fml.common.discovery.cache.ClassScanRecord;
import net.minecraftforge.fml.common.discovery.cache.DetailRecord;
import net.minecraftforge.fml.common.discovery.cache.MemberDetail;
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

    /**
     * The three things one pass produces: the class index, the member table it is queried with, and the detail a
     * language tool asks for, all from the same visit and all named the way the loader defines them.
     */
    record Scanned(JarScanRecord index, Map<String, MemberRecord[]> members, DetailRecord detail) {}

    /**
     * @return the index of {@code archive} with every name mapped the way the loader maps it, and its member table
     */
    static Scanned scan(File archive, ClassNaming naming) throws IOException {
        final List<ZipEntry> entries = new ArrayList<>();
        final List<Integer> indices = new ArrayList<>();
        final List<ClassScanRecord> records = new ArrayList<>();
        final Map<String, MemberRecord[]> members = new HashMap<>();
        final Map<String, ClassDetail> details = new HashMap<>();
        net.minecraftforge.fml.common.asm.transformers.AtRule[] atRules = new net.minecraftforge.fml.common.asm.transformers.AtRule[0];
        try (BorrowedArchive borrow = BorrowedArchive.of(archive, false)) {
            final java.util.jar.JarFile zip = borrow.jar();
            atRules = net.minecraftforge.fml.common.discovery.JarAtRules.of(zip, null);
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
                if (header.detail != null && !header.detail.isEmpty()) {
                    details.put(header.record.internalName(), header.detail);
                }
            }
        }
        final JarScanRecord index = JarScanRecord.of(JarFingerprint.compute(entries), indices, records);
        return new Scanned(index, members, new DetailRecord(index.fingerprint(), details, atRules));
    }

    private static Header readHeader(byte[] bytes, ClassNaming naming) throws IOException {
        final Header header = new Header(naming);
        // the bytes came out of the archive at the size its central directory records, so ASM does not have to buffer
        // them into a growing stream of its own first
        // SourceFile is a debug attribute and is the one thing a reader needs to name a file; method bodies stay
        // skipped, which is where the line and variable tables live
        new ClassReader(bytes).accept(header, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES);
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
        private final Map<String, MemberDetail> memberDetails = new HashMap<>();
        private ClassScanRecord record;
        private ClassDetail detail;
        private String signature;
        private ClassDetail.Inner inner;
        private ClassDetail.Enclosing enclosing;
        private String sourceFile;

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
            this.signature = signature;
        }

        @Override
        public void visitSource(String source, String debug) {
            this.sourceFile = source;
        }

        @Override
        public void visitOuterClass(String owner, String name, String descriptor) {
            this.enclosing = new ClassDetail.Enclosing(naming.type(owner),
                    name == null ? null : naming.member(owner, name, descriptor, true),
                    descriptor == null ? null : naming.descriptor(descriptor));
        }

        @Override
        public void visitInnerClass(String innerName, String outerName, String simpleName, int innerAccess) {
            if (this.name != null && innerName.equals(this.name)) {
                this.inner = new ClassDetail.Inner(outerName, simpleName, innerAccess);
            }
        }

        @Override
        public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
            final String mappedName = naming.member(owner, name, descriptor, false);
            final String mappedDescriptor = naming.descriptor(descriptor);
            members.add(new MemberRecord(mappedName, mappedDescriptor, access, false));
            final MemberDetail detail = MemberDetail.of(
                    signature == null ? null : naming.signature(signature, true), null, value);
            if (detail != null) {
                memberDetails.put(MemberRecord.key(mappedName, mappedDescriptor, false), detail);
            }
            return null;
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                String[] exceptions) {
            final String mappedName = naming.member(owner, name, descriptor, true);
            final String mappedDescriptor = naming.descriptor(descriptor);
            members.add(new MemberRecord(mappedName, mappedDescriptor, access, true));
            String[] mappedExceptions = null;
            if (exceptions != null && exceptions.length > 0) {
                mappedExceptions = new String[exceptions.length];
                for (int i = 0; i < exceptions.length; i++) {
                    mappedExceptions[i] = naming.type(exceptions[i]);
                }
            }
            final MemberDetail detail = MemberDetail.of(
                    signature == null ? null : naming.signature(signature, false), mappedExceptions, null);
            if (detail != null) {
                memberDetails.put(MemberRecord.key(mappedName, mappedDescriptor, true), detail);
            }
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
            final ClassDetail.Inner mappedInner = inner == null ? null
                    : new ClassDetail.Inner(inner.outer() == null ? null : naming.type(inner.outer()),
                            inner.innerName(), inner.access());
            this.detail = new ClassDetail(
                    signature == null ? null : naming.signature(signature, true),
                    mappedInner, enclosing, sourceFile,
                    memberDetails.isEmpty() ? Map.of() : Map.copyOf(memberDetails));
        }

        private MemberRecord[] members() {
            return members.toArray(new MemberRecord[0]);
        }
    }
}
