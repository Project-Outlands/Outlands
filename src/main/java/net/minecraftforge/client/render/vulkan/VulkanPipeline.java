package net.minecraftforge.client.render.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDepthStencilStateCreateInfo;
import org.lwjgl.vulkan.VkStencilOpState;
import org.lwjgl.vulkan.VkPipelineDynamicStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineInputAssemblyStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRenderingCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPipelineVertexInputStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;
import org.lwjgl.vulkan.VkVertexInputAttributeDescription;
import org.lwjgl.vulkan.VkVertexInputBindingDescription;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_DST_ALPHA;
import static org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_DST_COLOR;
import static org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_ONE;
import static org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA;
import static org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR;
import static org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
import static org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR;
import static org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_SRC_ALPHA;
import static org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_SRC_ALPHA_SATURATE;
import static org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_SRC_COLOR;
import static org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_ZERO;
import static org.lwjgl.vulkan.VK10.VK_BLEND_OP_ADD;
import static org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_A_BIT;
import static org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_B_BIT;
import static org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_G_BIT;
import static org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_R_BIT;
import static org.lwjgl.vulkan.VK10.VK_COMPARE_OP_LESS_OR_EQUAL;
import static org.lwjgl.vulkan.VK10.VK_CULL_MODE_BACK_BIT;
import static org.lwjgl.vulkan.VK10.VK_DYNAMIC_STATE_LINE_WIDTH;
import static org.lwjgl.vulkan.VK10.VK_DYNAMIC_STATE_SCISSOR;
import static org.lwjgl.vulkan.VK10.VK_DYNAMIC_STATE_VIEWPORT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16B16A16_SINT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R16G16_UNORM;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32B32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R32G32_SFLOAT;
import static org.lwjgl.vulkan.VK10.VK_FORMAT_R8G8B8A8_UNORM;
import static org.lwjgl.vulkan.VK10.VK_FRONT_FACE_COUNTER_CLOCKWISE;
import static org.lwjgl.vulkan.VK10.VK_NULL_HANDLE;
import static org.lwjgl.vulkan.VK10.VK_PIPELINE_BIND_POINT_GRAPHICS;
import static org.lwjgl.vulkan.VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
import static org.lwjgl.vulkan.VK10.VK_SAMPLE_COUNT_1_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_FRAGMENT_BIT;
import static org.lwjgl.vulkan.VK10.VK_SHADER_STAGE_VERTEX_BIT;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.VK_VERTEX_INPUT_RATE_VERTEX;
import static org.lwjgl.vulkan.VK10.vkCreateGraphicsPipelines;
import static org.lwjgl.vulkan.VK10.vkCreatePipelineLayout;
import static org.lwjgl.vulkan.VK10.vkDestroyPipeline;
import static org.lwjgl.vulkan.VK10.vkDestroyPipelineLayout;

/**
 * A graphics pipeline, plus the layout that says what its shaders are handed.
 *
 * <p><b>Fixed state, no dynamic state beyond viewport and scissor.</b> The vertex format is a compile-time
 * constant here because the caller is the loader's own terrain node, not the GL shim. The shim's problem - a new
 * vertex layout on every draw - needs dynamic vertex input or a PSO cache, and neither belongs in this class.</p>
 *
 * <p><b>Push constants, not descriptors.</b> One mat4 is 64 bytes, well inside the 128 bytes every device
 * guarantees, and it removes a descriptor set from the one thing every draw needs.</p>
 *
 * <p><b>Depth test on, back faces culled, counter-clockwise front.</b> With the default Vulkan viewport Y pointing
 * down, a counter-clockwise winding in the vertex data is what ends up facing the camera. Getting this wrong
 * produces an inside-out cube rather than an error, which is why the shader colours faces differently.</p>
 */
final class VulkanPipeline implements AutoCloseable {

    /** Vanilla's {@code DefaultVertexFormats.BLOCK}: 28 bytes, four attributes, no normal. */
    static final int VERTEX_STRIDE = TerrainVertexFormat.RAW_STRIDE;

    /**
     * Maximum push constant size across pipelines: 128 bytes (guaranteed minimum across all Vulkan devices).
     *
     * <p>Terrain pipeline uses 88 bytes (64-byte mat4 MVP, 8-byte BDA pointer, alphaReference, alphaTest, flipU).
     * Stream pipeline uses 76 bytes (64-byte mat4 MVP, alphaReference, alphaTest, flipU).</p>
     */
    static final int PUSH_CONSTANT_BYTES = 128;

