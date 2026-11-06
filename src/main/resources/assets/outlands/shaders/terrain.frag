#version 450

// A single directional light and a fixed ambient term. Not a lighting model - a way to make the depth and the
// normals of the stand-in geometry visible, so a wrong matrix or a reversed winding shows up as a wrong picture
// rather than as a plausible one.

layout(location = 0) in vec3 inNormal;
layout(location = 1) in vec3 inColor;

layout(location = 0) out vec4 outColor;

const vec3 LIGHT_DIRECTION = normalize(vec3(-0.4, -1.0, -0.3));
const vec3 AMBIENT = vec3(0.35);

void main() {
    float diffuse = max(dot(normalize(inNormal), -LIGHT_DIRECTION), 0.0);
    outColor = vec4(inColor * (AMBIENT + diffuse), 1.0);
}
