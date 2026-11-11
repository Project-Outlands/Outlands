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
 * <p><b>Two kinds of source.</b> A file is taken from {@code handWritten} when one exists there, and otherwise from the
 * assets jar through {@link GlslPreprocessor}. The hand-written ones are the chain this project actually depends on -
 * the entity outline is the first - and they are written for Vulkan directly: explicit block bindings, explicit
 * interface locations, {@code texture()} instead of {@code texture2D}. They are not passed through the preprocessor,
 * because there is nothing to bring forward and because a mistake in them should fail here, at build time, instead of
 * being quietly repaired.</p>
 *
 * <p>A hand-written name that is not one of the game's shaders is an error rather than a warning: it means a file was
 * misspelled or left behind, and the failure mode of ignoring it is a module that is silently <i>not</i> the one the
 * author wrote.</p>
 *
 * <p>Reads {@code assets/minecraft/shaders/program/*.{vsh,fsh}} out of the assets jar and writes {@code .spv} next to
 * where they will be loaded from.</p>
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
        Path handWritten = args.length > 2 ? Path.of(args[2]) : null;
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

            List<String> handWrittenNames = listHandWritten(handWritten);
            for (String file : handWrittenNames) {
                if (!names.contains(file)) {
                    throw new IllegalStateException("hand-written shader " + file + " is not one of the game's"
                            + " shaders in " + jar.getFileName() + "; the name has to match the file it replaces"
                            + " exactly, or its module is never loaded");
                }
            }

            int fromHandWritten = 0;
            for (String file : names) {
                GlslCompiler.Stage stage = file.endsWith(".vsh")
                        ? GlslCompiler.Stage.VERTEX : GlslCompiler.Stage.FRAGMENT;
                String source;
                String origin;
                if (handWrittenNames.contains(file)) {
                    source = Files.readString(handWritten.resolve(file), StandardCharsets.UTF_8);
                    origin = "hand-written";
                    fromHandWritten++;
                } else {
                    source = read(zip, PROGRAM_DIR + file);
                    source = GlslPreprocessor.forVulkan(source, stage);
                    origin = "rewritten";
                }
                // Names are needed here: the facade answers glGetUniformLocation for these programs by name.
                byte[] spirv = GlslCompiler.compile(source, stage, file, true);
                Files.write(targetDir.resolve(file + ".spv"), spirv);
                System.out.println("  " + file + " (" + origin + ") -> " + spirv.length + " bytes");
            }
            System.out.println("compiled " + names.size() + " program shaders into " + targetDir
                    + " (" + fromHandWritten + " hand-written)");
        } finally {
            GlslCompiler.release();
        }
    }

    /** The hand-written sources that are present, as the game's file names. An absent directory means none. */
    private static List<String> listHandWritten(Path directory) throws IOException {
        List<String> names = new ArrayList<>();
        if (directory == null || !Files.isDirectory(directory)) {
            return names;
        }
        try (var stream = Files.list(directory)) {
            stream.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".vsh") || name.endsWith(".fsh"))
                    .sorted()
                    .forEach(names::add);
        }
        return names;
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
