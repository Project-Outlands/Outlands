package net.minecraftforge.fml.common.discovery.index;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vanilla's asset answer is one view: the downloaded objects, then the jar the index already identified. A run that
 * found neither is empty rather than broken.
 */
class VanillaAssetsTest {

    private static final ClassNaming IDENTITY = new ClassNaming() {
        @Override
        public String type(String internalName) {
            return internalName;
        }

        @Override
        public String member(String owner, String name, String descriptor, boolean method) {
            return name;
        }

        @Override
        public String descriptor(String descriptor) {
            return descriptor;
        }
    };

    @Test
    void theJarAnswersOnceItIsIdentified(@TempDir Path dir) throws Exception {
        final File gameDir = Files.createDirectories(dir.resolve("game")).toFile();
        final File minecraft = minecraftJar(dir);
        final LoadIndex index = new LoadIndex(gameDir, List.of(minecraft));

        assertEquals(AssetView.NONE.layers().size(), index.vanillaAssets().layers().size(),
                "before the jar is identified there is nothing to answer from");

        assertTrue(index.indexMinecraftJar(IDENTITY, "salt"));

        final AssetView view = index.vanillaAssets();
        assertEquals(1, view.layers().size(), "the launcher's assets directory is not known here, so the jar alone");
        assertArrayEquals("texture".getBytes(StandardCharsets.UTF_8),
                view.read("assets/minecraft/textures/blocks/stone.png"));
        assertNull(view.read("assets/minecraft/lang/en_us.lang"));
    }

    @Test
    void theDownloadedHalfComesFirstWhenTheLauncherPassedOne(@TempDir Path dir) throws Exception {
        final File gameDir = Files.createDirectories(dir.resolve("game")).toFile();
        final File minecraft = minecraftJar(dir);
        final Path assets = assets(dir, Map.of("minecraft/lang/en_us.lang", "downloaded"));
        System.setProperty("outlands.assets.dir", assets.toString());
        try {
            final LoadIndex index = new LoadIndex(gameDir, List.of(minecraft));
            assertTrue(index.indexMinecraftJar(IDENTITY, "salt"));

            final AssetView view = index.vanillaAssets();

            assertEquals(2, view.layers().size());
            assertArrayEquals("downloaded".getBytes(StandardCharsets.UTF_8),
                    view.read("assets/minecraft/lang/en_us.lang"), "the objects answer first");
            assertArrayEquals("texture".getBytes(StandardCharsets.UTF_8),
                    view.read("assets/minecraft/textures/blocks/stone.png"), "and the jar answers for the rest");
        } finally {
            System.clearProperty("outlands.assets.dir");
        }
    }

    @Test
    void theDownloadedHalfStillAnswersWithoutTheJar(@TempDir Path dir) throws Exception {
        final File gameDir = Files.createDirectories(dir.resolve("game")).toFile();
        final Path assets = assets(dir, Map.of("minecraft/lang/zh_cn.lang", "downloaded"));
        System.setProperty("outlands.assets.dir", assets.toString());
        try {
            final LoadIndex index = new LoadIndex(gameDir, List.of());

            assertNull(index.vanillaArchive());
            final AssetView view = index.vanillaAssets();
            assertEquals(1, view.layers().size(), "only the downloaded half is known");
            assertArrayEquals("downloaded".getBytes(StandardCharsets.UTF_8),
                    view.read("assets/minecraft/lang/zh_cn.lang"));
        } finally {
            System.clearProperty("outlands.assets.dir");
        }
    }

    private static File minecraftJar(Path dir) throws IOException {
        final Map<String, byte[]> entries = new LinkedHashMap<>();
        // the marker the index recognises, and a class the scan can read
        entries.put("net/minecraft/client/ClientBrandRetriever", clazz("net/minecraft/client/ClientBrandRetriever"));
        entries.put("pkg/Plain", clazz("pkg/Plain"));
        final File jar = dir.resolve("client.jar").toFile();
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(jar.toPath()))) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey() + ".class"));
                out.write(entry.getValue());
                out.closeEntry();
            }
            out.putNextEntry(new ZipEntry("assets/minecraft/textures/blocks/stone.png"));
            out.write("texture".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        return jar.getAbsoluteFile();
    }

    private static Path assets(Path dir, Map<String, String> files) throws IOException {
        final Path assets = dir.resolve("assets");
        final StringBuilder rows = new StringBuilder();
        for (Map.Entry<String, String> entry : files.entrySet()) {
            final byte[] bytes = entry.getValue().getBytes(StandardCharsets.UTF_8);
            final String hash = sha1(bytes);
            final Path file = assets.resolve("objects").resolve(hash.substring(0, 2)).resolve(hash);
            Files.createDirectories(file.getParent());
            Files.write(file, bytes);
            if (rows.length() > 0) {
                rows.append(',');
            }
            rows.append('"').append(entry.getKey()).append("\":{\"hash\":\"").append(hash)
                    .append("\",\"size\":").append(bytes.length).append('}');
        }
        final Path indexes = Files.createDirectories(assets.resolve("indexes"));
        Files.writeString(indexes.resolve("1.12.json"), "{\"objects\":{" + rows + "}}", StandardCharsets.UTF_8);
        return assets;
    }

    private static String sha1(byte[] bytes) throws IOException {
        try {
            final java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-1");
            final StringBuilder out = new StringBuilder();
            for (byte b : digest.digest(bytes)) {
                out.append(String.format("%02x", b));
            }
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static byte[] clazz(String name) {
        final ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object", null);
        final MethodVisitor constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.visitCode();
        constructor.visitVarInsn(Opcodes.ALOAD, 0);
        constructor.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        constructor.visitInsn(Opcodes.RETURN);
        constructor.visitMaxs(1, 1);
        constructor.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
