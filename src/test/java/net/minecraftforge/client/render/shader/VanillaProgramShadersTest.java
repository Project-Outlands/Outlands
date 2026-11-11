package net.minecraftforge.client.render.shader;

import net.minecraftforge.client.render.vulkan.shader.SpirvReflection;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The game's own shader chain for the entity outline - the one a spectral arrow makes visible.
 *
 * <p><b>Why this chain has its own test.</b> It is the first feature that needs the whole shader path at once: four
 * program passes ({@code outline}, two {@code blur} directions, {@code blit}), each a vertex and a fragment stage,
 * driven by {@code shaders/post/entity_outline.json}. It is also the chain whose sources are hand-written rather than
 * run through the generic rewriter, so what is asserted here is the contract between those sources and the game:
 * the uniform and sampler names the game looks up, and the interface the vertex and fragment stages have to agree
 * on.</p>
 *
 * <p><b>Why the interface deserves its own assertion.</b> GL links a {@code varying} by name; SPIR-V links by
 * location. Two modules can therefore agree on the name, disagree on the location, and produce a shader that runs and
 * reads garbage. Nothing throws in that case - the outline is simply wrong - which is exactly the kind of failure a
 * test has to hold down.</p>
 */
class VanillaProgramShadersTest {

    /** File name to stage, for the five stages the chain uses. */
    private static final String[] OUTLINE_CHAIN = {
            "sobel.vsh", "outline.fsh", "blur.fsh", "blit.vsh", "blit.fsh"
    };

    @Test
    void everyStageIsShippedAsAUsableModule() {
        for (String name : OUTLINE_CHAIN) {
            byte[] module = module(name);
            int glType = name.endsWith(".vsh") ? GlShaders.VERTEX_SHADER : GlShaders.FRAGMENT_SHADER;
            assertNull(SpirvResources.validate(module, glType),
                    name + " is a module of its own stage, got " + SpirvResources.validate(module, glType));
        }
    }

    @Test
    void theUniformsTheGameAsksForByNameAreAllThere() {
        // The names come from the game's shaders/program/{outline,blur,blit}.json, which is what ShaderManager reads
        // and then looks up with glGetUniformLocation. A member that is renamed or dropped here is a uniform the game
        // sets and nothing reads.
        assertUniforms("sobel.vsh", "ProjMat", "InSize", "OutSize");
        assertUniforms("blur.fsh", "ProjMat", "InSize", "OutSize", "BlurDir", "Radius");
        assertUniforms("blit.vsh", "ProjMat", "OutSize");
        assertUniforms("blit.fsh", "ProjMat", "OutSize", "ColorModulate");

        // outline.fsh reads only the sampler, so it declares no block at all. A block is per stage, not per program.
        assertTrue(SpirvReflection.reflect(module("outline.fsh")).blocks().isEmpty(),
                "outline.fsh has no uniform of its own");
    }

    @Test
    void samplersAreInTheirOwnDescriptorSet() {
        // The convention the hand-written sources follow, and the one the backend will build layouts from: set 0 is
        // the program's uniform block, set 1 is the textures. Keeping them apart is what lets a program bind one
        // uniform buffer and any number of samplers without the two lists colliding.
        for (String name : new String[] {"outline.fsh", "blur.fsh", "blit.fsh"}) {
            SpirvReflection.Sampler sampler = SpirvReflection.reflect(module(name)).sampler("DiffuseSampler");
            assertNotNull(sampler, name + " reads the previous pass through DiffuseSampler");
            assertEquals(1, sampler.set(), name + ": samplers live in set 1");
            assertEquals(0, sampler.binding(), name + ": DiffuseSampler is the first sampler");
        }
        for (String name : new String[] {"sobel.vsh", "blit.vsh"}) {
            assertTrue(SpirvReflection.reflect(module(name)).samplers().isEmpty(),
                    name + " samples nothing");
        }
    }

