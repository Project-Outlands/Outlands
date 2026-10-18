package com.cleanroommc.gradle.lwjgl2;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Verifies rewrite-table.tsv against the resolved LWJGL3 jars: table hash, generated file sync,
 * L3 jar hashes, target existence (including inheritance) and recipe consistency.
 */
public final class LwjglCompatCheck {

    private LwjglCompatCheck() {}

    public static void run(File tableTsv, File rewriteTableJava, List<File> l3Jars) {
        final List<String> errors = new ArrayList<>();
        try {
            final List<String> lines = Files.readAllLines(tableTsv.toPath(), StandardCharsets.UTF_8);
            final String expectedTableSha = headerValue(lines, "# table-sha256:");
            final Map<String, String> expectedJarShas = headerJarShas(lines);
            final StringBuilder body = new StringBuilder();
            final List<String[]> rows = new ArrayList<>();
            for (String line : lines) {
                if (line.startsWith("#") || line.trim().isEmpty()) {
                    continue;
                }
                body.append(line).append('\n');
                final String[] cols = line.split("\t", -1);
                if (cols.length != 8) {
                    errors.add("expected 8 columns, got " + cols.length + ": " + line);
                    continue;
                }
                rows.add(cols);
            }

            // table body hash
            final String actualTableSha = sha256(body.toString().getBytes(StandardCharsets.UTF_8));
            if (expectedTableSha == null) {
                errors.add("missing '# table-sha256:' header");
            } else if (!expectedTableSha.equals(actualTableSha)) {
                errors.add("table hash mismatch: header " + expectedTableSha + " != actual " + actualTableSha);
            }

            // generated file must carry the same hash
            if (!rewriteTableJava.isFile()) {
                errors.add("generated file not found: " + rewriteTableJava);
            } else {
                final String java = new String(Files.readAllBytes(rewriteTableJava.toPath()), StandardCharsets.UTF_8);
                final String marker = "TABLE_SHA256 = \"";
                final int at = java.indexOf(marker);
                if (at < 0) {
                    errors.add("TABLE_SHA256 not found in RewriteTable.java");
                } else {
                    final int end = java.indexOf('"', at + marker.length());
                    final String javaSha = java.substring(at + marker.length(), end);
                    if (!javaSha.equals(actualTableSha)) {
                        errors.add("RewriteTable.java is out of sync with rewrite-table.tsv (java=" + javaSha
                                + ", tsv=" + actualTableSha + "); rerun derive.py --emit-java");
                    }
                }
            }

            // L3 jars must not have changed, otherwise the table is stale
            final Map<String, String> actualJarShas = jarShas(l3Jars);
            for (Map.Entry<String, String> e : expectedJarShas.entrySet()) {
                final String actual = actualJarShas.get(e.getKey());
                if (actual == null) {
                    errors.add("header lists an LWJGL3 jar that was not provided: " + e.getKey());
                } else if (!actual.equals(e.getValue())) {
                    errors.add("LWJGL3 jar changed: " + e.getKey() + " (regenerate the table)");
                }
            }

            // every row must resolve in L3
            final Index index = Index.of(l3Jars);
            for (String[] row : rows) {
                validateRow(index, row, errors);
            }

            if (!errors.isEmpty()) {
                final StringBuilder sb = new StringBuilder("checkLwjglCompatTable failed:\n");
                for (String e : errors) {
                    sb.append("  - ").append(e).append('\n');
                }
                throw new IllegalStateException(sb.toString());
            }
            System.out.println("[lwjgl2compat] table OK: " + rows.size() + " rows, " + l3Jars.size() + " LWJGL3 jars");
        } catch (IOException e) {
            throw new IllegalStateException("cannot read table file: " + tableTsv, e);
        }
    }

