package ml.mypals.sky.mixin.client;

import com.mojang.renderpearl.backend.opengl.GlStateManager;
import ml.mypals.sky.client.ShadowHoles;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "com.mojang.renderpearl.backend.opengl.GlCommandEncoder")
public class GlCommandEncoderMixin {
    // The sky blocks are drawn into the shadow map only for their depth: the pack's shadow colors stay as they are.
    @Inject(method = "setupDraw", at = @At("RETURN"))
    private void sky$depthOnly(CallbackInfo ci) {
        if (ShadowHoles.drawing()) {
            GlStateManager._colorMask(0);
        }
    }
}