    @Test
    void theUniformBlockSitsInSetZero() {
        for (String name : new String[] {"sobel.vsh", "blur.fsh", "blit.vsh", "blit.fsh"}) {
            SpirvReflection.Block block = SpirvReflection.reflect(module(name)).blocks().get(0);
            assertEquals(0, block.set(), name + ": the uniform block lives in set 0");
            assertEquals(0, block.binding(), name + ": and at binding 0");
        }
    }

    @Test
    void theVertexAndFragmentStagesOfEachPassAgreeOnTheirInterface() {
        // The three passes the chain runs: outline and both blur directions share sobel.vsh, and the final blit has
        // its own pair. Every fragment input has to be a vertex output with the same name at the same location.
        assertInterfaceMatches("sobel.vsh", "outline.fsh");
        assertInterfaceMatches("sobel.vsh", "blur.fsh");
        assertInterfaceMatches("blit.vsh", "blit.fsh");
    }

    @Test
    void theStagesOfAPassAgreeOnTheOffsetsOfTheBlockMembersTheyShare() {
        // A pass declares its block in both stages, and the members are laid out in declaration order. The fragment
        // appends its own members after the vertex's, so the shared ones keep their offsets - that is the rule the
        // sources state, and it is what the facade's single uniform table per program relies on.
        assertSharedOffsetsMatch("sobel.vsh", "blur.fsh");
        assertSharedOffsetsMatch("blit.vsh", "blit.fsh");
    }

    private static void assertUniforms(String name, String... expected) {
        SpirvReflection.Module module = SpirvReflection.reflect(module(name));
        List<String> missing = new ArrayList<>();
        for (String member : expected) {
            if (module.blockContaining(member) == null) {
                missing.add(member);
            }
        }
        assertTrue(missing.isEmpty(), name + " is missing " + missing + ", it declares "
                + module.blocks().stream().flatMap(block -> block.members().stream()
                        .map(SpirvReflection.Member::name)).toList());
    }

    private static void assertInterfaceMatches(String vertexName, String fragmentName) {
        List<SpirvReflection.StageVariable> outputs = SpirvReflection.stageInterface(module(vertexName)).stream()
                .filter(SpirvReflection.StageVariable::output).toList();
        List<SpirvReflection.StageVariable> inputs = SpirvReflection.stageInterface(module(fragmentName)).stream()
                .filter(variable -> !variable.output()).toList();
        assertTrue(!inputs.isEmpty(), fragmentName + " declares its inputs");

        for (SpirvReflection.StageVariable input : inputs) {
            SpirvReflection.StageVariable match = null;
            for (SpirvReflection.StageVariable output : outputs) {
                if (output.name().equals(input.name())) {
                    match = output;
                    break;
                }
            }
            assertNotNull(match, fragmentName + " reads " + input.name() + ", which " + vertexName + " writes as "
                    + outputs.stream().map(SpirvReflection.StageVariable::name).toList());
            assertEquals(match.location(), input.location(),
                    fragmentName + " reads " + input.name() + " at location " + input.location() + ", but "
                            + vertexName + " writes it at " + match.location());
        }
    }

    private static void assertSharedOffsetsMatch(String vertexName, String fragmentName) {
        SpirvReflection.Module vertex = SpirvReflection.reflect(module(vertexName));
        SpirvReflection.Module fragment = SpirvReflection.reflect(module(fragmentName));
        for (SpirvReflection.Member member : vertex.blocks().get(0).members()) {
            SpirvReflection.Block inFragment = fragment.blockContaining(member.name());
            if (inFragment == null) {
                continue;
            }
            assertEquals(member.offset(), inFragment.offsetOf(member.name()),
                    member.name() + " is at " + member.offset() + " in " + vertexName + " but at "
                            + inFragment.offsetOf(member.name()) + " in " + fragmentName);
        }
    }

    private static byte[] module(String name) {
        String resource = "/assets/minecraft/shaders/program/" + name + ".spv";
        try (InputStream in = VanillaProgramShadersTest.class.getResourceAsStream(resource)) {
            assertNotNull(in, "the build ships " + resource);
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("failed to read " + resource, e);
        }
    }
}
