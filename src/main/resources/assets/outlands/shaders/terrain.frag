#version 450
#extension GL_EXT_buffer_reference : enable

layout(buffer_reference, std430) readonly buffer ChunkDataBuffer {
    vec4 chunkOffsets[];
};

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

// The game's own 16x16 lightmap, in set 1 so every draw of the frame shares one binding: it changes when the sun moves
// or a torch flickers, not per draw. Its u axis is block light and its v axis sky light, which is exactly what the
// lightmap attribute carries, so the sample is the whole of vanilla's brightness curve - the sun's height, night
// vision, the boss-colour modifier and the dimension's own overrides all arrive as texels.
layout(set = 1, binding = 0) uniform sampler2D lightmap;

// The fixed-function alpha test, as a discard.
//
// GL removed fragments whose alpha failed a comparison, and vanilla's GUI depends on it: a fully transparent texel
// discarded here never writes anything, while without it the same texel writes its colour whenever blending happens
// to be off. That is the difference between the main menu's wordmark floating over the panorama and the same
// wordmark sitting on a black rectangle.
layout(push_constant) uniform PushConstants {
    mat4 modelViewProjection;
    ChunkDataBuffer chunkData;
    float alphaReference;
    float alphaTest;
    float flipU;
} pc;

layout(location = 0) out vec4 outColor;

void main() {
    // A texture that was filled by a framebuffer copy is sampled with u reversed: the quad that reads it back places
    // u = 0 at the right edge of the screen, so storing the copy unmirrored puts the background's left on the screen's
    // right. Measured against the GL renderer, whose background has its water on the left and whose Vulkan
    // counterpart had it on the right.
    // flipU: 0 = as given, 1 = mirror u, 2 = swap u and v (transposed sampling).
    vec2 uv;
    if (pc.flipU > 1.5) {
        uv = inUv.yx;
    } else if (pc.flipU > 0.5) {
        uv = vec2(1.0 - inUv.x, inUv.y);
    } else {
        uv = inUv;
    }
    vec4 sample4 = texture(atlas, uv);

    // GL_GREATER is the only comparison vanilla sets, and the backend rejects the others rather than guessing.
    // In addition, discard fully transparent texels so they never write depth or trigger redundant work.
    if ((pc.alphaTest > 0.5 && !(sample4.a > pc.alphaReference)) || sample4.a == 0.0) {
        discard;
    }

    // Component order is block light then sky light: BufferBuilder.lightmap writes blockLight to the element's first
    // short and skyLight to the second, which is the opposite of the argument order of that method.
    //
    // Vanilla's lightmap coordinates are in 0..240 (blockLight * 16, skyLight * 16).
    // The vertex attribute format is R16G16_UNORM, so the raw 16-bit value is scaled to 0..1 by dividing by 65535.
    // In GL, the lightmap matrix mapped coord -> (coord + 8.0) / 256.0 to land in the center of 16x16 texels.
    // Geometry without lightmap (e.g. GUI, text) arrives with 0xFFFF (UNORM 1.0), which gets full brightness.
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
