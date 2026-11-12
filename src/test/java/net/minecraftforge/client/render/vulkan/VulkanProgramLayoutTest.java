package net.minecraftforge.client.render.vulkan;

import net.minecraftforge.client.render.shader.GlShaders;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The descriptor set layout and vertex input a program implies, derived without a device.
 *
 * <p><b>Why this is worth testing off the GPU.</b> The two failures that matter here are silent: a binding written
 * into the wrong set binds a texture where a matrix should be, and a vertex input the stream cannot feed is filled
 * with zeroes rather than refused. Neither throws, and both produce a picture that is wrong in a way that looks like a
 * maths bug. What decides them is the program's own decorations, so the decision is a pure function of a linked
 * program - and that is what these assertions pin down.</p>
 *
 * <p><b>Why the game's own pass is the main case.</b> {@code sobel.vsh} is the first stage of the entity-outline chain
 * and it is hand-written for Vulkan, so it states the convention the backend has to honour: block in set 0, samplers
 * in set 1, one vertex attribute at location 0.</p>
 */
class VulkanProgramLayoutTest {

    @BeforeEach
    void resetTheFacade() {
        GlShaders.reset();
    }

    @Test
    void theGameOutlinePassBecomesABlockSetAndASamplerSet() {
        GlShaders.Program program = link(module("sobel.vsh"), module("entity_sobel.fsh"));

        assertArrayEquals(new int[] {0}, program.vertexInputLocations(),
                "sobel.vsh reads one attribute: the quad's corner, at location 0");
        assertTrue(program.hasBlock(), "the vertex stage declares the pass's uniforms");
        assertEquals(0, program.blockSet());
        assertEquals(0, program.blockBinding());
        assertEquals(1, program.samplerUniforms().size());
        assertEquals("DiffuseSampler", program.samplerUniforms().get(0).name());
        assertEquals(1, program.samplerUniforms().get(0).descriptorSet(), "samplers live in set 1");

        List<List<VulkanProgram.Binding>> sets = VulkanProgram.describe(program);
        assertEquals(2, sets.size(), "set 0 is the block and set 1 the sampler");
        assertEquals(1, sets.get(0).size());
        assertTrue(sets.get(0).get(0).uniformBlock(), "the block is what set 0 binds");
        assertEquals(0, sets.get(0).get(0).binding());
        assertEquals(1, sets.get(1).size());
        assertEquals("DiffuseSampler", sets.get(1).get(0).name());
        assertEquals(0, sets.get(1).get(0).binding());
    }

    @Test
    void aShaderThatNeverAsksForABindingGetsOneThatFollowsItsBlock() {
        // What a mod's own GLSL becomes: the preprocessor collects the plain uniforms into a block with binding 0, and
        // shaderc numbers everything else it finds. The layout has to be read back from that, not assumed, because the
        // sampler lands in set 0 beside the block rather than in a set of its own.
        GlShaders.Program program = link("""
                #version 120
                void main() { gl_Position = vec4(0.0); }
                """, """
                #version 120
                uniform float Strength;
                uniform sampler2D DiffuseSampler;
                void main() { gl_FragColor = texture2D(DiffuseSampler, vec2(0.5)) * Strength; }
                """);

        List<List<VulkanProgram.Binding>> sets = VulkanProgram.describe(program);
        assertEquals(1, sets.size(), "an auto-bound program uses one set");
        assertEquals(2, sets.get(0).size(), "the block and the sampler share it");
        assertEquals(0, program.samplerUniforms().get(0).descriptorSet(),
                "an auto-bound sampler stays in the set its block is in: " + program.samplerUniforms());
        assertEquals(1, program.samplerUniforms().get(0).binding(),
                "and takes the binding after the block's, which the block keeps");
        assertTrue(sets.get(0).stream().anyMatch(VulkanProgram.Binding::uniformBlock));
        assertTrue(sets.get(0).stream().noneMatch(binding -> binding.binding() == 0 && !binding.uniformBlock()),
                "the block keeps binding 0, so the sampler has to be somewhere else");
    }

