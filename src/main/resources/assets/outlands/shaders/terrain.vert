#version 450
#extension GL_EXT_buffer_reference : require

// The terrain shader. Its vertex input uses the compact 16-byte layout:
// - Location 0: inPosAndLight (VK_FORMAT_R16G16B16A16_SINT, offset 0): 3x s16 pos relative to chunk scaled by 512.0,
//               and 2x u8 lightmap (block in byte 6, sky in byte 7) packed in .w.
// - Location 1: inUv (VK_FORMAT_R16G16_UNORM, offset 8): 2x u16 unorm texture coordinates.
// - Location 2: inColor (VK_FORMAT_R8G8B8A8_UNORM, offset 12): 4x u8 unorm baked shade and AO.
//
// There is no normal attribute. Vanilla bakes its directional shading and ambient occlusion into the vertex colour
// before the vertex is written, so the lighting is already in `inColor`.

layout(buffer_reference, std430) readonly buffer ChunkDataBuffer {
    vec4 chunkOffsets[];
};

layout(location = 0) in ivec4 inPosAndLight;
layout(location = 1) in vec2 inUv;
layout(location = 2) in vec4 inColor;

// Push constants: mat4 MVP (64 bytes), vec4 fogColor (16 bytes), chunkData 64-bit BDA pointer (8 bytes),
// alphaReference (4), flags (4), fogMode (4), fogStart (4), fogEnd (4), fogDensity (4),
// cameraX/Y/Z (12 bytes), spare (4). Total: 128 bytes, the limit Vulkan guarantees.
//
// alphaTest, flipU and the fog distance mode used to be three separate floats. They are one int of bits now,
// because the distance mode had nowhere else to go and four bytes were cheaper than growing the block past 128.
layout(push_constant) uniform PushConstants {
    mat4 modelViewProjection;
    vec4 fogColor;
    ChunkDataBuffer chunkData;
    float alphaReference;
    /** Bit 0 alpha test, bits 1-2 flip mode (0 none, 1 mirror u, 2 transpose), bit 3 radial fog. */
    int flags;
    int fogMode;
    float fogStart;
    float fogEnd;
    float fogDensity;
    float cameraX;
    float cameraY;
    float cameraZ;
    float spare;
} pc;

const int FLAG_RADIAL_FOG = 8;

layout(location = 0) out vec4 outColor;
layout(location = 1) out vec2 outUv;
layout(location = 2) out vec2 outLightmap;
layout(location = 3) out float outEyeDist;

void main() {
    vec3 localPos = vec3(inPosAndLight.xyz) * (1.0 / 512.0);
    vec3 worldPos = localPos + pc.chunkData.chunkOffsets[gl_InstanceIndex].xyz;
    gl_Position = pc.modelViewProjection * vec4(worldPos, 1.0);
    outColor = inColor;
    outUv = inUv;
    int lightData = inPosAndLight.w;
    outLightmap = vec2(float(lightData & 0xFF), float((lightData >> 8) & 0xFF));
    // GL's fog coordinate: absolute eye-space z by default, eye-to-fragment distance with
    // GL_NV_fog_distance's GL_EYE_RADIAL_NV (what Immersive Engineering asks for). Both are computed here because
    // this is the only stage that has the vertex.
    vec3 camPos = vec3(pc.cameraX, pc.cameraY, pc.cameraZ);
    outEyeDist = (pc.flags & FLAG_RADIAL_FOG) != 0 ? length(worldPos - camPos) : gl_Position.w;
}
