package ml.mypals.sky.mixin.client;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import ml.mypals.sky.client.SkyClient;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SkyRenderer.class)
public class SkyRendererMixin {
    // With a pack, the sky is gbuffers_skybasic and skytextured here, before any terrain.
    @Inject(method = "render", at = @At("RETURN"))
    private void sky$afterSky(GpuBufferSlice skyFog, SkyRenderState state, CallbackInfo ci) {
        SkyClient.afterSky(skyFog, state);
    }
}
