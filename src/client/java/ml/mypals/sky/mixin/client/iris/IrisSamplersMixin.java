package ml.mypals.sky.mixin.client.iris;

import com.google.common.collect.ImmutableSet;
import ml.mypals.sky.client.ShadowHoles;
import ml.mypals.sky.client.ThroughDepth;
import net.irisshaders.iris.gl.sampler.GlSampler;
import net.irisshaders.iris.gl.sampler.SamplerHolder;
import net.irisshaders.iris.mixinterface.GpuTextureInterface;
import net.irisshaders.iris.samplers.IrisSamplers;
import net.irisshaders.iris.shadows.ShadowRenderTargets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(value = IrisSamplers.class, remap = false)
public class IrisSamplersMixin {
    @Inject(method = "addShadowSamplers", at = @At("TAIL"), require = 0)
    private static void sky$receiverSamplers(SamplerHolder samplers, ShadowRenderTargets targets, ImmutableSet<Integer> flipped,
                                             boolean separateHardwareSamplers, CallbackInfoReturnable<Boolean> cir) {
        if (!ThroughDepth.enabled()) {
            return;
        }
        samplers.addDynamicSampler(ShadowHoles.glId(ShadowHoles.FRONT_NEAR, texture -> ((GpuTextureInterface) texture).iris$getGlId()),
                GlSampler.NEAREST, ShadowHoles.SAMPLERS[ShadowHoles.FRONT_NEAR]);
        samplers.addDynamicSampler(ShadowHoles.glId(ShadowHoles.FAR, texture -> ((GpuTextureInterface) texture).iris$getGlId()),
                GlSampler.NEAREST, ShadowHoles.SAMPLERS[ShadowHoles.FAR]);
        samplers.addDynamicSampler(ThroughDepth::glId, GlSampler.NEAREST, ThroughDepth.SAMPLER);
    }
}
