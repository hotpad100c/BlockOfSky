package ml.mypals.sky.smoke.mixin;

import com.mojang.blaze3d.pipeline.TextureTarget;
import ml.mypals.sky.client.SkyCubes;
import ml.mypals.sky.smoke.Capture;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SkyCubes.class, remap = false)
public abstract class SkyCubesCapture {
    @Shadow private static TextureTarget[] targets;

    @Inject(method = "render", at = @At("RETURN"))
    private static void sky$rendered(CallbackInfo ci) {
        if (Capture.active() && targets != null) {
            for (Direction face : Direction.values()) {
                Capture.color(targets[face.ordinal()].getColorTextureId(), "n1-cube-" + face.getName());
            }
            Capture.frameDone();
        }
    }
}
