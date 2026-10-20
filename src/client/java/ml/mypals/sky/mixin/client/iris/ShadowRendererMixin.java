package ml.mypals.sky.mixin.client.iris;

import ml.mypals.sky.client.ShadowHoles;
import ml.mypals.sky.client.ThroughDepth;
import ml.mypals.sky.client.iris.IrisAccess;
import net.irisshaders.iris.shadows.ShadowRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(value = ShadowRenderer.class, remap = false)
public class ShadowRendererMixin {
    // The shadow map is clear and the shadow matrices are set; no terrain has been drawn yet.
    @Inject(method = "renderShadows", at = @At(value = "INVOKE",
            target = "Lnet/irisshaders/iris/pipeline/IrisRenderingPipeline;setPhase(Lnet/irisshaders/iris/pipeline/WorldRenderingPhase;)V",
            ordinal = 0), require = 0)
    private void sky$beforeTerrain(CallbackInfo ci) {
        var depth = IrisAccess.shadowDepth();
        if (depth != null) {
            ShadowHoles.prepare(depth, ShadowRenderer.MODELVIEW);
        }
    }

    // The shadow pass is done: what it wrote into the depth beyond the sky blocks is read from here on.
    @Inject(method = "renderShadows", at = @At("TAIL"), require = 0)
    private void sky$afterShadows(CallbackInfo ci) {
        if (ThroughDepth.enabled()) {
            ThroughDepth.afterShadowPass();
        }
    }
}
