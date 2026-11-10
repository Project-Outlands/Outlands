#version 450

// The stream shader for OpenGL immediate mode / display list emulation.
// Pos (vec3), Color (vec4), UV (vec2), Lightmap (vec2).

layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec4 inColor;
layout(location = 2) in vec2 inUv;
layout(location = 3) in vec2 inLightmap;

layout(push_constant) uniform PushConstants {
    mat4 modelViewProjection;
    vec4 fogColor;
    float alphaReference;
    float alphaTest;
    float flipU;
    int fogMode;
    float fogStart;
    float fogEnd;
    float fogDensity;
} pc;

layout(location = 0) out vec4 outColor;
layout(location = 1) out vec2 outUv;
layout(location = 2) out vec2 outLightmap;
layout(location = 3) out float outEyeDist;

void main() {
    gl_Position = pc.modelViewProjection * vec4(inPosition, 1.0);
    outColor = inColor;
    outUv = inUv;
    outLightmap = inLightmap;
    outEyeDist = gl_Position.w;
}
