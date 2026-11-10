#version 450
#extension GL_EXT_buffer_reference : require

// The terrain shader. Its vertex input is vanilla's DefaultVertexFormats.BLOCK, unchanged: the mesh builder only
// splits quads into triangles and adds indices, so nothing here is a conversion - the offsets are the ones
// VboRenderList.setupArrayPointers hands to GL.
//
// There is no normal attribute. Vanilla bakes its directional shading and ambient occlusion into the vertex colour
// before the vertex is written, so the lighting is already in `inColor`; adding a normal would mean inventing a
// second, disagreeing source for it.

layout(buffer_reference, std430) readonly buffer ChunkDataBuffer {
    vec4 chunkOffsets[];
};

layout(location = 0) in vec3 inPosition;   // offset 0,  R32G32B32_SFLOAT
layout(location = 1) in vec4 inColor;      // offset 12, R8G8B8A8_UNORM
layout(location = 2) in vec2 inUv;         // offset 16, R32G32_SFLOAT
layout(location = 3) in vec2 inLightmap;   // offset 24, R16G16_UNORM

// Push constants: mat4 MVP (64 bytes), vec4 fogColor (16 bytes), chunkData 64-bit BDA pointer (8 bytes),
// alphaReference (4), alphaTest (4), flipU (4), fogMode (4), fogStart (4), fogEnd (4), fogDensity (4),
// cameraX/Y/Z (12 bytes). Total: 128 bytes, exact limit guaranteed by Vulkan specifications.
layout(push_constant) uniform PushConstants {
    mat4 modelViewProjection;
    vec4 fogColor;
    ChunkDataBuffer chunkData;
    float alphaReference;
    float alphaTest;
    float flipU;
    int fogMode;
    float fogStart;
    float fogEnd;
    float fogDensity;
    float cameraX;
    float cameraY;
    float cameraZ;
} pc;

layout(location = 0) out vec4 outColor;
layout(location = 1) out vec2 outUv;
layout(location = 2) out vec2 outLightmap;
layout(location = 3) out float outEyeDist;

void main() {
    vec3 worldPos = inPosition + pc.chunkData.chunkOffsets[gl_InstanceIndex].xyz;
    gl_Position = pc.modelViewProjection * vec4(worldPos, 1.0);
    outColor = inColor;
    outUv = inUv;
    outLightmap = inLightmap;
    vec3 camPos = vec3(pc.cameraX, pc.cameraY, pc.cameraZ);
    outEyeDist = length(worldPos - camPos);
}
