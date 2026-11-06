#version 450

// The loader's own terrain shader, for M3. It draws whatever geometry it is handed; the geometry is a stand-in
// until chunk data is wired in, so the point of this shader is the interface: a vertex format, a pushed MVP, and
// a colour that depends on depth so a flat face is visibly flat.

layout(location = 0) in vec3 inPosition;
layout(location = 1) in vec3 inNormal;
layout(location = 2) in vec3 inColor;

// A push constant, not a uniform buffer: 64 bytes of matrix is well inside the 128-byte minimum every device
// guarantees, and it avoids a descriptor set for the one thing every draw needs.
layout(push_constant) uniform PushConstants {
    mat4 modelViewProjection;
} pc;

layout(location = 0) out vec3 outNormal;
layout(location = 1) out vec3 outColor;

void main() {
    gl_Position = pc.modelViewProjection * vec4(inPosition, 1.0);
    outNormal = inNormal;
    outColor = inColor;
}
