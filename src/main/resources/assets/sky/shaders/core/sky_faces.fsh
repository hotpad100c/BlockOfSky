#version 150

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform sampler2D Sampler2;
uniform sampler2D Sampler3;
uniform sampler2D Sampler4;
uniform sampler2D Sampler5;

in vec3 viewDir;

out vec4 fragColor;

vec4 sampleFace(int face, vec2 uv) {
    if (face == 0) return texture(Sampler0, uv);
    if (face == 1) return texture(Sampler1, uv);
    if (face == 2) return texture(Sampler2, uv);
    if (face == 3) return texture(Sampler3, uv);
    if (face == 4) return texture(Sampler4, uv);
    return texture(Sampler5, uv);
}

void main() {
    vec3 d = viewDir;
    vec3 a = abs(d);
    vec3 forward;
    int face;
    if (a.x >= a.y && a.x >= a.z) {
        forward = vec3(sign(d.x), 0.0, 0.0);
        face = d.x > 0.0 ? 5 : 4;
    } else if (a.y >= a.z) {
        forward = vec3(0.0, sign(d.y), 0.0);
        face = d.y > 0.0 ? 1 : 0;
    } else {
        forward = vec3(0.0, 0.0, sign(d.z));
        face = d.z > 0.0 ? 3 : 2;
    }
    vec3 up = forward.y != 0.0 ? vec3(0.0, 0.0, 1.0) : vec3(0.0, 1.0, 0.0);
    vec3 right = normalize(cross(forward, up));
    vec3 trueUp = cross(right, forward);
    vec2 uv = vec2(dot(d, right), dot(d, trueUp)) / dot(d, forward) * 0.5 + 0.5;
    fragColor = sampleFace(face, uv);
}
