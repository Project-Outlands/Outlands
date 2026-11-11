package net.minecraftforge.client.render.shader;

import net.minecraftforge.client.render.vulkan.shader.GlslCompiler;
import net.minecraftforge.client.render.vulkan.shader.GlslPreprocessor;
import net.minecraftforge.client.render.vulkan.shader.SpirvReflection;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The GL shader objects the game builds, kept as SPIR-V modules and uniform buffers.
 *
 * <p><b>Why a facade instead of a translation of every call.</b> The game's shader layer is written against the GL 2.0
 * object model: create a shader, give it source, compile it, attach it to a program, link, then look uniforms up by
 * name and write them. None of that has an equivalent in Vulkan, which wants modules prepared ahead of time and a
 * descriptor set per program. This class keeps the object model - it is what the game talks to - and defers only the
 * two things that need a device: turning a linked program into pipelines, and drawing with it.</p>
 *
 * <p><b>Compilation is GLSL to SPIR-V, here and now.</b> Sources arrive as strings, so they are brought forward by
 * {@link GlslPreprocessor} and compiled by {@link GlslCompiler}. The game's own programs are also precompiled into the
 * resources; a caller that has a module already never reaches the compiler.</p>
 *
 * <p><b>Names come back from the module, not from the source.</b> {@code glGetUniformLocation} is answered by the
 * SPIR-V's own name table through {@link SpirvReflection}, so it agrees with what the driver would have compiled
 * rather than with what a text scan of the GLSL expected.</p>
 *
 * <p><b>No GL, no exception.</b> Compiling a shader that does not compile is a normal event with a log attached, and
 * the status call reports it; that is what GL does and what the game checks. Throwing here would turn a bad mod shader
 * into a crash.</p>
 */
public final class GlShaders {

    private static final Logger LOGGER = LogManager.getLogger("Outlands|render");

    // GL constants, spelled out because this class answers GL's questions.
    public static final int VERTEX_SHADER = 35633;
    public static final int FRAGMENT_SHADER = 35632;
    public static final int COMPILE_STATUS = 35713;
    public static final int LINK_STATUS = 35714;

    /** One shader object: a stage's source, and its module once compiled. */
    public record Shader(int id, int glType, String source, byte[] spirv, String log) {
        public boolean compiled() {
            return spirv != null;
        }
    }

    /**
     * One addressable uniform.
     *
     * <p>A sampler is a texture binding; anything else is bytes at an offset in the program's block. That is the whole
     * distinction: Vulkan has no scalar uniforms, so a {@code glUniform4f} becomes a write into a fixed-size block at
     * the offset the compiler chose.</p>
     */
    public record Uniform(int location, String name, boolean sampler, int offset, int size,
                          int descriptorSet, int binding) {}

    /** One linked program: its stages, their interface, and the bytes the game has written into its uniforms. */
    public static final class Program {
        private final int id;
        private final List<Integer> stages = new ArrayList<>();
        private final Map<String, Integer> attributeLocations = new LinkedHashMap<>();
        private final List<Uniform> uniforms = new ArrayList<>();
        private final Map<String, Uniform> uniformsByName = new LinkedHashMap<>();
        private byte[] blockBytes = new byte[0];
        private int blockBinding;
        private int blockSet;
        private String log = "";
        private boolean linked;

        Program(int id) {
            this.id = id;
        }

        public int id() {
            return id;
        }

        public boolean linked() {
            return linked;
        }

        public String log() {
            return log;
        }

        public List<Integer> stages() {
            return stages;
        }

        /** name -> attribute index, as the game asked for it. */
        public Map<String, Integer> attributeLocations() {
            return attributeLocations;
        }

        public List<Uniform> uniforms() {
            return uniforms;
        }

        public Uniform uniform(String name) {
            return uniformsByName.get(name);
        }

        public int blockSet() {
            return blockSet;
        }

        public int blockBinding() {
            return blockBinding;
        }

        /** The uniform block's bytes: what the game has written through glUniform*, laid out as the module expects. */
        public byte[] blockBytes() {
            return blockBytes;
        }

        /** The SPIR-V of a stage, or null when the program has no such stage. */
        public byte[] stageModule(int glType) {
            for (int stageId : stages) {
                Shader candidate = SHADERS.get(stageId);
                if (candidate != null && candidate.glType() == glType) {
                    return candidate.spirv();
                }
            }
            return null;
        }
    }

    private static final Map<Integer, Shader> SHADERS = new LinkedHashMap<>();
    private static final Map<Integer, Program> PROGRAMS = new LinkedHashMap<>();
    private static int nextShaderId = 1;
    private static int nextProgramId = 1;
    private static int currentProgram;

