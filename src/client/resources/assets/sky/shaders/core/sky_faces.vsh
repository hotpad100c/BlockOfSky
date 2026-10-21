#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

layout(location = 0) in vec3 Position;
#ifdef ITEM
// The corner of the item's cube, 0 to 1 on each axis.
layout(location = 1) in vec4 Color;
#endif

layout(location = 0) out vec3 viewDir;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    // In the world (the block, an item in the hand, on the ground, in a frame), the vertex is relative to the camera
    // in world axes: the faces show the sky behind them.
    viewDir = Position;
#ifdef ITEM
    // In a GUI there is no camera to look through (an orthographic projection): each face shows the sky on its side,
    // the top the sky overhead, the sides the horizon.
    if (ProjMat[3][3] == 1.0) {
        viewDir = Color.rgb * 2.0 - 1.0;
    }
#endif
}
