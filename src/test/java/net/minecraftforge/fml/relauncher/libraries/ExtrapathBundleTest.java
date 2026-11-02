package net.minecraftforge.fml.relauncher.libraries;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import net.minecraft.launchwrapper.Launch;
import net.minecraftforge.common.ForgeVersion;
import net.minecraftforge.fml.relauncher.FMLLaunchHandler;
import net.minecraftforge.fml.relauncher.Side;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers a mod named on the extras path: a jar that is in no mod list, outside the mods folder.
 *
 * <p>{@code setup} walks the mods folder and the known lists, so such a jar used to be scanned and never opened
 * otherwise - its {@code containedDeps} were read by nobody, and the dependency it shipped stayed inside it. The
 * loader then reported the mod as present while the mod it bundles was missing.
 */
class ExtrapathBundleTest {

    private static final String PARENT_METADATA = "{\"jar\":{\"schemaVersion\":1,\"license\":\"MIT\",\"containedDeps\":["
            + "{\"path\":\"inner.jar\"},"
            + "{\"path\":\"extralib.jar\",\"kind\":\"lib\",\"id\":\"com.example:extralib\",\"version\":\"1.0\"}"
            + "]},\"modList\":[{\"modid\":\"parent\"}]}";

    @Test
    void theBundleOfAnUnmanagedJarIsUnpackedAndDiscovered(@TempDir Path home, @TempDir Path outside) throws IOException {
        // The mod list lookup reads the launch arguments on every call, even when nothing set --mods.
        // The tweaker is the only thing that says which side a launch is, and this test stands in for it.
        FMLLaunchHandler.setSide(Side.CLIENT);
        Launch.blackboard = new HashMap<>();
        Launch.blackboard.put("forgeLaunchArgs", new HashMap<String, String>());
        Files.createDirectories(home.resolve("mods"));

        File parent = extrasPathJar(outside);
        System.setProperty("outlands.dev.extrapath", parent.getAbsolutePath());
        try {
            LibraryManager.setup(home.toFile());
            List<File> candidates = LibraryManager.getCandidates();

            File versionDir = new File(new File(home.toFile(), "mods"), ForgeVersion.mcVersion);
            File inner = new File(versionDir, "inner.jar").getAbsoluteFile();
            assertTrue(inner.isFile(), "the jar bundled by an extras-path mod must be unpacked like any other");
            assertTrue(candidates.contains(inner),
                    "an unpacked jar has to be a candidate of this launch, not of the next one");
            assertTrue(candidates.contains(parent.getAbsoluteFile()), "the extras-path jar is scanned as usual");

            // The library is mounted inside its parent when that mode is on, and unpacked beside the mods when it is
            // not; either way it must not be silently dropped.
            assertTrue(LibraryManager.bundledLibraryNamed("extralib.jar") != null
                            || !LibraryManager.mountedLibraries().isEmpty(),
                    "a bundled library must end up reachable from the classpath");

            assertSame(candidates, LibraryManager.getCandidates(), "the candidate list is decided once");
        } finally {
            System.clearProperty("outlands.dev.extrapath");
        }
    }

    /** A parent jar carrying one mod-kind and one lib-kind bundled jar, plus the metadata that declares them. */
    private static File extrasPathJar(Path dir) throws IOException {
        Path path = Files.createDirectories(dir).resolve("parent.jar");
        try (OutputStream out = Files.newOutputStream(path); JarOutputStream jar = new JarOutputStream(out)) {
            entry(jar, "mcmod.info", PARENT_METADATA);
            entry(jar, "inner.jar", innerJar());
            entry(jar, "extralib.jar", innerJar());
        }
        return path.toFile();
    }

    /** Bytes of a minimal jar, produced by writing one to a temporary stream. */
    private static byte[] innerJar() throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes)) {
            entry(jar, "marker.txt", "marker");
        }
        return bytes.toByteArray();
    }

    private static void entry(JarOutputStream jar, String name, String content) throws IOException {
        entry(jar, name, content.getBytes(StandardCharsets.UTF_8));
    }

    private static void entry(JarOutputStream jar, String name, byte[] content) throws IOException {
        jar.putNextEntry(new JarEntry(name));
        jar.write(content);
        jar.closeEntry();
    }
}