    private static void validateRow(Index index, String[] row, List<String> errors) {
        final String kind = row[0];
        final String owner = row[1];
        final String name = row[2];
        final String desc = row[3];
        final String tOwner = row[4];
        final String tName = row[5];
        final String tDesc = row[6];
        final String recipe = row[7];

        switch (kind) {
            case "RENAME":
                if (!owner.equals(tOwner) || !desc.equals(tDesc)) {
                    errors.add("RENAME must keep owner and desc: " + join(row));
                }
                if (!index.hasMethod(tOwner, tName, tDesc)) {
                    errors.add("RENAME target missing: " + tOwner + '.' + tName + tDesc);
                }
                break;
            case "ALIAS":
                if (!desc.equals(tDesc)) {
                    errors.add("ALIAS must keep desc: " + join(row));
                }
                if (!index.hasMethod(tOwner, tName, tDesc)) {
                    errors.add("ALIAS target missing: " + tOwner + '.' + tName + tDesc);
                }
                break;
            case "CONVERT":
                if (!index.hasMethod(tOwner, tName, tDesc)) {
                    errors.add("CONVERT target missing: " + tOwner + '.' + tName + tDesc);
                }
                validateRecipe(index, desc, tDesc, recipe, row, errors);
                break;
            case "CTOR":
                if (!"<init>".equals(tName) || !desc.equals(tDesc)) {
                    errors.add("CTOR must keep the constructor descriptor: " + join(row));
                }
                if (index.hasClass(tOwner)) {
                    errors.add("CTOR replacement must not shadow an LWJGL3 class: " + tOwner);
                }
                break;
            case "ADAPTER":
                if (!isAdapterShape(owner, desc, tDesc)) {
                    errors.add("ADAPTER must keep the descriptor or take the receiver first: " + join(row));
                }
                if (index.hasClass(tOwner)) {
                    errors.add("ADAPTER owner must not shadow an LWJGL3 class: " + tOwner);
                }
                break;
            default:
                errors.add("unknown kind '" + kind + "': " + join(row));
        }
    }

    /** Mirrors the slot planning of CompatRewriter to catch recipe/descriptor mismatches. */
    private static void validateRecipe(Index index, String srcDesc, String dstDesc, String recipe, String[] row,
                                       List<String> errors) {
        final int srcCount = paramCount(srcDesc);
        final int dstCount = paramCount(dstDesc);
        int constSlots = 0;
        final Set<Integer> touched = new HashSet<>();
        for (String op : recipe.split(";")) {
            if (op.isEmpty()) {
                continue;
            }
            final String[] f = op.split(":");
            final String what = f[0];
            try {
                switch (what) {
                    case "insConst": {
                        final int at = Integer.parseInt(f[1]);
                        if (at < 0 || at >= dstCount) {
                            errors.add("CONVERT insConst index out of range: " + join(row));
                        }
                        if (!index.hasIntField(f[2])) {
                            errors.add("CONVERT constant missing or not an int: " + f[2]);
                        }
                        constSlots++;
                        touched.add(at);
                        break;
                    }
                    case "typeFromUnsigned":
                    case "widen":
                    case "addr": {
                        final int at = Integer.parseInt(f[1]);
                        if (at < 0 || at >= dstCount) {
                            errors.add("CONVERT " + what + " index out of range: " + join(row));
                        }
                        if (what.equals("typeFromUnsigned") && f.length > 2 && !index.hasIntField(f[2])) {
                            errors.add("CONVERT constant missing or not an int: " + f[2]);
                        }
                        touched.add(at);
                        break;
                    }
                    case "drop": {
                        final int at = Integer.parseInt(f[1]);
                        if (at < 0 || at >= srcCount) {
                            errors.add("CONVERT drop index out of range: " + join(row));
                        }
                        break;
                    }
                    default:
                        errors.add("CONVERT unknown recipe op '" + what + "': " + join(row));
                }
            } catch (NumberFormatException e) {
                errors.add("CONVERT recipe argument is not a number: " + op + " in " + join(row));
            }
        }
        // dst arity = src arity + insConst count - drop count
        int drops = 0;
        for (String op : recipe.split(";")) {
            if (op.startsWith("drop:")) {
                drops++;
            }
        }
        if (dstCount != srcCount + constSlots - drops) {
            errors.add("CONVERT arity mismatch (src=" + srcCount + ", dst=" + dstCount + ", insConst=" + constSlots
                    + ", drop=" + drops + "): " + join(row));
        }
    }

    // ------------------------------------------------------------------ helpers

    /** The adapter either keeps the descriptor or prepends the receiver, which turns the call into a static one. */
    private static boolean isAdapterShape(String owner, String desc, String adapterDesc) {
        return desc.equals(adapterDesc) || adapterDesc.equals("(L" + owner + ';' + desc.substring(1));
    }

    private static String join(String[] row) {
        return String.join(" | ", row);
    }