    /**
     * The pipeline state that is not dynamic.
     *
     * <p>Everything the stream path can vary per draw and that Vulkan bakes in. The vertex layout is absent on
     * purpose: every draw is converted to the canonical layout first, so the input description is a constant and
     * this key stays small.</p>
     *
     * @param topology   the Vulkan primitive topology, already converted from the caller's GL mode
     * @param depthTest  whether fragments are depth-tested
     * @param depthWrite whether depth is written
     * @param blend      whether blending is enabled
     * @param cull       whether back faces are culled
     * @param srcColor   the GL source blend factor for RGB
     * @param dstColor   the GL destination blend factor for RGB
     * @param srcAlpha   the GL source blend factor for alpha
     * @param dstAlpha   the GL destination blend factor for alpha
     */
    record PipelineState(VK10Topology topology, boolean depthTest, boolean depthWrite, int depthFunc, boolean blend,
                         boolean cull, int srcColor, int dstColor, int srcAlpha, int dstAlpha, int colorWriteMask,
                         boolean flippedWinding, boolean stencilTest, int stencilFunc, int stencilReference,
                         int stencilCompareMask, int stencilWriteMask, int stencilFail, int stencilDepthFail,
                         int stencilPass) {
        /** Every colour channel, which is what a pipeline built without a mask writes. */
        static final int ALL_CHANNELS = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT
                | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;

        /** The state the terrain phase has always used for opaque and cutout geometry. */
        static PipelineState terrain() {
            return new PipelineState(VK10Topology.TRIANGLE_LIST, true, true, 515, false, true,
                    GL_ONE, GL_ZERO, GL_ONE, GL_ZERO, ALL_CHANNELS, false,
                    false, 519, 0, 0xFFFFFFFF, 0xFFFFFFFF, 7680, 7680, 7680);
        }

        /**
         * The state a recorded draw was issued with.
         *
         * <p>Shared by the stream path and the program path, so a program draw and a fixed-function draw that differ
         * only in their shader cannot end up with different blending: the mask and the enable bits are read from the
         * same place, in the same order, by both.</p>
         *
         * @param flipped whether the draw goes into a target whose viewport is inverted, which inverts the winding
         */
        static PipelineState of(VK10Topology topology, net.minecraftforge.client.render.vertex.DrawState state,
                                boolean flipped) {
            boolean[] mask = state.colorMask();
            int channels = (mask[0] ? VK_COLOR_COMPONENT_R_BIT : 0)
                    | (mask[1] ? VK_COLOR_COMPONENT_G_BIT : 0)
                    | (mask[2] ? VK_COLOR_COMPONENT_B_BIT : 0)
                    | (mask[3] ? VK_COLOR_COMPONENT_A_BIT : 0);
            return new PipelineState(topology, state.depthTest(), state.depthWrite(), state.depthFunc(),
                    state.blend(), state.cull(), state.blendSourceRgb(), state.blendDestinationRgb(),
                    state.blendSourceAlpha(), state.blendDestinationAlpha(), channels, flipped,
                    state.stencilTest(), state.stencilComparison(), state.stencilReference(),
                    state.stencilCompareMask(), state.stencilWriteMask(), state.stencilFail(),
                    state.stencilDepthFail(), state.stencilPass());
        }

        /** The state for translucent terrain (water, stained glass): blending enabled, depth write disabled. */
        static PipelineState translucentTerrain() {
            return new PipelineState(VK10Topology.TRIANGLE_LIST, true, false, 515, true, true,
                    770, 771, 1, 0, ALL_CHANNELS, false,
                    false, 519, 0, 0xFFFFFFFF, 0xFFFFFFFF, 7680, 7680, 7680);
        }
    }

    /** The subset of {@code VkPrimitiveTopology} the stream path produces, named so a key can be read. */
    enum VK10Topology {
        POINT_LIST(org.lwjgl.vulkan.VK10.VK_PRIMITIVE_TOPOLOGY_POINT_LIST),
        LINE_LIST(org.lwjgl.vulkan.VK10.VK_PRIMITIVE_TOPOLOGY_LINE_LIST),
        LINE_STRIP(org.lwjgl.vulkan.VK10.VK_PRIMITIVE_TOPOLOGY_LINE_STRIP),
        TRIANGLE_LIST(org.lwjgl.vulkan.VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST),
        TRIANGLE_STRIP(org.lwjgl.vulkan.VK10.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP);

