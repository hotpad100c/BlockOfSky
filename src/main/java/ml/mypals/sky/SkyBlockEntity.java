package ml.mypals.sky;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public class SkyBlockEntity extends BlockEntity {
    public SkyBlockEntity(BlockPos pos, BlockState state) {
        super(SkyMod.SKY_ENTITY, pos, state);
    }

    public boolean showsSky() {
        return !isRemoved() && SkyBlock.showsSky(getBlockState());
    }

    public boolean faceShows(Direction face) {
        return level == null || !SkyBlock.showsSky(level.getBlockState(getBlockPos().relative(face)));
    }
}
