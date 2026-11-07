#version 450

// The fragment stage samples whatever texture the draw was issued with, then multiplies by the vertex colour.
//
// That is the whole of GL's fixed-function texture pipeline for the vanilla draw paths: modulate with the vertex
// colour, nothing else. Lighting is already baked into the colour by vanilla (directional shade and ambient occlusion
// are written into the vertex before it reaches the buffer), so the sample times the colour is the final colour - no
// second lighting term belongs here.

layout(location = 0) in vec4 inColor;      // vertex colour, already carrying vanilla's baked shade and AO
layout(location = 1) in vec2 inUv;         // texture coordinate, the one the game wrote
layout(location = 2) in vec2 inLightmap;   // block light, sky light, both 0..1

// One texture per draw. The white 1x1 image stands in for "nothing bound", which makes the multiply a no-op for the
// solid-colour quads vanilla draws without a texture.
layout(set = 0, binding = 0) uniform sampler2D atlas;

// The fixed-function alpha test, as a discard.
//
// GL removed fragments whose alpha failed a comparison, and vanilla's GUI depends on it: a fully transparent texel
// discarded here never writes anything, while without it the same texel writes its colour whenever blending happens
// to be off. That is the difference between the main menu's wordmark floating over the panorama and the same
// wordmark sitting on a black rectangle.
layout(push_constant) uniform PushConstants {
    mat4 modelViewProjection;
    float alphaReference;
    float alphaTest;
} pc;

layout(location = 0) out vec4 outColor;

void main() {
    vec4 sample4 = texture(atlas, inUv);

    // GL_GREATER is the only comparison vanilla sets, and the backend rejects the others rather than guessing.
    if (pc.alphaTest > 0.5 && !(sample4.a > pc.alphaReference)) {
        discard;
    }

    // Component order is block light then sky light: BufferBuilder.lightmap writes blockLight to the element's first
    // short and skyLight to the second, which is the opposite of the argument order of that method.
    float blockLight = clamp(inLightmap.x, 0.0, 1.0);
    float skyLight = clamp(inLightmap.y, 0.0, 1.0);

    // A GUI quad arrives with the lightmap at its full value, so this factor is 1 there and only darkens world
    // geometry. Vanilla's own minimum brightness for an unlit block is brighter than zero, hence the floor.
    float brightness = mix(0.15, 1.0, max(blockLight, skyLight));

    outColor = vec4(sample4.rgb * inColor.rgb * brightness, sample4.a * inColor.a);
}
