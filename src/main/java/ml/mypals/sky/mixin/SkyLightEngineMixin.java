package ml.mypals.sky.mixin;

import ml.mypals.sky.SkyBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.level.lighting.SkyLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


@Mixin(SkyLightEngine.class)
public abstract class SkyLightEngineMixin {
    @Inject(method = "checkNode", at = @At("TAIL"))
    private void sky$emitAfterChange(long pos, CallbackInfo ci) {
        LightEngineAccessor engine = (LightEngineAccessor) this;
        BlockState state = engine.sky$getState(BlockPos.of(pos));
        if (SkyBlock.showsSky(state)) {
            sky$emit(engine, pos, state);
        }
    }

    @Inject(method = "propagateLightSources", at = @At("TAIL"))
    private void sky$emitInChunk(ChunkPos pos, CallbackInfo ci) {
        LightEngineAccessor engine = (LightEngineAccessor) this;
        if (engine.sky$chunkSource().getChunkForLighting(pos.x(), pos.z()) instanceof ChunkAccess chunk) {
            chunk.findBlocks(SkyBlock::showsSky, (blockPos, state) -> sky$emit(engine, blockPos.asLong(), state));
        }
    }

    @Unique
    private static void sky$emit(LightEngineAccessor engine, long pos, BlockState state) {
        engine.sky$enqueueIncrease(pos, LightEngine.QueueEntry.increaseLightFromEmission(15, LightEngineAccessor.sky$isEmptyShape(state)));
    }
}
