package net.minecraftforge.client.render.vulkan;

import net.minecraftforge.client.render.shader.GlShaders;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC;
import static org.lwjgl.vulkan.VK10.VK_NULL_HANDLE;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_VERTEX_BIT;
import static org.lwjgl.vulkan.VK10.vkCreateDescriptorSetLayout;
import static org.lwjgl.vulkan.VK10.vkCreatePipelineLayout;
import static org.lwjgl.vulkan.VK10.vkDestroyDescriptorSetLayout;
import static org.lwjgl.vulkan.VK10.vkDestroyPipelineLayout;

/**
 * A shader program the game linked, as Vulkan needs it: modules, descriptor set layouts, a pipeline layout and the
 * pipelines built from them.
 *
 * <p><b>Why the layout is derived from the module instead of agreed on.</b> Every {@code ShaderManager} program - and
 * every mod shader that arrives as GLSL - is reflected into a uniform block plus a set of samplers, and the bindings
 * come from the module's own decorations. This class reads them back rather than imposing a convention, so a shader
 * written against set 1 works and so does one whose samplers were auto-bound into set 0 beside its block. What is
 * imposed is only what Vulkan requires anyway: one dynamic uniform buffer for the block, one descriptor set per set
 * index, and no gaps in those indexes.</p>
 *
 * <p><b>Why the block is a dynamic uniform buffer.</b> The block belongs to the program, but its contents belong to
 * the draw: a second blur pass sets a different {@code BlurDir} and both draws are in the same frame. So each draw's
 * bytes go into its own slot of a per-frame arena and are selected with a dynamic offset, which is the cheapest thing
 * that stays correct without a descriptor set per draw.</p>
 *
 * <p><b>Pipelines are cached per draw state.</b> The same program is used for a blur in both directions with different
 * blending, and a pipeline is the one object here that is expensive to build. The key is the same
 * {@link VulkanPipeline.PipelineState} the stream path uses, so the two cannot disagree about what a pipeline is.</p>
 */
final class VulkanProgram implements AutoCloseable {

    /** One binding: where it lives, what kind it is, and the name it serves. */
    record Binding(int set, int binding, boolean uniformBlock, String name) {}

    /** The samplers a program binds, in the order a descriptor set writes them. */
    private final List<GlShaders.Uniform> samplers;

    private final VkDevice device;
    private final int programId;
    /** The modules this was built from, so a re-linked program is noticed rather than drawn with the old stages. */
    private final byte[] vertexBytes;
    private final byte[] fragmentBytes;
    private final VulkanShader vertex;
    private final VulkanShader fragment;
    private final long[] setLayouts;
    private final long pipelineLayout;
    private final int blockSet;
    private final int blockBinding;
    private final int blockSize;
    /** The vertex attributes this program's vertex stage reads, which is what its pipelines declare. */
    private final int[] vertexInputs;
    private final Map<VulkanPipeline.PipelineState, VulkanPipeline> pipelines = new HashMap<>();

    private VulkanProgram(VkDevice device, GlShaders.Program source, byte[] vertexBytes, byte[] fragmentBytes,
                          VulkanShader vertex, VulkanShader fragment, long[] setLayouts, long pipelineLayout) {
        this.device = device;
        this.programId = source.id();
        this.vertexBytes = vertexBytes;
        this.fragmentBytes = fragmentBytes;
        this.vertex = vertex;
        this.fragment = fragment;
        this.setLayouts = setLayouts;
        this.pipelineLayout = pipelineLayout;
        this.blockSet = source.blockSet();
        this.blockBinding = source.blockBinding();
        this.blockSize = source.blockSize();
        this.samplers = source.samplerUniforms();
        this.vertexInputs = source.vertexInputLocations();
    }

