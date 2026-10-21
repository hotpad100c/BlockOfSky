package ml.mypals.sky.mixin.client;

import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import ml.mypals.sky.client.SkyClient;
import ml.mypals.sky.client.SkyCubes;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class LevelRendererMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void sky$fogColor(GraphicsResourceAllocator resourceAllocator, boolean renderOutline, CameraRenderState cameraState,
                              GpuBufferSlice terrainFog, Vector4f fogColor, boolean shouldRenderSky, boolean consistentDepthRequired, CallbackInfo ci) {
        SkyCubes.setBackdrop(fogColor);
        SkyClient.setRenderingLevel(true);
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void sky$afterLevel(GraphicsResourceAllocator resourceAllocator, boolean renderOutline, CameraRenderState cameraState,
                                GpuBufferSlice terrainFog, Vector4f fogColor, boolean shouldRenderSky, boolean consistentDepthRequired, CallbackInfo ci) {
        SkyClient.setRenderingLevel(false);
    }
}