        final int vk;

        VK10Topology(int vk) {
            this.vk = vk;
        }

        /** Maps a backend-neutral topology. */
        static VK10Topology of(net.minecraftforge.client.render.vertex.PrimitiveShape.Topology topology) {
            return switch (topology) {
                case POINT_LIST -> POINT_LIST;
                case LINE_LIST -> LINE_LIST;
                case LINE_STRIP -> LINE_STRIP;
                case TRIANGLE_LIST -> TRIANGLE_LIST;
                case TRIANGLE_STRIP -> TRIANGLE_STRIP;
            };
        }
    }

    private final VkDevice device;
    private final long layout;
    /** Whether this pipeline created the layout and therefore must destroy it. */
    private final boolean ownsLayout;
    private final long pipeline;

    // The GL blend factor constants, spelled here so a PipelineState can name the ones GL's defaults use without
    // importing gl11 into a Vulkan class.
    static final int GL_ZERO = 0;
    static final int GL_ONE = 1;

    private VulkanPipeline(VkDevice device, long layout, boolean ownsLayout, long pipeline) {
        this.device = device;
        this.layout = layout;
        this.ownsLayout = ownsLayout;
        this.pipeline = pipeline;
    }

    long handle() {
        return pipeline;
    }

    long layout() {
        return layout;
    }

    /**
     * Builds a pipeline for the given shaders.
     *
     * @param colorFormat the swapchain image format
     * @param depthFormat the depth attachment format
     */
    static VulkanPipeline create(VkDevice device, VulkanShader vertex, VulkanShader fragment, int colorFormat,
                                 int depthFormat) {
        return create(device, vertex, fragment, colorFormat, depthFormat, VK_NULL_HANDLE, PipelineState.terrain());
    }

    /**
     * Builds a pipeline for a state, with the canonical vertex layout.
     *
     * <p>The pipeline layout is borrowed, not created: it holds the descriptor set layout and the push constant range,
     * and every pipeline in the renderer has to share one instance - a set bound against one layout is incompatible
     * with a pipeline built for another. {@link VulkanDescriptors} owns it.</p>
     *
     * @param descriptorSetLayout the set layout the shader samples through, or {@code VK_NULL_HANDLE} for a shader
     *                            that takes no descriptors
     */
    static VulkanPipeline create(VkDevice device, VulkanShader vertex, VulkanShader fragment, int colorFormat,
                                 int depthFormat, long descriptorSetLayout, PipelineState state) {
        // No descriptors: a layout with no set layouts, which is what a caller that does not sample wants.
        return create(device, vertex, fragment, colorFormat, depthFormat, 0L, descriptorSetLayout, state);
    }

    /**
     * Builds a terrain pipeline with the compact 16-byte vertex layout.
     */
    static VulkanPipeline createTerrain(VkDevice device, VulkanShader vertex, VulkanShader fragment, int colorFormat,
                                        int depthFormat, long pipelineLayout, long descriptorSetLayout, PipelineState state) {
        return create(device, vertex, fragment, colorFormat, depthFormat, pipelineLayout, descriptorSetLayout, state, true);
    }

    /**
     * Builds a pipeline against an existing pipeline layout.
     *
     * @param pipelineLayout the shared layout, or {@code VK_NULL_HANDLE} to create a private one
     */
    static VulkanPipeline create(VkDevice device, VulkanShader vertex, VulkanShader fragment, int colorFormat,
                                 int depthFormat, long pipelineLayout, long descriptorSetLayout, PipelineState state) {
        return create(device, vertex, fragment, colorFormat, depthFormat, pipelineLayout, descriptorSetLayout, state,
                false, null);
    }

    /**
     * Builds a pipeline whose vertex input declares only the attributes {@code locations} names.
     *
     * <p>Needed for a program of the game's own: its vertex stage reads whichever of the four normalised attributes it
     * declares, and a description that also declares the others is not an error but is reported by the validation
     * layers as "not consumed by vertex shader" - twice per program. A program also knows its own list, so passing it
     * in is both quieter and more honest than declaring everything and hoping nobody tests the difference.</p>
     *
     * @param locations the attribute locations the vertex stage reads, ascending, each in 0..3
     */
    static VulkanPipeline create(VkDevice device, VulkanShader vertex, VulkanShader fragment, int colorFormat,
                                 int depthFormat, long pipelineLayout, long descriptorSetLayout, PipelineState state,
                                 int[] locations) {
        return create(device, vertex, fragment, colorFormat, depthFormat, pipelineLayout, descriptorSetLayout, state,
                false, locations);
    }

