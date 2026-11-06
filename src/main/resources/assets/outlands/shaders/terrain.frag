#version 450

// Until the block atlas is bound, the texture coordinate cannot be sampled, so the fragment stage shows what the
// vertex data actually carries: the baked shade in the colour, and the lightmap coordinate. That makes a wrong
// offset or a wrong stride visible - a misread lightmap shows up as a wrong gradient rather than as a plausible
// texture. Sampling the atlas is a separate step and needs descriptors.

layout(location = 0) in vec4 inColor;
layout(location = 1) in vec2 inUv;
layout(location = 2) in vec2 inLightmap;

layout(location = 0) out vec4 outColor;

void main() {
    // The lightmap coordinate is UNORM, so 0..1 spans the range of the two unsigned shorts vanilla writes.
    // Component order is block light then sky light: BufferBuilder.lightmap writes blockLight to the element's
    // first short and skyLight to the second, which is the opposite of the argument order of that method.
    float blockLight = clamp(inLightmap.x, 0.0, 1.0);
    float skyLight = clamp(inLightmap.y, 0.0, 1.0);
    float brightness = mix(0.15, 1.0, max(blockLight, skyLight));

    // The atlas is not bound yet; the UV's fractional part gives a stable per-texel tint so the geometry reads as
    // textured rather than as flat-shaded blocks.
    float tint = 0.85 + 0.15 * fract(inUv.x * 16.0) * fract(inUv.y * 16.0);

    outColor = vec4(inColor.rgb * brightness * tint, 1.0);
}