    private static int paramCount(String desc) {
        int n = 0;
        int i = 1;
        while (desc.charAt(i) != ')') {
            while (desc.charAt(i) == '[') {
                i++;
            }
            if (desc.charAt(i) == 'L') {
                i = desc.indexOf(';', i);
            }
            i++;
            n++;
        }
        return n;
    }

    private static String headerValue(List<String> lines, String prefix) {
        for (String line : lines) {
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length()).trim();
            }
        }
        return null;
    }

    private static Map<String, String> headerJarShas(List<String> lines) {
        final Map<String, String> out = new LinkedHashMap<>();
        for (String line : lines) {
            if (!line.startsWith("# l3-sha256:")) {
                continue;
            }
            final String rest = line.substring("# l3-sha256:".length()).trim();
            final int sep = rest.indexOf(' ');
            if (sep > 0) {
                out.put(rest.substring(0, sep), rest.substring(sep + 1).trim());
            }
        }
        return out;
    }

    private static Map<String, String> jarShas(List<File> jars) throws IOException {
        final Map<String, String> out = new HashMap<>();
        for (File jar : jars) {
            out.put(jar.getName(), sha256(Files.readAllBytes(jar.toPath())));
        }
        return out;
    }

    private static String sha256(byte[] data) {
        try {
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            final StringBuilder sb = new StringBuilder(64);
            for (byte b : md.digest(data)) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Minimal LWJGL3 metadata index resolving methods through superclasses and interfaces. */
    private static final class Index {

        static final class ClassInfo {
            String superName;
            final List<String> interfaces = new ArrayList<>();
            final Set<String> methods = new LinkedHashSet<>();
            final Set<String> intFields = new LinkedHashSet<>();
        }

        private final Map<String, ClassInfo> classes = new HashMap<>();

        static Index of(List<File> jars) throws IOException {
            final Index idx = new Index();
            for (File jar : jars) {
                try (ZipFile zf = new ZipFile(jar)) {
                    for (var e = zf.entries(); e.hasMoreElements(); ) {
                        final ZipEntry ze = e.nextElement();
                        if (!ze.getName().endsWith(".class") || ze.getName().startsWith("META-INF/")) {
                            continue;
                        }
                        final ClassReader cr;
                        try (var in = zf.getInputStream(ze)) {
                            cr = new ClassReader(in.readAllBytes());
                        }
                        final String owner = ze.getName().substring(0, ze.getName().length() - ".class".length());
                        final ClassInfo info = new ClassInfo();
                        cr.accept(new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public void visit(int version, int access, String name, String signature,
                                              String superName, String[] interfaces) {
                                info.superName = superName;
                                if (interfaces != null) {
                                    info.interfaces.addAll(Arrays.asList(interfaces));
                                }
                            }

                            @Override
                            public MethodVisitor visitMethod(int access, String name, String desc, String sig,
                                                             String[] ex) {
                                info.methods.add(name + desc);
                                return null;
                            }

                            @Override
                            public FieldVisitor visitField(int access, String name, String desc, String sig,
                                                           Object value) {
                                if ("I".equals(desc)) {
                                    info.intFields.add(name);
                                }
                                return null;
                            }
                        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                        idx.classes.put(owner, info);
                    }
                }
            }
            return idx;
        }

        boolean hasClass(String owner) {
            return classes.containsKey(owner);
        }

        boolean hasMethod(String owner, String name, String desc) {
            final String key = name + desc;
            final Set<String> seen = new HashSet<>();
            final List<String> stack = new ArrayList<>();
            stack.add(owner);
            while (!stack.isEmpty()) {
                final String c = stack.remove(stack.size() - 1);
                if (!seen.add(c)) {
                    continue;
                }
                final ClassInfo info = classes.get(c);
                if (info == null) {
                    continue;
                }
                if (info.methods.contains(key)) {
                    return true;
                }
                if (info.superName != null) {
                    stack.add(info.superName);
                }
                stack.addAll(info.interfaces);
            }
            return false;
        }

        /** Expects "owner/field" such as {@code org/lwjgl/opengl/GL11/GL_FLOAT}. */
        boolean hasIntField(String ownerAndName) {
            final int sep = ownerAndName.lastIndexOf('/');
            if (sep <= 0) {
                return false;
            }
            final ClassInfo info = classes.get(ownerAndName.substring(0, sep));
            return info != null && info.intFields.contains(ownerAndName.substring(sep + 1));
        }
    }
}