    private static VulkanPipeline create(VkDevice device, VulkanShader vertex, VulkanShader fragment, int colorFormat,
                                         int depthFormat, long pipelineLayout, long descriptorSetLayout, PipelineState state,
                                         boolean isTerrain) {
        return create(device, vertex, fragment, colorFormat, depthFormat, pipelineLayout, descriptorSetLayout, state,
                isTerrain, null);
    }

    private static VulkanPipeline create(VkDevice device, VulkanShader vertex, VulkanShader fragment, int colorFormat,
                                         int depthFormat, long pipelineLayout, long descriptorSetLayout, PipelineState state,
                                         boolean isTerrain, int[] locations) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            boolean ownsLayout = pipelineLayout == VK_NULL_HANDLE;
            long layout = pipelineLayout;
            if (ownsLayout) {
                VkPushConstantRange.Buffer pushConstants = VkPushConstantRange.calloc(1, stack);
                pushConstants.get(0)
                        .stageFlags(VK_SHADER_STAGE_VERTEX_BIT)
                        .offset(0)
                        .size(16 * Float.BYTES);
                VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                        .sType$Default()
                        .pPushConstantRanges(pushConstants);
                if (descriptorSetLayout != VK_NULL_HANDLE) {
                    layoutInfo.pSetLayouts(stack.longs(descriptorSetLayout));
                }
                LongBuffer layoutOut = stack.mallocLong(1);
                VkCheck.check(vkCreatePipelineLayout(device, layoutInfo, null, layoutOut), "vkCreatePipelineLayout");
                layout = layoutOut.get(0);
            }

