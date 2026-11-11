#version 450

// Entity-outline pass: what makes a spectral arrow's target glow.
//
// This replaces the game's shaders/program/outline.fsh (#version 120). It is an edge detector run over the buffer the
// glowing entities were just drawn into: the four neighbour differences are summed, and the centre colour has that
// sum subtracted, which leaves the silhouette bright against black. The maths is copied verbatim from the game's
// shader - only the syntax is brought forward, because the algorithm is what vanilla's look is made of.
//
// The stage declares no uniform block: nothing here is uniform, and the vertex stage already provides the block. That
// is legal - a block is per stage, not per program - and it keeps the sampler as the only thing this pass binds.

layout(set = 1, binding = 0) uniform sampler2D DiffuseSampler;

layout(location = 0) in vec2 texCoord;
layout(location = 1) in vec2 oneTexel;

layout(location = 0) out vec4 outColor;

void main() {
    vec4 center = texture(DiffuseSampler, texCoord);
    vec4 up     = texture(DiffuseSampler, texCoord + vec2(        0.0, -oneTexel.y));
    vec4 down   = texture(DiffuseSampler, texCoord + vec2( oneTexel.x,         0.0));
    vec4 left   = texture(DiffuseSampler, texCoord + vec2(-oneTexel.x,         0.0));
    vec4 right  = texture(DiffuseSampler, texCoord + vec2(        0.0,  oneTexel.y));
    vec4 uDiff = center - up;
    vec4 dDiff = center - down;
    vec4 lDiff = center - left;
    vec4 rDiff = center - right;
    vec4 sum = uDiff + dDiff + lDiff + rDiff;
    vec3 clamped = clamp(center.rgb - sum.rgb, 0.0, 1.0);
    outColor = vec4(clamped, 1.0);
}
