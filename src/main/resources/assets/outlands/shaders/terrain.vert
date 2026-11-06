#version 450

// The terrain shader. Its vertex input is vanilla's DefaultVertexFormats.BLOCK, unchanged: the mesh builder only
// splits quads into triangles and adds indices, so nothing here is a conversion - the offsets are the ones
// VboRenderList.setupArrayPointers hands to GL.
//
// There is no normal attribute. Vanilla bakes its directional shading and ambient occlusion into the vertex colour
// before the vertex is written, so the lighting is already in `inColor`; adding a normal would mean inventing a
// second, disagreeing source for it.

layout(location = 0) in vec3 inPosition;   // offset 0,  R32G32B32_SFLOAT
layout(location = 1) in vec4 inColor;      // offset 12, R8G8B8A8_UNORM
layout(location = 2) in vec2 inUv;         // offset 16, R32G32_SFLOAT
layout(location = 3) in vec2 inLightmap;   // offset 24, R16G16_UNORM

// One mat4 per draw. 64 bytes, inside the 128 every device guarantees.
layout(push_constant) uniform PushConstants {
    mat4 modelViewProjection;
} pc;

layout(location = 0) out vec4 outColor;
layout(location = 1) out vec2 outUv;
layout(location = 2) out vec2 outLightmap;

void main() {
    gl_Position = pc.modelViewProjection * vec4(inPosition, 1.0);
    outColor = inColor;
    outUv = inUv;
    outLightmap = inLightmap;
}
