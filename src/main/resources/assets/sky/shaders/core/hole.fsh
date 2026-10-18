#version 150

// The depth just before and just after the sky blocks were drawn again through the shader pack's own program. Where
// they differ, the pack drew a block: those are the holes, wherever its vertex shader put them.
uniform sampler2D Before;
uniform sampler2D After;
// The pack's colortex0 right after its sky was drawn, before any terrain.
uniform sampler2D SkySnapshot;
// The depth after the translucent surfaces.
uniform sampler2D CurrentDepth;

// 0: sky for the deferred passes (far depth, the pack's sky colour).
// 1: the block's depth back, for the translucent surfaces.
// 2: sky again for the composite passes, where nothing translucent was drawn in front.
uniform int Mode;

out vec4 fragColor;

void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    float after = texelFetch(After, pixel, 0).r;
    if (after == texelFetch(Before, pixel, 0).r) {
        discard;
    }
    if (Mode == 1) {
        gl_FragDepth = after;
        fragColor = vec4(0.0);
        return;
    }
    if (Mode == 2 && texelFetch(CurrentDepth, pixel, 0).r != after) {
        discard;
    }
    gl_FragDepth = 1.0;
    fragColor = texelFetch(SkySnapshot, pixel, 0);
}
