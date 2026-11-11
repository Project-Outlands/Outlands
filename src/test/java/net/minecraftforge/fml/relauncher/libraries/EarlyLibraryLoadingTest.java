package net.minecraftforge.fml.relauncher.libraries;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import net.minecraft.launchwrapper.Launch;
import net.minecraftforge.common.ForgeVersion;
import net.minecraftforge.fml.relauncher.FMLLaunchHandler;
import net.minecraftforge.fml.relauncher.Side;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EarlyLibraryLoadingTest {
    private final Map<String, Object> previousBlackboard = Launch.blackboard;
    private final File previousHome = Launch.minecraftHome;
    private final String previousMode = System.getProperty("outlands.nestedJars");

    @AfterEach
    void restoreLaunch() {
        Launch.blackboard = previousBlackboard;
        Launch.minecraftHome = previousHome;
        if (previousMode == null) {
            System.clearProperty("outlands.nestedJars");
        } else {
            System.setProperty("outlands.nestedJars", previousMode);
        }
        NestedLibraryJars.closeAll();
    }

    @ParameterizedTest
    @ValueSource(strings = {"zipfs", "extract"})
    void legacyLibrariesAreVisibleBeforeCoremodLoading(String mode, @TempDir Path home) throws Exception {
        System.setProperty("outlands.nestedJars", mode);
        String first = "first-" + home.getFileName() + ".jar";
        String second = "second-" + home.getFileName() + ".jar";
        File source = prepare(home, "true", null, List.of(
                new Library(first, first, "bundled.First"),
                new Library(second, "META-INF/libraries/" + second, "bundled.Second")));

        List<URL> libraries = LibraryManager.earlyLibraryUrls(source);
        assertEquals(2, libraries.size());
        try (URLClassLoader loader = loader(source, libraries)) {
            assertTrue(loader.getResource("bundled/First.class") != null);
            assertTrue(loader.getResource("bundled/Second.class") != null);
        }
        assertEquals(mode.equals("extract"), Files.exists(versionDir(home).resolve(first)));
        assertEquals(mode.equals("extract"), Files.exists(versionDir(home).resolve(second)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"zipfs", "extract"})
    void falseLegacyFlagKeepsDependenciesOutOfTheEarlyClasspath(String mode, @TempDir Path home) throws Exception {
        verifyLegacyFlagDoesNotInject(home, mode, "false");
    }

    @ParameterizedTest
    @ValueSource(strings = {"zipfs", "extract"})
    void absentLegacyFlagKeepsDependenciesOutOfTheEarlyClasspath(String mode, @TempDir Path home) throws Exception {
        verifyLegacyFlagDoesNotInject(home, mode, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"zipfs", "extract"})
    void structuredDependencyKindsOverrideTheLegacyFlag(String mode, @TempDir Path home) throws Exception {
        System.setProperty("outlands.nestedJars", mode);
        String mod = "mod-" + home.getFileName() + ".jar";
        String lib = "lib-" + home.getFileName() + ".jar";
        String metadata = "{\"jar\":{\"schemaVersion\":1,\"license\":\"MIT\",\"containedDeps\":["
                + "{\"path\":\"" + mod + "\",\"kind\":\"MOD\"},"
                + "{\"path\":\"" + lib + "\",\"kind\":\"LIB\"}]}}";
        File source = prepare(home, "true", metadata, List.of(
                new Library(mod, mod, "bundled.Mod"),
                new Library(lib, "META-INF/libraries/" + lib, "bundled.Library")));

        List<URL> libraries = LibraryManager.earlyLibraryUrls(source);
        assertEquals(1, libraries.size());
        try (URLClassLoader loader = loader(source, libraries)) {
            assertTrue(loader.getResource("bundled/Library.class") != null);
            assertTrue(loader.getResource("bundled/Mod.class") == null);
        }
        assertTrue(LibraryManager.getCandidates().contains(versionDir(home).resolve(mod).toFile()));
    }

    @Test
    void anExistingExtractedLegacyLibraryIsUsableImmediately(@TempDir Path home) throws Exception {
        System.setProperty("outlands.nestedJars", "extract");
        String name = "existing-" + home.getFileName() + ".jar";
        Files.createDirectories(versionDir(home));
        Files.write(versionDir(home).resolve(name), libraryJar("bundled.Existing"));
        File source = prepare(home, "true", null,
                List.of(new Library(name, name, "bundled.Existing")));

        try (URLClassLoader loader = loader(source, LibraryManager.earlyLibraryUrls(source))) {
            assertTrue(loader.getResource("bundled/Existing.class") != null);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"zipfs", "extract"})
    void skippedLibrariesAreExcludedByDeclaredPathResolvedPathAndFileName(String mode, @TempDir Path home) throws Exception {
        System.setProperty("outlands.nestedJars", mode);
        String name = "skipped-" + home.getFileName() + ".jar";
        String resolved = "META-INF/libraries/" + name;
        File source = prepare(home, "true", null,
                List.of(new Library(name, resolved, "bundled.Skipped")));

        assertTrue(LibraryManager.earlyLibraryUrls(source, List.of(name)).isEmpty());
        assertTrue(LibraryManager.earlyLibraryUrls(source, List.of(resolved)).isEmpty());
    }

    private static void verifyLegacyFlagDoesNotInject(Path home, String mode, String flag) throws Exception {
        System.setProperty("outlands.nestedJars", mode);
        String name = "ordinary-" + home.getFileName() + ".jar";
        File source = prepare(home, flag, null, List.of(new Library(name, name, "bundled.Ordinary")));

        List<URL> libraries = LibraryManager.earlyLibraryUrls(source);
        assertTrue(libraries.isEmpty());
        try (URLClassLoader loader = loader(source, libraries)) {
            assertTrue(loader.getResource("bundled/Ordinary.class") == null);
        }
        assertTrue(LibraryManager.getCandidates().contains(versionDir(home).resolve(name).toFile()));
    }

    private static File prepare(Path home, String flag, String metadata, List<Library> libraries) throws IOException {
        Path mods = Files.createDirectories(home.resolve("mods"));
        File source = mods.resolve("parent.jar").toFile();
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.putValue("ContainedDeps", String.join(" ", libraries.stream().map(Library::declaredPath).toList()));
        if (flag != null) {
            attributes.putValue("NonModDeps", flag);
        }
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(source.toPath()), manifest)) {
            if (metadata != null) {
                entry(jar, "mcmod.info", metadata.getBytes(StandardCharsets.UTF_8));
            }
            for (Library library : libraries) {
                entry(jar, library.entryPath(), libraryJar(library.className()));
            }
        }
        Launch.minecraftHome = home.toFile();
        Launch.blackboard = new HashMap<>();
        Launch.blackboard.put("forgeLaunchArgs", Map.of());
        FMLLaunchHandler.setSide(Side.CLIENT);
        LibraryManager.setup(home.toFile());
        assertTrue(LibraryManager.getCandidates().contains(source));
        return source;
    }

    private static Path versionDir(Path home) {
        return home.resolve("mods").resolve(ForgeVersion.mcVersion);
    }

    private static URLClassLoader loader(File source, List<URL> libraries) throws IOException {
        List<URL> urls = new ArrayList<>();
        urls.add(source.toURI().toURL());
        urls.addAll(libraries);
        return new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
    }

    private static byte[] libraryJar(String className) throws IOException {
        String internalName = className.replace('.', '/');
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, internalName, null, "java/lang/Object", null);
        writer.visitEnd();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes)) {
            entry(jar, internalName + ".class", writer.toByteArray());
        }
        return bytes.toByteArray();
    }

    private static void entry(JarOutputStream jar, String name, byte[] content) throws IOException {
        jar.putNextEntry(new JarEntry(name));
        jar.write(content);
        jar.closeEntry();
    }

    private record Library(String declaredPath, String entryPath, String className) {}

}
