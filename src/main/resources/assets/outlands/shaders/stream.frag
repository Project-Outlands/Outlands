#version 450

// The stream fragment shader for OpenGL immediate mode / display list emulation.

layout(location = 0) in vec4 inColor;
layout(location = 1) in vec2 inUv;
layout(location = 2) in vec2 inLightmap;

layout(set = 0, binding = 0) uniform sampler2D atlas;
layout(set = 1, binding = 0) uniform sampler2D lightmap;

layout(push_constant) uniform PushConstants {
    mat4 modelViewProjection;
    float alphaReference;
    float alphaTest;
    float flipU;
} pc;

layout(location = 0) out vec4 outColor;

void main() {
    vec2 uv;
    if (pc.flipU > 1.5) {
        uv = inUv.yx;
    } else if (pc.flipU > 0.5) {
        uv = vec2(1.0 - inUv.x, inUv.y);
    } else {
        uv = inUv;
    }
    vec4 sample4 = texture(atlas, uv);

    if ((pc.alphaTest > 0.5 && !(sample4.a > pc.alphaReference)) || sample4.a == 0.0) {
        discard;
    }

    vec3 light;
    if (inLightmap.x > 0.5 || inLightmap.y > 0.5) {
        light = vec3(1.0);
    } else {
        vec2 rawCoord = inLightmap * 65535.0;
        vec2 lightUv = (rawCoord + 8.0) / 256.0;
        light = texture(lightmap, clamp(lightUv, 0.0, 1.0)).rgb;
    }

    outColor = vec4(sample4.rgb * inColor.rgb * light, sample4.a * inColor.a);
}
