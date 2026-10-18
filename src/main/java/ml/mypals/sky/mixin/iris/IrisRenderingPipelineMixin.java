package ml.mypals.sky.mixin.iris;

import ml.mypals.sky.client.ShaderSkyHoles;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = IrisRenderingPipeline.class, remap = false)
public class IrisRenderingPipelineMixin {
    // The opaque scene is done and none of the pack's passes has run: the holes become sky for the deferred passes.
    @Inject(method = "beginHand", at = @At("HEAD"))
    private void sky$beforeHand(CallbackInfo ci) {
        ShaderSkyHoles.draw();
    }

    // After the deferred passes, before the translucent surfaces: the holes get the block's depth back.
    @Inject(method = "beginTranslucents", at = @At("RETURN"))
    private void sky$afterDeferred(CallbackInfo ci) {
        ShaderSkyHoles.restoreDepth();
    }

    // Before the composite passes: the holes are sky again for the pack's sky, fog and temporal reprojection.
    @Inject(method = "finalizeLevelRendering", at = @At("HEAD"))
    private void sky$beforeComposite(CallbackInfo ci) {
        ShaderSkyHoles.farAgain();
    }
}