    private GlShaders() {}

    /**
     * Creates a shader object.
     *
     * @param glType {@link #VERTEX_SHADER} or {@link #FRAGMENT_SHADER}
     */
    public static int createShader(int glType) {
        if (glType != VERTEX_SHADER && glType != FRAGMENT_SHADER) {
            // GL would report an error and return 0. Nothing in the game asks for a geometry or compute stage, and
            // pretending to support one would fail later and less clearly.
            LOGGER.warn("createShader: unsupported shader type {} (only vertex and fragment are implemented)", glType);
            return 0;
        }
        int id = nextShaderId++;
        SHADERS.put(id, new Shader(id, glType, "", null, ""));
        return id;
    }

    /** Replaces a shader's source, the way {@code glShaderSource} does. */
    public static void shaderSource(int shader, String source) {
        Shader existing = SHADERS.get(shader);
        if (existing == null) {
            return;
        }
        SHADERS.put(shader, new Shader(shader, existing.glType(), source == null ? "" : source,
                existing.spirv(), existing.log()));
    }

    /**
     * Compiles a shader.
     *
     * <p>A failure is recorded rather than thrown: the game reads the status and prints the log, which is how vanilla
     * reports a broken shader pack, and it must keep going.</p>
     */
    public static void compileShader(int shader) {
        Shader existing = SHADERS.get(shader);
        if (existing == null) {
            return;
        }
        if (existing.compiled()) {
            // A module was attached for this shader: a precompiled one next to the source. Nothing to do.
            return;
        }
        GlslCompiler.Stage stage = existing.glType() == VERTEX_SHADER
                ? GlslCompiler.Stage.VERTEX : GlslCompiler.Stage.FRAGMENT;
        try {
            String forward = GlslPreprocessor.forVulkan(existing.source(), stage);
            byte[] spirv = GlslCompiler.compile(forward, stage, "shader" + shader, true);
            SHADERS.put(shader, new Shader(shader, existing.glType(), existing.source(), spirv, ""));
        } catch (GlslCompiler.GlslCompileException e) {
            SHADERS.put(shader, new Shader(shader, existing.glType(), existing.source(), null, e.log()));
            LOGGER.warn("shader {} (type {}) did not compile:\n{}", shader, existing.glType(), e.log());
        } catch (RuntimeException | LinkageError e) {
            // The native compiler missing is not the shader's fault, so it is reported as a failure with a reason
            // rather than as a crash. The game will disable the feature that needed it.
            String reason = "GLSL compiler unavailable: " + e;
            SHADERS.put(shader, new Shader(shader, existing.glType(), existing.source(), null, reason));
            LOGGER.error("shader {} could not be compiled: {}", shader, reason);
        }
    }

    /** {@code glGetShaderi}: {@link #COMPILE_STATUS}, and nothing else the game asks for. */
    public static int shaderStatus(int shader, int parameter) {
        Shader existing = SHADERS.get(shader);
        if (existing == null) {
            return 0;
        }
        return parameter == COMPILE_STATUS ? (existing.compiled() ? 1 : 0) : 0;
    }

    public static String shaderLog(int shader) {
        Shader existing = SHADERS.get(shader);
        return existing == null ? "" : existing.log();
    }

    /**
     * Gives a shader object a module that was found beside its source, so {@link #compileShader} adopts it instead of
     * compiling anything.
     *
     * <p>This is what a precompiled module is for: the source is never touched, and the shipped module is what runs.</p>
     */
    public static void attachModule(int shader, byte[] spirv) {
        Shader existing = SHADERS.get(shader);
        if (existing == null || spirv == null) {
            return;
        }
        SHADERS.put(shader, new Shader(shader, existing.glType(), existing.source(), spirv, ""));
    }

    /**
     * Fails a shader with a reason, without compiling anything.
     *
     * <p>Used when a shipped module turns out to be unusable: the shader reports a compile failure carrying the
     * reason, which is what the game reads and prints. Compiling the GLSL instead would silently override the choice
     * the module's author made.</p>
     */
    public static void failShader(int shader, String reason) {
        Shader existing = SHADERS.get(shader);
        if (existing == null) {
            return;
        }
        SHADERS.put(shader, new Shader(shader, existing.glType(), existing.source(), null, reason));
    }

    public static void deleteShader(int shader) {
        SHADERS.remove(shader);
    }

