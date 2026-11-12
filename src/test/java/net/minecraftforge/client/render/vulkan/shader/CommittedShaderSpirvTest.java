package net.minecraftforge.client.render.vulkan.shader;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every GLSL source this project ships is the one its committed SPIR-V was built from.
 *
 * <p><b>Why this exists.</b> The backend loads {@code .spv} and only {@code .spv}; the GLSL next to it is a source
 * that no code path reads. A source edited without recompiling therefore changes nothing at run time, and nothing
 * anywhere says so - the shader keeps behaving like the binary from before the edit. That is not hypothetical:
 * {@code stream.frag}'s outline-mode branches were written, unit-tested and reviewed, and never executed once, because
 * {@link ShaderCompiler}'s list of shaders had only the two terrain stages. Four rounds of in-world testing measured
 * the old behaviour of a shader that had been "fixed".</p>
 *
 * <p>The check recompiles from the source on the classpath and compares bytes with the committed binary, so it also
 * fails when a new GLSL file is added without being listed. Fix by running {@code ./gradlew :outlands:compileShaders}
 * and committing the result.</p>
 */
class CommittedShaderSpirvTest {

    private static final String DIRECTORY = "/assets/outlands/shaders/";

    @Test
    void everySourceMatchesItsCommittedSpirv() throws IOException {
        List<String> problems = new ArrayList<>();
        for (String[] shader : ShaderCompiler.SHADERS) {
            String name = shader[0];
            String source = read(name);
            byte[] committed = readBytes(name + ".spv");
            assertNotNull(committed, name + ".spv is missing; run ./gradlew :outlands:compileShaders");
            byte[] compiled = GlslCompiler.compile(source, stage(shader[1]), name, false);
            if (!Arrays.equals(compiled, committed)) {
                problems.add(name + ": the source compiles to " + compiled.length + " bytes, the committed module is "
                        + committed.length);
            }
        }
        assertTrue(problems.isEmpty(), "committed SPIR-V is out of date with its GLSL - run"
                + " ./gradlew :outlands:compileShaders and commit the result:\n  " + String.join("\n  ", problems));
    }

    /**
     * Every GLSL file in the directory is listed.
     *
     * <p>The other half of the same trap: a source that exists and is not in the list is silently never compiled -
     * exactly how {@code stream.frag} ended up running yesterday's binary.</p>
     */
    @Test
    void everyShippedSourceIsListed() throws IOException {
        List<String> listed = new ArrayList<>();
        for (String[] shader : ShaderCompiler.SHADERS) {
            listed.add(shader[0]);
        }
        List<String> unlisted = new ArrayList<>();
        for (String name : sourcesInDirectory()) {
            if (!listed.contains(name)) {
                unlisted.add(name);
            }
        }
        assertTrue(unlisted.isEmpty(),
                "these shaders have no SPIR-V and are never compiled - add them to ShaderCompiler.SHADERS: " + unlisted);
    }

    /** The GLSL sources the build put on the classpath, sorted. */
    private static List<String> sourcesInDirectory() throws IOException {
        java.net.URL url = CommittedShaderSpirvTest.class.getResource(DIRECTORY);
        assertNotNull(url, "the shader directory is not on the classpath");
        List<String> names = new ArrayList<>();
        try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.list(
                java.nio.file.Path.of(url.getPath().replace("%20", " ")))) {
            files.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".vert") || name.endsWith(".frag"))
                    .sorted()
                    .forEach(names::add);
        }
        return names;
    }

    private static String read(String name) throws IOException {
        try (InputStream stream = CommittedShaderSpirvTest.class.getResourceAsStream(DIRECTORY + name)) {
            assertNotNull(stream, name + " is not on the classpath");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static byte[] readBytes(String name) throws IOException {
        try (InputStream stream = CommittedShaderSpirvTest.class.getResourceAsStream(DIRECTORY + name)) {
            return stream == null ? null : stream.readAllBytes();
        }
    }

    private static GlslCompiler.Stage stage(String name) {
        return switch (name) {
            case "vertex" -> GlslCompiler.Stage.VERTEX;
            case "fragment" -> GlslCompiler.Stage.FRAGMENT;
            default -> throw new IllegalArgumentException("unsupported stage: " + name);
        };
    }
}
