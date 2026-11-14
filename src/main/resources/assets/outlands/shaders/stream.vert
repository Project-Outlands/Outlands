#version 450

// The stream shader for OpenGL immediate mode / display list emulation.
// Pos (vec3), Color (vec4), UV (vec2), Lightmap (vec2).

layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec4 inColor;
layout(location = 2) in vec2 inUv;
layout(location = 3) in vec2 inLightmap;

// 128 bytes, the limit Vulkan guarantees: mat4 (64), fogColor (16), overlayColor packed RGBA8 (4),
// alphaReference (4), flags (4), fogMode (4), fogStart/fogEnd/fogDensity (12), fogTanHalfFov (8), pointSize (4),
// spare (8).
//
// overlayColor is packed and alphaTest/flipU/overlayMode are bits because this block was full and two features
// needed room: glPointSize and GL_NV_fog_distance. Both are state GL has and the shader has to read, and neither
// was worth a second descriptor set.
layout(push_constant) uniform PushConstants {
    mat4 modelViewProjection;
    vec4 fogColor;
    /** The overlay colour as RGBA8, unpacked with unpackUnorm4x8. */
    uint overlayColorPacked;
    float alphaReference;
    /**
     * Bit 0 alpha test, bits 1-2 flip mode (0 none, 1 mirror u, 2 transpose), bits 3-4 overlay mode
     * (0 mix, 1 light replaced, 2 texture replaced), bit 5 radial fog.
     */
    int flags;
    int fogMode;
    float fogStart;
    float fogEnd;
    float fogDensity;
    /** tan(halfFovX) and tan(halfFovY); both 0 means GL's default planar fog distance. Floats, not a vec2: a vec2
     *  aligns to 8 and would push this block past the 128 bytes Vulkan guarantees. */
    float fogTanX;
    float fogTanY;
    /** The rasterised diameter of a point primitive, from glPointSize. */
    float pointSize;
    float spare0;
    float spare1;
} pc;

layout(location = 0) out vec4 outColor;
layout(location = 1) out vec2 outUv;
layout(location = 2) out vec2 outLightmap;
layout(location = 3) out float outEyeDist;

void main() {
    gl_Position = pc.modelViewProjection * vec4(inPosition, 1.0);
    gl_PointSize = pc.pointSize;
    outColor = inColor;
    outUv = inUv;
    outLightmap = inLightmap;
    // The fog coordinate. With both tangents zero this is exactly |gl_Position.w|, which is GL's default
    // absolute eye-space z - no branch and no extra state for the common case. With GL_NV_fog_distance's
    // GL_EYE_RADIAL_NV the CPU supplies the projection's tangents, and this becomes the eye-to-fragment distance:
    // viewPos = (clip.x * tanX, clip.y * tanY, -clip.w), so its length is what is written here.
    outEyeDist = length(vec3(gl_Position.x * pc.fogTanX,
                             gl_Position.y * pc.fogTanY,
                             gl_Position.w));
}
