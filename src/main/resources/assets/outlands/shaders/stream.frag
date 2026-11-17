#version 450

// The stream fragment shader for OpenGL immediate mode / display list emulation.

layout(location = 0) in vec4 inColor;
layout(location = 1) in vec2 inUv;
layout(location = 2) in vec2 inLightmap;
layout(location = 3) in float inEyeDist;
// The draw's own index, from the vertex stage: a fragment shader has no instance index of its own, and this block is
// per draw.
layout(location = 4) flat in int inDraw;

layout(set = 0, binding = 0) uniform sampler2D atlas;
layout(set = 1, binding = 0) uniform sampler2D lightmap;

struct DrawData {
    mat4 modelViewProjection;
    vec4 fogColor;
    /** The overlay colour as RGBA8, unpacked with unpackUnorm4x8. */
    uint overlayColorPacked;
    float alphaReference;
    /**
     * Bit 0 alpha test, bits 1-2 flip mode, bits 3-4 overlay mode, bit 5 radial fog, bits 6-8 the alpha test's
     * comparison function as {@code glComparison - 512}.
     */
    int flags;
    int fogMode;
    float fogStart;
    float fogEnd;
    float fogDensity;
    float fogTanX;
    float fogTanY;
    float pointSize;
    float spare0;
    float spare1;
};

layout(set = 1, binding = 1) readonly buffer DrawDataBuffer {
    DrawData draws[];
};

vec4 overlayColor(DrawData d) {
    return unpackUnorm4x8(d.overlayColorPacked);
}

layout(location = 0) out vec4 outColor;

float computeFogFactor(DrawData d, float dist) {
    if (d.fogMode == 9729) { // GL_LINEAR
        float range = d.fogEnd - d.fogStart;
        if (abs(range) > 0.0001) {
            return clamp((d.fogEnd - dist) / range, 0.0, 1.0);
        }
        return 0.0;
    } else if (d.fogMode == 2048) { // GL_EXP
        return clamp(exp(-d.fogDensity * dist), 0.0, 1.0);
    } else if (d.fogMode == 2049) { // GL_EXP2
        float f = d.fogDensity * dist;
        return clamp(exp(-f * f), 0.0, 1.0);
    }
    return 1.0;
}

void main() {
    DrawData d = draws[inDraw];
    vec2 uv;
    int flip = (d.flags >> 1) & 3;
    if (flip == 2) {
        uv = inUv.yx;
    } else if (flip == 1) {
        uv = vec2(1.0 - inUv.x, inUv.y);
    } else {
        uv = inUv;
    }
    vec4 sample4 = texture(atlas, uv);

    // glAlphaFunc's comparison, all eight of them. GL_ALWAYS (the default) is index 7 and never discards, so a draw
    // that never called glAlphaFunc costs one compare.
    if ((d.flags & 1) != 0) {
        int comparison = (d.flags >> 6) & 7;
        float a = sample4.a;
        bool passes = comparison == 7
                || (comparison == 1 && a < d.alphaReference)
                || (comparison == 2 && a == d.alphaReference)
                || (comparison == 3 && a <= d.alphaReference)
                || (comparison == 4 && a > d.alphaReference)
                || (comparison == 5 && a != d.alphaReference)
                || (comparison == 6 && a >= d.alphaReference);
        if (!passes) {
            discard;
        }
    }
    // A fully transparent texel never draws, test or no test - which is what the fixed pipeline's texture-alpha
    // path did and what every vanilla model relies on.
    if (sample4.a == 0.0) {
        discard;
    }

    vec3 light;
    float lightAlpha = 1.0;
    if (inLightmap.x > 0.5 || inLightmap.y > 0.5) {
        light = vec3(1.0);
    } else {
        vec2 rawCoord = inLightmap * 65535.0;
        vec2 lightUv = (rawCoord + 8.0) / 256.0;
        vec4 sampled = texture(lightmap, clamp(lightUv, 0.0, 1.0));
        light = sampled.rgb;
        lightAlpha = sampled.a;
    }

    // Outline mode: GlStateManager.enableOutlineMode sets one unit's combine to GL_REPLACE with the team colour.
    // Which unit decides what disappears from the picture.
    //
    // Over the entity's own texture (the case that happens: the outline pass calls it with unit 0 active) the texture
    // is replaced, so the silhouette is one flat colour - that is what a glowing entity looks like, and it is why
    // multiplying the texture in here turned a white outline into the entity's own colour.
    int overlayMode = (d.flags >> 3) & 3;
    if (overlayMode > 1) {
        // The alpha still comes through the lightmap, as GL's modulate stage does: whatever the light contributes
        // there multiplies alpha as well as colour, and leaving it out would invent an opacity the fixed pipeline
        // never produced.
        outColor = vec4(inColor.rgb * overlayColor(d).rgb * light, sample4.a * inColor.a * lightAlpha);
        return;
    }
    // Over a light: the texture stays, the light becomes the constant colour, and the silhouette's alpha comes from
    // that light. Kept because a mod can configure the combiner this way, and the two are not the same picture.
    if (overlayMode > 0) {
        outColor = vec4(sample4.rgb * inColor.rgb * overlayColor(d).rgb,
                sample4.a * inColor.a * lightAlpha);
        return;
    }

    // The overlay is the lightmap unit's combine stage, so it modifies the light and not the finished colour:
    // vanilla makes a hurt mob red by lighting it red, not by mixing red into its textures.
    if (overlayColor(d).a > 0.0) {
        light = mix(light, overlayColor(d).rgb, overlayColor(d).a);
    }

    vec4 baseColor = vec4(sample4.rgb * inColor.rgb * light, sample4.a * inColor.a);
    if (d.fogMode > 0) {
        float f = computeFogFactor(d, inEyeDist);
        outColor = vec4(mix(d.fogColor.rgb, baseColor.rgb, f), baseColor.a);
    } else {
        outColor = baseColor;
    }
}
