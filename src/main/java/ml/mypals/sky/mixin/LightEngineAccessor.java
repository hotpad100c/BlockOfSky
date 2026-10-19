package ml.mypals.sky.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(LightEngine.class)
public interface LightEngineAccessor {
    @Accessor("chunkSource")
    LightChunkGetter sky$chunkSource();

    @Invoker("getState")
    BlockState sky$getState(BlockPos pos);

    @Invoker("enqueueIncrease")
    void sky$enqueueIncrease(long pos, long entry);

    @Invoker("isEmptyShape")
    static boolean sky$isEmptyShape(BlockState state) {
        throw new AssertionError();
    }
}
