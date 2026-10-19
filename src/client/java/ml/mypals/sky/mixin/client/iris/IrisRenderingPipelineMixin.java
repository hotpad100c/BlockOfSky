package ml.mypals.sky.mixin.client.iris;

import ml.mypals.sky.client.ShaderSkyHoles;
import ml.mypals.sky.client.ShadowHoles;
import net.irisshaders.iris.gl.image.ImageHolder;
import net.irisshaders.iris.gl.sampler.GlSampler;
import net.irisshaders.iris.gl.sampler.SamplerHolder;
import net.irisshaders.iris.mixinterface.GpuTextureInterface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Supplier;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.IrisRenderingPipeline", remap = false)
public class IrisRenderingPipelineMixin {
    // The opaque scene is done and none of the pack's passes has run: the holes become sky for the deferred passes.
    @Inject(method = "beginHand", at = @At("HEAD"), require = 0)
    private void sky$beforeHand(CallbackInfo ci) {
        ShaderSkyHoles.draw();
    }

    // After the deferred passes, before the translucent surfaces: the holes get the block's depth back.
    @Inject(method = "beginTranslucents", at = @At("RETURN"), require = 0)
    private void sky$afterDeferred(CallbackInfo ci) {
        ShaderSkyHoles.restoreDepth();
    }

    // Before the composite passes: the holes are sky again for the pack's sky, fog and temporal reprojection.
    @Inject(method = "finalizeLevelRendering", at = @At("HEAD"), require = 0)
    private void sky$beforeComposite(CallbackInfo ci) {
        ShaderSkyHoles.farAgain();
    }

    // The shadow programs read the sky blocks' depth maps; programs that do not declare them are left alone.
    @Inject(method = "addGbufferOrShadowSamplers", at = @At("TAIL"), require = 0)
    private void sky$shadowHoleSamplers(SamplerHolder samplers, ImageHolder images, Supplier<?> flipped, boolean isShadowPass,
                                        boolean hasTexture, boolean hasLightmap, boolean hasOverlay, CallbackInfo ci) {
        if (!isShadowPass) {
            return;
        }
        for (int i = 0; i < ShadowHoles.SAMPLERS.length; i++) {
            samplers.addDynamicSampler(ShadowHoles.glId(i, texture -> ((GpuTextureInterface) texture).iris$getGlId()),
                    GlSampler.NEAREST, ShadowHoles.SAMPLERS[i]);
        }
    }
}