    public static int createProgram() {
        int id = nextProgramId++;
        PROGRAMS.put(id, new Program(id));
        return id;
    }

    public static void attachShader(int program, int shader) {
        Program existing = PROGRAMS.get(program);
        if (existing != null && SHADERS.containsKey(shader) && !existing.stages.contains(shader)) {
            existing.stages.add(shader);
        }
    }

    /** {@code glBindAttribLocation}: recorded, and used when the pipeline is built. */
    public static void bindAttribLocation(int program, int index, CharSequence name) {
        Program existing = PROGRAMS.get(program);
        if (existing != null && name != null) {
            existing.attributeLocations.put(name.toString(), index);
        }
    }

    /**
     * Links a program: compiles nothing, but reads each stage's interface and builds the uniform table.
     *
     * <p>The block's set and binding come from the module's own decorations, which the preprocessor arranged to be 0
     * and 0 for the collected uniforms. Samplers keep the bindings the compiler assigned.</p>
     */
    public static void linkProgram(int program) {
        Program existing = PROGRAMS.get(program);
        if (existing == null) {
            return;
        }
        StringBuilder problems = new StringBuilder();
        List<SpirvReflection.Module> modules = new ArrayList<>();
        for (int stageId : existing.stages) {
            Shader shader = SHADERS.get(stageId);
            if (shader == null) {
                continue;
            }
            if (!shader.compiled()) {
                problems.append("stage ").append(stageId).append(" did not compile\n");
                continue;
            }
            modules.add(SpirvReflection.reflect(shader.spirv()));
        }
        existing.log = problems.toString();
        if (!problems.isEmpty() || modules.isEmpty()) {
            existing.linked = false;
            if (!problems.isEmpty()) {
                LOGGER.warn("program {} did not link:\n{}", program, existing.log);
            }
            return;
        }

        existing.uniforms.clear();
        existing.uniformsByName.clear();
        int location = 1;
        int blockSize = 0;
        for (SpirvReflection.Module module : modules) {
            for (SpirvReflection.Sampler sampler : module.samplers()) {
                Uniform uniform = new Uniform(location++, sampler.name(), true, 0, 4,
                        sampler.set(), sampler.binding());
                existing.uniforms.add(uniform);
                existing.uniformsByName.put(uniform.name(), uniform);
            }
            for (SpirvReflection.Block block : module.blocks()) {
                existing.blockSet = block.set();
                existing.blockBinding = block.binding();
                blockSize = Math.max(blockSize, block.size());
                for (SpirvReflection.Member member : block.members()) {
                    Uniform uniform = new Uniform(location++, member.name(), false, member.offset(),
                            member.size(), block.set(), block.binding());
                    existing.uniforms.add(uniform);
                    existing.uniformsByName.put(uniform.name(), uniform);
                }
            }
        }
        existing.blockBytes = new byte[blockSize];
        existing.linked = true;
    }

    public static int programStatus(int program, int parameter) {
        Program existing = PROGRAMS.get(program);
        if (existing == null) {
            return 0;
        }
        return parameter == LINK_STATUS ? (existing.linked() ? 1 : 0) : 0;
    }

    public static String programLog(int program) {
        Program existing = PROGRAMS.get(program);
        return existing == null ? "" : existing.log();
    }

    public static void deleteProgram(int program) {
        PROGRAMS.remove(program);
        if (currentProgram == program) {
            currentProgram = 0;
        }
    }

    /** {@code glUseProgram}. Recorded; the backend draws with it. */
    public static void useProgram(int program) {
        currentProgram = program == 0 || PROGRAMS.containsKey(program) ? program : 0;
    }

    public static int currentProgram() {
        return currentProgram;
    }

    /** The program a handle refers to, for the backend. Null when it does not exist or is unlinked. */
    public static Program program(int program) {
        Program existing = PROGRAMS.get(program);
        return existing != null && existing.linked ? existing : null;
    }

    /**
     * {@code glGetUniformLocation}: -1 when the program has no such name, exactly as GL does - the game checks for
     * that and skips the uniform.
     */
    public static int uniformLocation(int program, CharSequence name) {
        Program existing = PROGRAMS.get(program);
        if (existing == null || name == null) {
            return -1;
        }
        Uniform uniform = existing.uniformsByName.get(name.toString());
        return uniform == null ? -1 : uniform.location();
    }

    // region uniform writes -----------------------------------------------------

    /** {@code glUniform1i}: for a sampler this is the texture unit index; for anything else, four bytes. */
    public static void uniform1i(int location, int value) {
        writeInt(location, 0, value);
    }