            long pipeline = 0L;
            try {
                pipeline = createPipeline(device, layout, vertex, fragment, colorFormat, depthFormat, state, isTerrain,
                        locations, stack);
                return new VulkanPipeline(device, layout, ownsLayout, pipeline);
            } catch (RuntimeException e) {
                if (pipeline != 0L) {
                    vkDestroyPipeline(device, pipeline, null);
                }
                if (ownsLayout) {
                    vkDestroyPipelineLayout(device, layout, null);
                }
                throw e;
            }
        }
    }

    private static long createPipeline(VkDevice device, long layout, VulkanShader vertex, VulkanShader fragment,
                                       int colorFormat, int depthFormat, PipelineState state, boolean isTerrain,
                                       int[] locations, MemoryStack stack) {
        VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
        stages.get(0)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                .stage(VK_SHADER_STAGE_VERTEX_BIT)
                .module(vertex.handle())
                .pName(stack.UTF8("main"));
        stages.get(1)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                .stage(VK_SHADER_STAGE_FRAGMENT_BIT)
                .module(fragment.handle())
                .pName(stack.UTF8("main"));

        VkVertexInputBindingDescription.Buffer binding = VkVertexInputBindingDescription.calloc(1, stack);
        binding.get(0).binding(0).stride(isTerrain ? TerrainVertexFormat.COMPACT_STRIDE : VERTEX_STRIDE)
                .inputRate(VK_VERTEX_INPUT_RATE_VERTEX);

        VkVertexInputAttributeDescription.Buffer attributes;
        if (isTerrain) {
            // Compact 16-byte terrain format:
            // 0: inPosAndLight (3x s16 pos, 2x u8 light) -> R16G16B16A16_SINT (offset 0)
            // 1: inUv (2x u16 unorm) -> R16G16_UNORM (offset 8)
            // 2: inColor (4x u8 unorm) -> R8G8B8A8_UNORM (offset 12)
            attributes = VkVertexInputAttributeDescription.calloc(3, stack);
            attributes.get(0).location(0).binding(0).format(VK_FORMAT_R16G16B16A16_SINT)
                    .offset(TerrainVertexFormat.COMPACT_POSITION_LIGHT_OFFSET);
            attributes.get(1).location(1).binding(0).format(VK_FORMAT_R16G16_UNORM)
                    .offset(TerrainVertexFormat.COMPACT_UV_OFFSET);
            attributes.get(2).location(2).binding(0).format(VK_FORMAT_R8G8B8A8_UNORM)
                    .offset(TerrainVertexFormat.COMPACT_COLOR_OFFSET);
        } else if (locations != null) {
            // Only what this program's vertex stage reads, at the offsets and formats of the normalised stream.
            attributes = VkVertexInputAttributeDescription.calloc(Math.max(1, locations.length), stack);
            attributes.limit(locations.length);
            for (int i = 0; i < locations.length; i++) {
                attributes.get(i).location(locations[i]).binding(0)
                        .format(rawFormat(locations[i])).offset(rawOffset(locations[i]));
            }
        } else {
            // Raw 28-byte format (stream draws)
            attributes = VkVertexInputAttributeDescription.calloc(4, stack);
            attributes.get(0).location(0).binding(0).format(VK_FORMAT_R32G32B32_SFLOAT)
                    .offset(TerrainVertexFormat.RAW_POSITION_OFFSET);
            attributes.get(1).location(1).binding(0).format(VK_FORMAT_R8G8B8A8_UNORM)
                    .offset(TerrainVertexFormat.RAW_COLOR_OFFSET);
            attributes.get(2).location(2).binding(0).format(VK_FORMAT_R32G32_SFLOAT)
                    .offset(TerrainVertexFormat.RAW_UV_OFFSET);
            attributes.get(3).location(3).binding(0).format(VK_FORMAT_R16G16_UNORM)
                    .offset(TerrainVertexFormat.RAW_LIGHTMAP_OFFSET);
        }

        VkPipelineVertexInputStateCreateInfo vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack)
                .sType$Default()
                .pVertexBindingDescriptions(binding)
                .pVertexAttributeDescriptions(attributes);

        VkPipelineInputAssemblyStateCreateInfo inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                .sType$Default()
                .topology(state.topology().vk);

        VkPipelineViewportStateCreateInfo viewportState = VkPipelineViewportStateCreateInfo.calloc(stack)
                .sType$Default()
                .viewportCount(1)
                .scissorCount(1);

        VkPipelineRasterizationStateCreateInfo rasterization = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                .sType$Default()
                .polygonMode(org.lwjgl.vulkan.VK10.VK_POLYGON_MODE_FILL)
                .cullMode(state.cull() ? VK_CULL_MODE_BACK_BIT : org.lwjgl.vulkan.VK10.VK_CULL_MODE_NONE)
                // An offscreen target is drawn with an inverted viewport so that its image ends up in GL's bottom-up
                // order, and inverting y inverts which side of a triangle is counter-clockwise: a quad that was front
                // facing becomes back facing and gets culled away. The front face is part of the pipeline state for
                // that reason - measured, the self-test's marker disappeared entirely while culling was on.
                .frontFace(state.flippedWinding()
                        ? org.lwjgl.vulkan.VK10.VK_FRONT_FACE_CLOCKWISE
                        : VK_FRONT_FACE_COUNTER_CLOCKWISE)
                .lineWidth(1.0f);

        VkPipelineMultisampleStateCreateInfo multisample = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                .sType$Default()
                .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);

        VkPipelineDepthStencilStateCreateInfo depthStencil = VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                .sType$Default()
                .depthTestEnable(state.depthTest())
                .depthWriteEnable(state.depthWrite())
                .depthCompareOp(compareOp(state.depthFunc()))
                .stencilTestEnable(state.stencilTest());
        if (state.stencilTest()) {
            // GL has one stencil state for both faces unless glStencilFuncSeparate was used, which nothing in the
            // game or the surveyed mods calls, so front and back get the same values.
            VkStencilOpState stencil = VkStencilOpState.calloc(stack)
                    .failOp(stencilOp(state.stencilFail()))
                    .passOp(stencilOp(state.stencilPass()))
                    .depthFailOp(stencilOp(state.stencilDepthFail()))
                    .compareOp(stencilCompareOp(state.stencilFunc()))
                    .compareMask(state.stencilCompareMask())
                    .writeMask(state.stencilWriteMask())
                    .reference(state.stencilReference());
            depthStencil.front(stencil).back(stencil);
        }

        VkPipelineColorBlendAttachmentState.Buffer blendAttachment =
                VkPipelineColorBlendAttachmentState.calloc(1, stack);
        blendAttachment.get(0)
                .blendEnable(state.blend())
                .srcColorBlendFactor(blendFactor(state.srcColor()))
                .dstColorBlendFactor(blendFactor(state.dstColor()))
                .colorBlendOp(VK_BLEND_OP_ADD)
                .srcAlphaBlendFactor(blendFactor(state.srcAlpha()))
                .dstAlphaBlendFactor(blendFactor(state.dstAlpha()))
                .alphaBlendOp(VK_BLEND_OP_ADD)
                .colorWriteMask(state.colorWriteMask());

        VkPipelineColorBlendStateCreateInfo colorBlend = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                .sType$Default()
                .pAttachments(blendAttachment);

        VkPipelineDynamicStateCreateInfo dynamicState = VkPipelineDynamicStateCreateInfo.calloc(stack)
                .sType$Default()
                // LINE_WIDTH is dynamic because GL's glLineWidth is: it changes per draw and a pipeline per width
                // would be a pipeline per call. The value still has to be set before the first draw with any
                // pipeline that declares it dynamic, which VulkanStream does.
                .pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR,
                        VK_DYNAMIC_STATE_LINE_WIDTH));

        VkPipelineRenderingCreateInfo rendering = VkPipelineRenderingCreateInfo.calloc(stack)
                .sType$Default()
                .colorAttachmentCount(1)
                .pColorAttachmentFormats(stack.ints(colorFormat))
                .depthAttachmentFormat(depthFormat);

        VkGraphicsPipelineCreateInfo.Buffer pipelineInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack);
        pipelineInfo.get(0)
                .sType$Default()
                .pNext(rendering.address())
                .pStages(stages)
                .pVertexInputState(vertexInput)
                .pInputAssemblyState(inputAssembly)
                .pViewportState(viewportState)
                .pRasterizationState(rasterization)
                .pMultisampleState(multisample)
                .pDepthStencilState(depthStencil)
                .pColorBlendState(colorBlend)
                .pDynamicState(dynamicState)
                .layout(layout)
                .renderPass(VK_NULL_HANDLE);

        LongBuffer pipelineOut = stack.mallocLong(1);
        IntBuffer result = stack.mallocInt(1);
        int status = vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, pipelineInfo, null, pipelineOut);
        result.put(0, status);
        if (status != VK_SUCCESS) {
            throw new VkCheck.VulkanException("vkCreateGraphicsPipelines returned " + status);
        }
        return pipelineOut.get(0);
    }

    /** The Vulkan format of the normalised stream's attribute at {@code location}. */
    private static int rawFormat(int location) {
        return switch (location) {
            case 0 -> VK_FORMAT_R32G32B32_SFLOAT;
            case 1 -> VK_FORMAT_R8G8B8A8_UNORM;
            case 2 -> VK_FORMAT_R32G32_SFLOAT;
            case 3 -> VK_FORMAT_R16G16_UNORM;
            default -> throw new IllegalStateException("the normalised vertex stream has no attribute at location "
                    + location + " (it provides position, colour, uv and lightmap at 0..3)");
        };
    }

    private static int rawOffset(int location) {
        return switch (location) {
            case 0 -> TerrainVertexFormat.RAW_POSITION_OFFSET;
            case 1 -> TerrainVertexFormat.RAW_COLOR_OFFSET;
            case 2 -> TerrainVertexFormat.RAW_UV_OFFSET;
            case 3 -> TerrainVertexFormat.RAW_LIGHTMAP_OFFSET;
            default -> throw new IllegalStateException("the normalised vertex stream has no attribute at location "
                    + location);
        };
    }

    @Override
    public void close() {
        vkDestroyPipeline(device, pipeline, null);
        if (ownsLayout) {
            vkDestroyPipelineLayout(device, layout, null);
        }
    }

    /**
     * Maps a GL blend factor to Vulkan's.
     *
     * <p>Vanilla's two common combinations are {@code SRC_ALPHA, ONE_MINUS_SRC_ALPHA} for the GUI and
     * {@code SRC_ALPHA, ONE} for additive effects such as the enchantment glint and particles. Both appear inside one
     * frame, so this cannot be a constant.</p>
     *
     * @throws IllegalArgumentException for a factor this renderer does not translate, rather than silently
     *                                  substituting one, which would show up as a wrong picture
     */
    private static int blendFactor(int glFactor) {
        return switch (glFactor) {
            case 0 -> VK_BLEND_FACTOR_ZERO;                              // GL_ZERO
            case 1 -> VK_BLEND_FACTOR_ONE;                               // GL_ONE
            case 768 -> VK_BLEND_FACTOR_SRC_COLOR;                       // GL_SRC_COLOR
            case 769 -> VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR;             // GL_ONE_MINUS_SRC_COLOR
            case 770 -> VK_BLEND_FACTOR_SRC_ALPHA;                       // GL_SRC_ALPHA
            case 771 -> VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;             // GL_ONE_MINUS_SRC_ALPHA
            case 772 -> VK_BLEND_FACTOR_DST_ALPHA;                       // GL_DST_ALPHA
            case 773 -> VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA;             // GL_ONE_MINUS_DST_ALPHA
            case 774 -> VK_BLEND_FACTOR_DST_COLOR;                       // GL_DST_COLOR
            case 775 -> VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR;             // GL_ONE_MINUS_DST_COLOR
            case 776 -> VK_BLEND_FACTOR_SRC_ALPHA_SATURATE;              // GL_SRC_ALPHA_SATURATE
            default -> throw new IllegalArgumentException("blend factor " + glFactor + " is not translated");
        };
    }

    /**
     * Maps a GL stencil operation.
     *
     * <p>Strict, unlike {@link #compareOp}: the depth function comes from vanilla, where an unknown value means a
     * patch went wrong and a defensive default is the lesser evil, while these come from a mod and an unmapped one
     * would silently become KEEP - a mask that does not mask.</p>
     */
    static int stencilOp(int glOp) {
        return switch (glOp) {
            case 0 -> org.lwjgl.vulkan.VK10.VK_STENCIL_OP_ZERO;                    // GL_ZERO
            case 7680 -> org.lwjgl.vulkan.VK10.VK_STENCIL_OP_KEEP;                 // GL_KEEP
            case 7681 -> org.lwjgl.vulkan.VK10.VK_STENCIL_OP_REPLACE;              // GL_REPLACE
            case 7682 -> org.lwjgl.vulkan.VK10.VK_STENCIL_OP_INCREMENT_AND_CLAMP;  // GL_INCR
            case 7683 -> org.lwjgl.vulkan.VK10.VK_STENCIL_OP_DECREMENT_AND_CLAMP;  // GL_DECR
            case 5386 -> org.lwjgl.vulkan.VK10.VK_STENCIL_OP_INVERT;               // GL_INVERT
            case 34055 -> org.lwjgl.vulkan.VK10.VK_STENCIL_OP_INCREMENT_AND_WRAP;  // GL_INCR_WRAP
            case 34056 -> org.lwjgl.vulkan.VK10.VK_STENCIL_OP_DECREMENT_AND_WRAP;  // GL_DECR_WRAP
            default -> throw new IllegalArgumentException("stencil operation " + glOp + " is not translated");
        };
    }

    /** Maps a GL stencil comparison, which shares its range with the depth one but not its tolerance. */
    static int stencilCompareOp(int glFunc) {
        if (glFunc < 512 || glFunc > 519) {
            throw new IllegalArgumentException("stencil comparison " + glFunc + " is not translated");
        }
        return compareOp(glFunc);
    }

    static int compareOp(int glFunc) {
        return switch (glFunc) {
            case 512 -> org.lwjgl.vulkan.VK10.VK_COMPARE_OP_NEVER;            // GL_NEVER
            case 513 -> org.lwjgl.vulkan.VK10.VK_COMPARE_OP_LESS;             // GL_LESS
            case 514 -> org.lwjgl.vulkan.VK10.VK_COMPARE_OP_EQUAL;            // GL_EQUAL
            case 515 -> org.lwjgl.vulkan.VK10.VK_COMPARE_OP_LESS_OR_EQUAL;    // GL_LEQUAL
            case 516 -> org.lwjgl.vulkan.VK10.VK_COMPARE_OP_GREATER;          // GL_GREATER
            case 517 -> org.lwjgl.vulkan.VK10.VK_COMPARE_OP_NOT_EQUAL;        // GL_NOTEQUAL
            case 518 -> org.lwjgl.vulkan.VK10.VK_COMPARE_OP_GREATER_OR_EQUAL; // GL_GEQUAL
            case 519 -> org.lwjgl.vulkan.VK10.VK_COMPARE_OP_ALWAYS;           // GL_ALWAYS
            default -> org.lwjgl.vulkan.VK10.VK_COMPARE_OP_LESS_OR_EQUAL;
        };
    }
}
