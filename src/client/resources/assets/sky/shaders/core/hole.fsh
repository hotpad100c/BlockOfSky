#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:projection.glsl>

// The depth just before and just after the sky blocks were drawn again through the shader pack's own program. Where
// they differ, the pack drew a block: those are the holes, wherever its vertex shader put them.
uniform sampler2D Before;
uniform sampler2D After;
#ifdef SKY_COLOR
// The pack's colortex0 right after its sky was drawn, before any terrain.
uniform sampler2D SkySnapshot;
layout(location = 0) out vec4 fragColor;
#endif
#ifdef FAR_AGAIN
// The depth after the translucent surfaces.
uniform sampler2D CurrentDepth;
#endif

// Default: sky for the deferred passes (far depth, and with SKY_COLOR the pack's sky colour). RESTORE_DEPTH: the
// block's depth back, for the translucent surfaces. FAR_AGAIN: sky again for the composite passes, where nothing
// translucent was drawn in front.
void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    float after = texelFetch(After, pixel, 0).r;
    if (after == texelFetch(Before, pixel, 0).r) {
        discard;
    }
#ifdef RESTORE_DEPTH
    gl_FragDepth = after;
#else
#ifdef FAR_AGAIN
    if (texelFetch(CurrentDepth, pixel, 0).r != after) {
        discard;
    }
#endif
    // Iris undoes the reversed depth of 26.3 while a pack renders the level, so the far plane is read off the
    // projection: a standard projection has m22 < 0 and the far plane at depth 1.
    gl_FragDepth = ProjMat[2][2] < 0.0 ? 1.0 : 0.0;
#ifdef SKY_COLOR
    fragColor = texelFetch(SkySnapshot, pixel, 0);
#endif
#endif
}