    /**
     * Builds the device objects for a linked program.
     *
     * @throws IllegalStateException when the program reads a vertex input the normalised stream has no attribute for,
     *                               which is a shader the backend cannot feed rather than one that happens to be wrong
     */
    static VulkanProgram create(VkDevice device, GlShaders.Program source) {
        byte[] vertexBytes = source.stageModule(GlShaders.VERTEX_SHADER);
        byte[] fragmentBytes = source.stageModule(GlShaders.FRAGMENT_SHADER);
        if (vertexBytes == null || fragmentBytes == null) {
            throw new IllegalStateException("program " + source.id() + " is missing a stage: "
                    + (vertexBytes == null ? "no vertex stage " : "") + (fragmentBytes == null ? "no fragment stage" : ""));
        }
        validateVertexInputs(source);

        VulkanShader vertex = VulkanShader.create(device, vertexBytes, "program " + source.id() + " vertex");
        VulkanShader fragment;
        try {
            fragment = VulkanShader.create(device, fragmentBytes, "program " + source.id() + " fragment");
        } catch (RuntimeException e) {
            vertex.close();
            throw e;
        }

        List<List<Binding>> sets = describe(source);
        long[] setLayouts = new long[sets.size()];
        long pipelineLayout = VK_NULL_HANDLE;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (int set = 0; set < sets.size(); set++) {
                setLayouts[set] = createSetLayout(device, sets.get(set), stack);
            }
            VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType$Default()
                    .pSetLayouts(stack.longs(setLayouts));
            LongBuffer layoutOut = stack.mallocLong(1);
            VkCheck.check(vkCreatePipelineLayout(device, layoutInfo, null, layoutOut),
                    "vkCreatePipelineLayout(program " + source.id() + ")");
            pipelineLayout = layoutOut.get(0);
        } catch (RuntimeException e) {
            for (long layout : setLayouts) {
                if (layout != 0L) {
                    vkDestroyDescriptorSetLayout(device, layout, null);
                }
            }
            vertex.close();
            fragment.close();
            throw e;
        }
        return new VulkanProgram(device, source, vertexBytes, fragmentBytes, vertex, fragment, setLayouts,
                pipelineLayout);
    }

    /**
     * Checks that the program's vertex inputs are ones the normalised stream can feed.
     *
     * <p>Separate from {@link #create} so it can be asserted without a device: this is the rule that decides whether a
     * shader is drawable at all, and the failure it prevents is a silently wrong mesh rather than an error - a
     * location the stream has no attribute for is filled with zeroes by the driver, not rejected.</p>
     *
     * @throws IllegalStateException naming the location and the program
     */
    static void validateVertexInputs(GlShaders.Program source) {
        for (int location : source.vertexInputLocations()) {
            if (location < 0 || location > VulkanStream.MAX_ATTRIBUTE_LOCATION) {
                throw new IllegalStateException("program " + source.id() + " reads vertex attribute location "
                        + location + ", but the normalised vertex stream only provides 0.."
                        + VulkanStream.MAX_ATTRIBUTE_LOCATION + " (position, colour, uv, lightmap). The shader has to"
                        + " take its input from one of those, or from a uniform.");
            }
        }
    }

    /**
     * Groups a program's bindings by descriptor set index.
     *
     * <p>Pure on purpose: what the layout is meant to be is worth asserting without a device, and the interesting
     * failures here - a gap in the set indexes, a block and a sampler on one binding - are all decided by this
     * function rather than by the driver.</p>
     *
     * @return one entry per set, from 0 to the highest set the program uses; empty when it uses no descriptors
     */
    static List<List<Binding>> describe(GlShaders.Program source) {
        List<Binding> bindings = new ArrayList<>();
        if (source.hasBlock()) {
            bindings.add(new Binding(source.blockSet(), source.blockBinding(), true, "<uniform block>"));
        }
        for (GlShaders.Uniform sampler : source.samplerUniforms()) {
            bindings.add(new Binding(sampler.descriptorSet(), sampler.binding(), false, sampler.name()));
        }
        if (bindings.isEmpty()) {
            return List.of();
        }

        int highest = 0;
        for (Binding binding : bindings) {
            highest = Math.max(highest, binding.set());
        }
        List<List<Binding>> sets = new ArrayList<>();
        for (int set = 0; set <= highest; set++) {
            sets.add(new ArrayList<>());
        }
        for (Binding binding : bindings) {
            if (binding.set() < 0) {
                throw new IllegalStateException("program " + source.id() + " has " + binding.name()
                        + " at negative set " + binding.set());
            }
            sets.get(binding.set()).add(binding);
        }

        for (int set = 0; set < highest; set++) {
            if (sets.get(set).isEmpty()) {
                // Vulkan binds sets as a range, so a hole cannot be skipped: it would need an empty layout and an
                // allocated set that exists only to be bound. Nothing produces this, and failing here says which
                // program and which set rather than letting the driver report a broken binding later.
                throw new IllegalStateException("program " + source.id() + " has no binding in set " + set
                        + " but uses set " + highest + "; descriptor set indexes have to be contiguous");
            }
        }
        for (List<Binding> set : sets) {
            for (int i = 0; i < set.size(); i++) {
                for (int j = i + 1; j < set.size(); j++) {
                    if (set.get(i).binding() == set.get(j).binding()) {
                        throw new IllegalStateException("program " + source.id() + " puts " + set.get(i).name()
                                + " and " + set.get(j).name() + " both at set " + set.get(i).set() + " binding "
                                + set.get(i).binding());
                    }
                }
            }
        }
        return sets;
    }

    private static long createSetLayout(VkDevice device, List<Binding> bindings, MemoryStack stack) {
        VkDescriptorSetLayoutBinding.Buffer descriptors = VkDescriptorSetLayoutBinding.calloc(bindings.size(), stack);
        for (int i = 0; i < bindings.size(); i++) {
            Binding binding = bindings.get(i);
            descriptors.get(i)
                    .binding(binding.binding())
                    .descriptorType(binding.uniformBlock()
                            ? VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC
                            : VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .descriptorCount(1)
                    // Both stages: a program may read its block (or a sampler) in either one, and the reflection does
                    // not say which - the block in the game's own shaders is declared by both stages.
                    .stageFlags(VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT);
        }
        VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                .sType$Default()
                .pBindings(descriptors);
        LongBuffer layoutOut = stack.mallocLong(1);
        VkCheck.check(vkCreateDescriptorSetLayout(device, layoutInfo, null, layoutOut),
                "vkCreateDescriptorSetLayout(program)");
        return layoutOut.get(0);
    }

    int programId() {
        return programId;
    }

    List<GlShaders.Uniform> samplers() {
        return samplers;
    }

    long[] setLayouts() {
        return setLayouts;
    }

    long pipelineLayout() {
        return pipelineLayout;
    }

    int blockSet() {
        return blockSet;
    }

    int blockBinding() {
        return blockBinding;
    }

    int blockSize() {
        return blockSize;
    }

    /** Whether this was built from the same modules the program has now. */
    boolean matches(GlShaders.Program source) {
        return source.id() == programId
                && java.util.Arrays.equals(source.stageModule(GlShaders.VERTEX_SHADER), vertexBytes)
                && java.util.Arrays.equals(source.stageModule(GlShaders.FRAGMENT_SHADER), fragmentBytes);
    }

    VulkanPipeline pipeline(VulkanPipeline.PipelineState state, int colorFormat, int depthFormat) {
        VulkanPipeline existing = pipelines.get(state);
        if (existing != null) {
            return existing;
        }
        VulkanPipeline created = VulkanPipeline.create(device, vertex, fragment, colorFormat, depthFormat,
                pipelineLayout, VK_NULL_HANDLE, state, vertexInputs);
        pipelines.put(state, created);
        return created;
    }

    @Override
    public void close() {
        for (VulkanPipeline pipeline : pipelines.values()) {
            pipeline.close();
        }
        pipelines.clear();
        vkDestroyPipelineLayout(device, pipelineLayout, null);
        for (long layout : setLayouts) {
            vkDestroyDescriptorSetLayout(device, layout, null);
        }
        vertex.close();
        fragment.close();
    }
}
