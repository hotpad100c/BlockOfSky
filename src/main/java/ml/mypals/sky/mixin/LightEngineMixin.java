package ml.mypals.sky.mixin;

import ml.mypals.sky.SkyBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LightEngine.class)
public class LightEngineMixin {
    // A sky block that shows the sky passes light as air does, but is a sky light source: placing it, removing it or
    // turning it on or off must reach the light engine, which skips changes it sees no light difference in.
    @Inject(method = "hasDifferentLightProperties", at = @At("HEAD"), cancellable = true)
    private static void sky$skySource(BlockState oldState, BlockState newState, CallbackInfoReturnable<Boolean> cir) {
        if (SkyBlock.showsSky(oldState) != SkyBlock.showsSky(newState)) {
            cir.setReturnValue(true);
        }
    }
}
