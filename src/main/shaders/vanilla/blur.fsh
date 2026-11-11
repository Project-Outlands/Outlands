#version 450

// One direction of the outline's two-pass blur.
//
// This replaces the game's shaders/program/blur.fsh (#version 120). The game runs it twice with BlurDir set to
// (1, 0) then (0, 1), which is a separable box blur with a triangular falloff - radius 2 on the outline chain. The
// loop is unchanged; only the syntax is brought forward.
//
// The uniform block repeats the members the vertex stage declares and then adds its own. That is deliberate: std140
// lays members out in declaration order, so as long as the shared members come first and in the same order, the two
// stages agree on every offset. Appending is what keeps this a copy of sobel.vsh's block plus two members, rather
// than a second layout that happens to look similar.
//
// Binding convention: set 0 is the uniform block, set 1 is the samplers.

layout(set = 0, binding = 0) uniform OutlandsGameUniforms {
    mat4 ProjMat;
    vec2 InSize;
    vec2 OutSize;
    vec2 BlurDir;
    float Radius;
} u;

layout(set = 1, binding = 0) uniform sampler2D DiffuseSampler;

layout(location = 0) in vec2 texCoord;
layout(location = 1) in vec2 oneTexel;

layout(location = 0) out vec4 outColor;

void main() {
    vec4 blurred = vec4(0.0);
    float totalStrength = 0.0;
    float totalAlpha = 0.0;
    float totalSamples = 0.0;
    for (float r = -u.Radius; r <= u.Radius; r += 1.0) {
        vec4 sampled = texture(DiffuseSampler, texCoord + oneTexel * r * u.BlurDir);

        // Accumulate average alpha.
        totalAlpha = totalAlpha + sampled.a;
        totalSamples = totalSamples + 1.0;

        // Accumulate smoothed blur.
        float strength = 1.0 - abs(r / u.Radius);
        totalStrength = totalStrength + strength;
        blurred = blurred + sampled;
    }
    outColor = vec4(blurred.rgb / (u.Radius * 2.0 + 1.0), totalAlpha);
}
