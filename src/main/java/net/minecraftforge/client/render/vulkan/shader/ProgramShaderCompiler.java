package net.minecraftforge.client.render.vulkan.shader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Compiles the game's own shader programs to SPIR-V, ahead of time.
 *
 * <p><b>Why ahead of time.</b> The runtime compiler exists for source that arrives while the game is running - a mod,
 * a shader pack, a mod loader's shader entry point. The game's own programs are fixed: they ship in the assets jar,
 * this repository already knows how to make them Vulkan-compatible, so the right time to do it is once at build time.
 * Shipping the modules means the vanilla path never compiles anything, never depends on shaderc being present, and
 * cannot be affected by a change to the rewrite rules.</p>
 *
 * <p>Reads {@code assets/minecraft/shaders/program/*.{vsh,fsh}} out of the assets jar and writes
 * {@code .spv} next to where they will be loaded from.</p>
 *
 * <p><b>Lives in the main source set although nothing calls it at run time.</b> It has to: the Gradle task that runs it
 * feeds {@code processResources}, so taking it from the test source set would make the task depend on the test classes,
 * which depend on the main classes, which depend on the resources the task produces. A tool in {@code main} is the
 * smaller wart.</p>
 */
public final class ProgramShaderCompiler {

    private static final String PROGRAM_DIR = "assets/minecraft/shaders/program/";

    private ProgramShaderCompiler() {}

    public static void main(String[] args) throws IOException {
        Path jar = Path.of(args[0]);
        Path output = Path.of(args[1]);
        Path targetDir = output.resolve(PROGRAM_DIR);
        Files.createDirectories(targetDir);

        List<String> names = new ArrayList<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.startsWith(PROGRAM_DIR)) {
                    continue;
                }
                String file = name.substring(PROGRAM_DIR.length());
                if (!file.endsWith(".vsh") && !file.endsWith(".fsh")) {
                    continue;
                }
                names.add(file);
            }
            names.sort(String::compareTo);

            for (String file : names) {
                String source = read(zip, PROGRAM_DIR + file);
                GlslCompiler.Stage stage = file.endsWith(".vsh")
                        ? GlslCompiler.Stage.VERTEX : GlslCompiler.Stage.FRAGMENT;
                // Names are needed here: the facade answers glGetUniformLocation for these programs by name.
                byte[] spirv = GlslCompiler.compile(
                        GlslPreprocessor.forVulkan(source, stage), stage, file, true);
                Files.write(targetDir.resolve(file + ".spv"), spirv);
            }
        } finally {
            GlslCompiler.release();
        }
        System.out.println("compiled " + names.size() + " program shaders into " + targetDir);
    }

    private static String read(ZipFile zip, String name) {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) {
            throw new IllegalArgumentException("missing " + name + " in " + zip.getName());
        }
        try (InputStream in = zip.getInputStream(entry)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            in.transferTo(out);
            return out.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
