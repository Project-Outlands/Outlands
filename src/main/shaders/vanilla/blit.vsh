#version 450

// The blit vertex stage: a full-screen quad, UVs derived from position.
//
// This replaces the game's shaders/program/blit.vsh (#version 120), which the last pass of the outline chain uses and
// so does every other post-processing effect in the game. The maths is unchanged, including the fixed 0.2 depth and
// the flipped texCoord.y.
//
// blit.vsh and blit.fsh must agree on their block, and they do: the fragment appends ColorModulate after OutSize, so
// the shared members keep their offsets. See blur.fsh for why that matters.

layout(location = 0) in vec4 Position;

layout(set = 0, binding = 0) uniform OutlandsGameUniforms {
    mat4 ProjMat;
    vec2 OutSize;
} u;

layout(location = 0) out vec2 texCoord;

void main() {
    vec4 outPos = u.ProjMat * vec4(Position.xy, 0.0, 1.0);
    gl_Position = vec4(outPos.xy, 0.2, 1.0);

    texCoord = Position.xy / u.OutSize;
    texCoord.y = 1.0 - texCoord.y;
}
