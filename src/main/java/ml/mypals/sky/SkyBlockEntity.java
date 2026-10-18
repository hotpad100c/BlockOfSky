package ml.mypals.sky;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class SkyBlockEntity extends BlockEntity {
    private static final Set<SkyBlockEntity> LOADED_ON_CLIENT = ConcurrentHashMap.newKeySet();

    public SkyBlockEntity(BlockPos pos, BlockState state) {
        super(SkyMod.SKY_ENTITY.get(), pos, state);
    }

    public static Collection<SkyBlockEntity> loadedOnClient() {
        return Collections.unmodifiableSet(LOADED_ON_CLIENT);
    }

    public boolean showsSky() {
        return !isRemoved() && SkyBlock.showsSky(getBlockState());
    }

    public boolean faceShows(Direction face) {
        return level == null || !SkyBlock.showsSky(level.getBlockState(getBlockPos().relative(face)));
    }

    @Override
    public void onLoad() {
        super.onLoad();
        if (level != null && level.isClientSide()) {
            LOADED_ON_CLIENT.add(this);
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        LOADED_ON_CLIENT.remove(this);
    }

    @Override
    public void onChunkUnloaded() {
        super.onChunkUnloaded();
        LOADED_ON_CLIENT.remove(this);
    }
}