    public static void uniform1f(int location, float value) {
        writeFloat(location, 0, value);
    }

    public static void uniform2f(int location, float x, float y) {
        writeFloat(location, 0, x);
        writeFloat(location, 4, y);
    }

    public static void uniform3f(int location, float x, float y, float z) {
        writeFloat(location, 0, x);
        writeFloat(location, 4, y);
        writeFloat(location, 8, z);
    }

    public static void uniform4f(int location, float x, float y, float z, float w) {
        writeFloat(location, 0, x);
        writeFloat(location, 4, y);
        writeFloat(location, 8, z);
        writeFloat(location, 12, w);
    }

    public static void uniform1(int location, FloatBuffer values) {
        writeFloats(location, values, 1);
    }

    public static void uniform2(int location, FloatBuffer values) {
        writeFloats(location, values, 2);
    }

    public static void uniform3(int location, FloatBuffer values) {
        writeFloats(location, values, 3);
    }

    public static void uniform4(int location, FloatBuffer values) {
        writeFloats(location, values, 4);
    }

    /** {@code glUniformMatrix4}: column-major, and GL's transpose flag is honoured rather than ignored. */
    public static void uniformMatrix4(int location, boolean transpose, FloatBuffer matrices) {
        Uniform uniform = uniformAt(location);
        if (uniform == null || matrices == null) {
            return;
        }
        float[] values = new float[16];
        FloatBuffer view = matrices.duplicate();
        view.rewind();
        if (view.remaining() < 16) {
            return;
        }
        view.get(values);
        if (transpose) {
            values = transpose4(values);
        }
        ByteBuffer target = ByteBuffer.wrap(currentProgramOf(location).blockBytes())
                .order(java.nio.ByteOrder.nativeOrder());
        int base = uniform.offset();
        for (int i = 0; i < 16 && base + i * 4 + 4 <= target.capacity(); i++) {
            target.putFloat(base + i * 4, values[i]);
        }
    }

    private static float[] transpose4(float[] m) {
        float[] out = new float[16];
        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < 4; column++) {
                out[column * 4 + row] = m[row * 4 + column];
            }
        }
        return out;
    }

    private static void writeInt(int location, int offset, int value) {
        Uniform uniform = uniformAt(location);
        if (uniform == null) {
            return;
        }
        Program program = programOf(location);
        if (program == null || uniform.offset() + offset + 4 > program.blockBytes().length) {
            return;
        }
        ByteBuffer.wrap(program.blockBytes()).order(java.nio.ByteOrder.nativeOrder())
                .putInt(uniform.offset() + offset, value);
    }

    private static void writeFloat(int location, int offset, float value) {
        Uniform uniform = uniformAt(location);
        if (uniform == null) {
            return;
        }
        Program program = programOf(location);
        if (program == null || uniform.offset() + offset + 4 > program.blockBytes().length) {
            return;
        }
        ByteBuffer.wrap(program.blockBytes()).order(java.nio.ByteOrder.nativeOrder())
                .putFloat(uniform.offset() + offset, value);
    }

    private static void writeFloats(int location, FloatBuffer values, int count) {
        Uniform uniform = uniformAt(location);
        if (uniform == null || values == null) {
            return;
        }
        Program program = programOf(location);
        if (program == null) {
            return;
        }
        FloatBuffer view = values.duplicate();
        view.rewind();
        ByteBuffer target = ByteBuffer.wrap(program.blockBytes()).order(java.nio.ByteOrder.nativeOrder());
        int base = uniform.offset();
        for (int i = 0; i < count && i < view.remaining(); i++) {
            if (base + i * 4 + 4 > target.capacity()) {
                break;
            }
            target.putFloat(base + i * 4, view.get(i));
        }
    }

    /** The program whose uniform table holds {@code location}. Locations are per program, so this is a lookup. */
    private static Program programOf(int location) {
        for (Program program : PROGRAMS.values()) {
            for (Uniform uniform : program.uniforms) {
                if (uniform.location() == location) {
                    return program;
                }
            }
        }
        return null;
    }

    private static Program currentProgramOf(int location) {
        return programOf(location);
    }

    private static Uniform uniformAt(int location) {
        for (Program program : PROGRAMS.values()) {
            for (Uniform uniform : program.uniforms) {
                if (uniform.location() == location) {
                    return uniform;
                }
            }
        }
        return null;
    }

    // endregion

    /** Test and backend support: drops everything, as a context loss would. */
    public static void reset() {
        SHADERS.clear();
        PROGRAMS.clear();
        currentProgram = 0;
    }
}
