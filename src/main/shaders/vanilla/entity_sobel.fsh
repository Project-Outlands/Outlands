#version 450

// The entity-outline pass itself: what makes a spectral arrow's target glow.
//
// This replaces the game's shaders/program/entity_sobel.fsh (#version 120), which is the fragment stage of the
// entity_outline program - the first pass of shaders/post/entity_outline.json. It is an alpha-edge detector, not the
// colour-edge one in outline.fsh: the glowing entities were drawn into the buffer as opaque silhouettes, so what marks
// an edge is a difference in alpha, and the output alpha is the sum of the four neighbour differences. The colour is
// the 5-tap sum of the neighbourhood, scaled by 0.2.
//
// The maths is copied verbatim, including that the four taps subtract one texel on their own axis only. Only the
// syntax is brought forward.
//
// No uniform block: nothing here is uniform, and the vertex stage already provides the block. A block is per stage,
// not per program.

layout(set = 1, binding = 0) uniform sampler2D DiffuseSampler;

layout(location = 0) in vec2 texCoord;
layout(location = 1) in vec2 oneTexel;

layout(location = 0) out vec4 outColor;

void main() {
    vec4 center = texture(DiffuseSampler, texCoord);
    vec4 left = texture(DiffuseSampler, texCoord - vec2(oneTexel.x, 0.0));
    vec4 right = texture(DiffuseSampler, texCoord + vec2(oneTexel.x, 0.0));
    vec4 up = texture(DiffuseSampler, texCoord - vec2(0.0, oneTexel.y));
    vec4 down = texture(DiffuseSampler, texCoord + vec2(0.0, oneTexel.y));
    float leftDiff  = abs(center.a - left.a);
    float rightDiff = abs(center.a - right.a);
    float upDiff    = abs(center.a - up.a);
    float downDiff  = abs(center.a - down.a);
    float total = clamp(leftDiff + rightDiff + upDiff + downDiff, 0.0, 1.0);
    vec3 edgeColor = center.rgb * center.a + left.rgb * left.a + right.rgb * right.a
            + up.rgb * up.a + down.rgb * down.a;
    outColor = vec4(edgeColor * 0.2, total);
}
