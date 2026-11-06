package net.minecraftforge.client.render.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDepthStencilStateCreateInfo;
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

import static org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_ONE;
import static org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_SRC_ALPHA;
import static org.lwjgl.vulkan.VK10.VK_BLEND_FACTOR_ZERO;
import static org.lwjgl.vulkan.VK10.VK_BLEND_OP_ADD;
import static org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_A_BIT;
import static org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_B_BIT;
import static org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_G_BIT;
import static org.lwjgl.vulkan.VK10.VK_COLOR_COMPONENT_R_BIT;
import static org.lwjgl.vulkan.VK10.VK_COMPARE_OP_LESS_OR_EQUAL;
import static org.lwjgl.vulkan.VK10.VK_CULL_MODE_BACK_BIT;
import static org.lwjgl.vulkan.VK10.VK_DYNAMIC_STATE_SCISSOR;
import static org.lwjgl.vulkan.VK10.VK_DYNAMIC_STATE_VIEWPORT;
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
    static final int VERTEX_STRIDE = TerrainVertexFormat.STRIDE;

    private final VkDevice device;
    private final long layout;
    private final long pipeline;

    private VulkanPipeline(VkDevice device, long layout, long pipeline) {
        this.device = device;
        this.layout = layout;
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
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPushConstantRange.Buffer pushConstants = VkPushConstantRange.calloc(1, stack);
            pushConstants.get(0)
                    .stageFlags(VK_SHADER_STAGE_VERTEX_BIT)
                    .offset(0)
                    .size(16 * Float.BYTES);

            VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType$Default()
                    .pPushConstantRanges(pushConstants);
            LongBuffer layoutOut = stack.mallocLong(1);
            VkCheck.check(vkCreatePipelineLayout(device, layoutInfo, null, layoutOut), "vkCreatePipelineLayout");
            long layout = layoutOut.get(0);

            long pipeline = 0L;
            try {
                pipeline = createPipeline(device, layout, vertex, fragment, colorFormat, depthFormat, stack);
                return new VulkanPipeline(device, layout, pipeline);
            } catch (RuntimeException e) {
                if (pipeline != 0L) {
                    vkDestroyPipeline(device, pipeline, null);
                }
                vkDestroyPipelineLayout(device, layout, null);
                throw e;
            }
        }
    }

    private static long createPipeline(VkDevice device, long layout, VulkanShader vertex, VulkanShader fragment,
                                       int colorFormat, int depthFormat, MemoryStack stack) {
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
        binding.get(0).binding(0).stride(VERTEX_STRIDE).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);

        // The offsets are TerrainVertexFormat's, which are the ones VboRenderList.setupArrayPointers gives GL.
        // R16G16_UNORM for the lightmap means the shader sees 0..1 without the CPU touching the shorts.
        VkVertexInputAttributeDescription.Buffer attributes = VkVertexInputAttributeDescription.calloc(4, stack);
        attributes.get(0).location(0).binding(0).format(VK_FORMAT_R32G32B32_SFLOAT)
                .offset(TerrainVertexFormat.POSITION_OFFSET);
        attributes.get(1).location(1).binding(0).format(VK_FORMAT_R8G8B8A8_UNORM)
                .offset(TerrainVertexFormat.COLOR_OFFSET);
        attributes.get(2).location(2).binding(0).format(VK_FORMAT_R32G32_SFLOAT)
                .offset(TerrainVertexFormat.UV_OFFSET);
        attributes.get(3).location(3).binding(0).format(VK_FORMAT_R16G16_UNORM)
                .offset(TerrainVertexFormat.LIGHTMAP_OFFSET);

        VkPipelineVertexInputStateCreateInfo vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack)
                .sType$Default()
                .pVertexBindingDescriptions(binding)
                .pVertexAttributeDescriptions(attributes);

        VkPipelineInputAssemblyStateCreateInfo inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                .sType$Default()
                .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);

        VkPipelineViewportStateCreateInfo viewportState = VkPipelineViewportStateCreateInfo.calloc(stack)
                .sType$Default()
                .viewportCount(1)
                .scissorCount(1);

        VkPipelineRasterizationStateCreateInfo rasterization = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                .sType$Default()
                .polygonMode(org.lwjgl.vulkan.VK10.VK_POLYGON_MODE_FILL)
                .cullMode(VK_CULL_MODE_BACK_BIT)
                .frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE)
                .lineWidth(1.0f);

        VkPipelineMultisampleStateCreateInfo multisample = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                .sType$Default()
                .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);

        VkPipelineDepthStencilStateCreateInfo depthStencil = VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                .sType$Default()
                .depthTestEnable(true)
                .depthWriteEnable(true)
                .depthCompareOp(VK_COMPARE_OP_LESS_OR_EQUAL);

        VkPipelineColorBlendAttachmentState.Buffer blendAttachment =
                VkPipelineColorBlendAttachmentState.calloc(1, stack);
        blendAttachment.get(0)
                .blendEnable(false)
                .srcColorBlendFactor(VK_BLEND_FACTOR_SRC_ALPHA)
                .dstColorBlendFactor(VK_BLEND_FACTOR_ONE)
                .colorBlendOp(VK_BLEND_OP_ADD)
                .srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE)
                .dstAlphaBlendFactor(VK_BLEND_FACTOR_ZERO)
                .alphaBlendOp(VK_BLEND_OP_ADD)
                .colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT
                        | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT);

        VkPipelineColorBlendStateCreateInfo colorBlend = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                .sType$Default()
                .pAttachments(blendAttachment);

        VkPipelineDynamicStateCreateInfo dynamicState = VkPipelineDynamicStateCreateInfo.calloc(stack)
                .sType$Default()
                .pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR));

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

    @Override
    public void close() {
        vkDestroyPipeline(device, pipeline, null);
        vkDestroyPipelineLayout(device, layout, null);
    }
}
