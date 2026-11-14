#version 450

// The stream fragment shader for OpenGL immediate mode / display list emulation.

layout(location = 0) in vec4 inColor;
layout(location = 1) in vec2 inUv;
layout(location = 2) in vec2 inLightmap;
layout(location = 3) in float inEyeDist;

layout(set = 0, binding = 0) uniform sampler2D atlas;
layout(set = 1, binding = 0) uniform sampler2D lightmap;

layout(push_constant) uniform PushConstants {
    mat4 modelViewProjection;
    vec4 fogColor;
    /** The overlay colour as RGBA8, unpacked with unpackUnorm4x8. */
    uint overlayColorPacked;
    float alphaReference;
    /** Bit 0 alpha test, bits 1-2 flip mode, bits 3-4 overlay mode, bit 5 radial fog. */
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
} pc;

vec4 overlayColor() {
    return unpackUnorm4x8(pc.overlayColorPacked);
}

layout(location = 0) out vec4 outColor;

float computeFogFactor(float dist) {
    if (pc.fogMode == 9729) { // GL_LINEAR
        float range = pc.fogEnd - pc.fogStart;
        if (abs(range) > 0.0001) {
            return clamp((pc.fogEnd - dist) / range, 0.0, 1.0);
        }
        return 0.0;
    } else if (pc.fogMode == 2048) { // GL_EXP
        return clamp(exp(-pc.fogDensity * dist), 0.0, 1.0);
    } else if (pc.fogMode == 2049) { // GL_EXP2
        float f = pc.fogDensity * dist;
        return clamp(exp(-f * f), 0.0, 1.0);
    }
    return 1.0;
}

void main() {
    vec2 uv;
    int flip = (pc.flags >> 1) & 3;
    if (flip == 2) {
        uv = inUv.yx;
    } else if (flip == 1) {
        uv = vec2(1.0 - inUv.x, inUv.y);
    } else {
        uv = inUv;
    }
    vec4 sample4 = texture(atlas, uv);

    if (((pc.flags & 1) != 0 && !(sample4.a > pc.alphaReference)) || sample4.a == 0.0) {
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
    int overlayMode = (pc.flags >> 3) & 3;
    if (overlayMode > 1) {
        // The alpha still comes through the lightmap, as GL's modulate stage does: whatever the light contributes
        // there multiplies alpha as well as colour, and leaving it out would invent an opacity the fixed pipeline
        // never produced.
        outColor = vec4(inColor.rgb * overlayColor().rgb * light, sample4.a * inColor.a * lightAlpha);
        return;
    }
    // Over a light: the texture stays, the light becomes the constant colour, and the silhouette's alpha comes from
    // that light. Kept because a mod can configure the combiner this way, and the two are not the same picture.
    if (overlayMode > 0) {
        outColor = vec4(sample4.rgb * inColor.rgb * overlayColor().rgb,
                sample4.a * inColor.a * lightAlpha);
        return;
    }

    // The overlay is the lightmap unit's combine stage, so it modifies the light and not the finished colour:
    // vanilla makes a hurt mob red by lighting it red, not by mixing red into its textures.
    if (overlayColor().a > 0.0) {
        light = mix(light, overlayColor().rgb, overlayColor().a);
    }

    vec4 baseColor = vec4(sample4.rgb * inColor.rgb * light, sample4.a * inColor.a);
    if (pc.fogMode > 0) {
        float f = computeFogFactor(inEyeDist);
        outColor = vec4(mix(pc.fogColor.rgb, baseColor.rgb, f), baseColor.a);
    } else {
        outColor = baseColor;
    }
}
