#version 450

// Full-screen quad vertex stage for the entity-outline chain.
//
// This replaces the game's shaders/program/sobel.vsh, which is #version 120 and cannot be compiled for Vulkan as it
// stands. The maths is unchanged, including the two details that look wrong but are not: the quad's depth is a fixed
// 0.2 (GL-era post-processing draws at a constant depth and disables the depth test), and texCoord.y is flipped
// because GL's texture origin is the bottom left while the quad is built in window coordinates.
//
// One change is visible: the uniforms are a block with an explicit binding, where GL had loose uniforms the compiler
// could place anywhere. That is not a style choice - Vulkan has no loose uniforms - and it is also what lets the
// facade answer glGetUniformLocation by name: the members keep the names the game asks for.
//
// The block must be a prefix-compatible copy of every fragment stage this vertex is paired with (outline.fsh and
// blur.fsh). Sharing the leading members is what keeps their offsets in agreement; see the note in those files.
//
// Binding convention for a game program on this backend: set 0 is the uniform block, set 1 is the samplers.

layout(location = 0) in vec4 Position;

layout(set = 0, binding = 0) uniform OutlandsGameUniforms {
    mat4 ProjMat;
    vec2 InSize;
    vec2 OutSize;
} u;

layout(location = 0) out vec2 texCoord;
layout(location = 1) out vec2 oneTexel;

void main() {
    vec4 outPos = u.ProjMat * vec4(Position.xy, 0.0, 1.0);
    gl_Position = vec4(outPos.xy, 0.2, 1.0);

    oneTexel = 1.0 / u.InSize;

    texCoord = Position.xy / u.OutSize;
    texCoord.y = 1.0 - texCoord.y;
}
