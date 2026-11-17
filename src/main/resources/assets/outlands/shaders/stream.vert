#version 450

// The stream shader for OpenGL immediate mode / display list emulation.
// Pos (vec3), Color (vec4), UV (vec2), Lightmap (vec2).

layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec4 inColor;
layout(location = 2) in vec2 inUv;
layout(location = 3) in vec2 inLightmap;

// One entry per draw, indexed by that draw's instance index.
//
// 128 bytes: mat4 (64), fogColor (16), overlayColor packed RGBA8 (4), alphaReference (4), flags (4), fogMode (4),
// fogStart/fogEnd/fogDensity (12), fogTanHalfFov (8), pointSize (4), spare (8).
//
// overlayColor is packed and alphaTest/flipU/overlayMode are bits because this block was full and two features
// needed room: glPointSize and GL_NV_fog_distance.
//
// It used to be a push_constant block. Every entity part has its own model-view matrix, so it changed on every draw,
// and a changed push block invalidates the pipeline state on RADV: the next vkCmdDrawIndexed then re-emits the whole
// graphics state packet. Measured at 1.5 us for the draw call alone, against 0.2 us for a draw whose state did not
// change. Reading the block from a buffer leaves the pipeline state alone between draws.
struct DrawData {
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
    /** tan(halfFovX) and tan(halfFovY); both 0 means GL's default planar fog distance. */
    float fogTanX;
    float fogTanY;
    /** The rasterised diameter of a point primitive, from glPointSize. */
    float pointSize;
    float spare0;
    float spare1;
};

layout(set = 1, binding = 1) readonly buffer DrawDataBuffer {
    DrawData draws[];
};

layout(location = 0) out vec4 outColor;
layout(location = 1) out vec2 outUv;
layout(location = 2) out vec2 outLightmap;
layout(location = 3) out float outEyeDist;
// Flat, because the fragment shader reads the same entry rather than interpolating between two draws' blocks.
layout(location = 4) flat out int outDraw;

void main() {
    DrawData d = draws[gl_InstanceIndex];
    gl_Position = d.modelViewProjection * vec4(inPosition, 1.0);
    gl_PointSize = d.pointSize;
    outColor = inColor;
    outUv = inUv;
    outLightmap = inLightmap;
    // The fog coordinate. With both tangents zero this is exactly |gl_Position.w|, which is GL's default
    // absolute eye-space z - no branch and no extra state for the common case. With GL_NV_fog_distance's
    // GL_EYE_RADIAL_NV the CPU supplies the projection's tangents, and this becomes the eye-to-fragment distance:
    // viewPos = (clip.x * tanX, clip.y * tanY, -clip.w), so its length is what is written here.
    outEyeDist = length(vec3(gl_Position.x * d.fogTanX,
                             gl_Position.y * d.fogTanY,
                             gl_Position.w));
    outDraw = gl_InstanceIndex;
}