    @Test
    void aGapBetweenSetIndexesIsRefusedByName() {
        // Sets are bound as a range, so a program that uses set 2 but not set 1 cannot be bound at all. Saying so here
        // names the program and the set; letting the driver find it reports a binding mismatch with no program in it.
        GlShaders.Program program = link("""
                #version 120
                void main() { gl_Position = vec4(0.0); }
                """, """
                #version 450
                uniform float Strength;
                layout(set = 2, binding = 0) uniform sampler2D DiffuseSampler;
                layout(location = 0) out vec4 fragColor;
                void main() { fragColor = texture(DiffuseSampler, vec2(0.5)) * Strength; }
                """);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> VulkanProgram.describe(program));
        assertTrue(error.getMessage().contains("set 1"), error.getMessage());
        assertTrue(error.getMessage().contains(String.valueOf(program.id())), error.getMessage());
    }

    @Test
    void aVertexInputTheStreamCannotFeedIsRefusedByName() {
        // Location 4 has no attribute in the normalised layout: position, colour, uv and lightmap are 0..3. A shader
        // asking for more would be handed zeroes, which looks like a mesh collapsing onto one axis.
        GlShaders.Program program = link("""
                #version 450
                layout(location = 4) in vec3 Something;
                void main() { gl_Position = vec4(Something, 1.0); }
                """, """
                #version 450
                layout(location = 0) out vec4 fragColor;
                void main() { fragColor = vec4(1.0); }
                """);

        assertArrayEquals(new int[] {4}, program.vertexInputLocations());
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> VulkanProgram.validateVertexInputs(program));
        assertTrue(error.getMessage().contains("location 4"), error.getMessage());
        assertTrue(error.getMessage().contains(String.valueOf(program.id())), error.getMessage());
    }

    @Test
    void aProgramWithoutDescriptorsDescribesNoSets() {
        GlShaders.Program program = link("""
                #version 450
                layout(location = 0) in vec4 Position;
                void main() { gl_Position = Position; }
                """, """
                #version 450
                layout(location = 0) out vec4 fragColor;
                void main() { fragColor = vec4(1.0); }
                """);

        assertEquals(List.of(), VulkanProgram.describe(program),
                "nothing to bind, so nothing to bind: an empty layout would still have to be allocated");
        assertTrue(VulkanProgram.describe(program).isEmpty());
    }

    private static GlShaders.Program link(String vertexSource, String fragmentSource) {
        int program = GlShaders.createProgram();
        GlShaders.attachShader(program, shader(GlShaders.VERTEX_SHADER, vertexSource));
        GlShaders.attachShader(program, shader(GlShaders.FRAGMENT_SHADER, fragmentSource));
        GlShaders.linkProgram(program);
        assertEquals(1, GlShaders.programStatus(program, GlShaders.LINK_STATUS),
                "the program links: " + GlShaders.programLog(program));
        GlShaders.Program linked = GlShaders.program(program);
        assertNotNull(linked);
        return linked;
    }

    private static GlShaders.Program link(byte[] vertexModule, byte[] fragmentModule) {
        int program = GlShaders.createProgram();
        int vertex = GlShaders.createShader(GlShaders.VERTEX_SHADER);
        int fragment = GlShaders.createShader(GlShaders.FRAGMENT_SHADER);
        GlShaders.attachModule(vertex, vertexModule);
        GlShaders.attachModule(fragment, fragmentModule);
        GlShaders.attachShader(program, vertex);
        GlShaders.attachShader(program, fragment);
        GlShaders.linkProgram(program);
        assertEquals(1, GlShaders.programStatus(program, GlShaders.LINK_STATUS),
                "the program links: " + GlShaders.programLog(program));
        GlShaders.Program linked = GlShaders.program(program);
        assertNotNull(linked);
        return linked;
    }

    private static int shader(int type, String source) {
        int id = GlShaders.createShader(type);
        GlShaders.shaderSource(id, source);
        GlShaders.compileShader(id);
        assertEquals(1, GlShaders.shaderStatus(id, GlShaders.COMPILE_STATUS),
                "the stage compiles: " + GlShaders.shaderLog(id));
        return id;
    }

    private static byte[] module(String name) {
        String resource = "/assets/minecraft/shaders/program/" + name + ".spv";
        try (InputStream in = VulkanProgramLayoutTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "the build ships " + resource);
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("failed to read " + resource, e);
        }
    }
}
