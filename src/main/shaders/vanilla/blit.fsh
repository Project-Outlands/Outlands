#version 450

// The blit fragment stage: one textured quad, modulated by a colour.
//
// This replaces the game's shaders/program/blit.fsh (#version 120). It is the last pass of the outline chain - the
// blurred outline is blitted over the frame - and it is also the shape every simple post-processing effect in the game
// takes, which is why it is worth having a hand-written version of: a pack that ships its own module replaces it, and
// everything that behaves like it keeps working.

layout(set = 0, binding = 0) uniform OutlandsGameUniforms {
    mat4 ProjMat;
    vec2 OutSize;
    vec4 ColorModulate;
} u;

layout(set = 1, binding = 0) uniform sampler2D DiffuseSampler;

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 outColor;

void main() {
    outColor = texture(DiffuseSampler, texCoord) * u.ColorModulate;
}
