#version 150

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

in vec3 Position;

out vec3 viewDir;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    viewDir = Position;
}
