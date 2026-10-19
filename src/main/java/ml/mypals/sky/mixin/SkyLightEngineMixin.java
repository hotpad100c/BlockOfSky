package ml.mypals.sky.mixin;

import ml.mypals.sky.SkyBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.level.lighting.SkyLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A sky block that shows the sky is a source of sky light, like the open sky: full sky light on the block itself,
 * spreading from it as from any light source. So shader packs, which fade the sun out where there is no sky light, let
 * the sun in through it, and mobs and grass see it as open sky.
 */
@Mixin(SkyLightEngine.class)
public abstract class SkyLightEngineMixin {
    // A block changed, or was turned on or off: the engine has handled it as any block, the sky block then shines.
    @Inject(method = "checkNode", at = @At("TAIL"))
    private void sky$emitAfterChange(long pos, CallbackInfo ci) {
        LightEngineAccessor engine = (LightEngineAccessor) (Object) this;
        BlockState state = engine.sky$getState(BlockPos.of(pos));
        if (SkyBlock.showsSky(state)) {
            sky$emit(engine, pos, state);
        }
    }

    // A chunk gets its light for the first time: its sky blocks shine, as the block light engine does for its sources.
    @Inject(method = "propagateLightSources", at = @At("TAIL"))
    private void sky$emitInChunk(ChunkPos chunkPos, CallbackInfo ci) {
        LightEngineAccessor engine = (LightEngineAccessor) (Object) this;
        if (engine.sky$chunkSource().getChunkForLighting(chunkPos.x(), chunkPos.z()) instanceof ChunkAccess chunk) {
            chunk.findBlocks(SkyBlock::showsSky, (pos, state) -> sky$emit(engine, pos.asLong(), state));
        }
    }

    private static void sky$emit(LightEngineAccessor engine, long pos, BlockState state) {
        engine.sky$enqueueIncrease(pos, LightEngine.QueueEntry.increaseLightFromEmission(15, LightEngineAccessor.sky$isEmptyShape(state)));
    }
}
