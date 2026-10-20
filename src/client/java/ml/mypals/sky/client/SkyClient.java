package ml.mypals.sky.client;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import ml.mypals.sky.SkyBlock;
import ml.mypals.sky.SkyBlockEntity;
import ml.mypals.sky.SkyMod;
import ml.mypals.sky.client.iris.IrisAccess;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientBlockEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.client.renderer.state.level.SkyRenderState;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class SkyClient implements ClientModInitializer {
    private static final boolean IRIS = FabricLoader.getInstance().isModLoaded("iris");
    private static final Set<SkyBlockEntity> LOADED = ConcurrentHashMap.newKeySet();
    private static boolean chunksBuiltSolid;

    public static boolean packInUse() {
        return IRIS && IrisAccess.packInUse();
    }

    public static Collection<SkyBlockEntity> loaded() {
        return Collections.unmodifiableSet(LOADED);
    }

    @Override
    public void onInitializeClient() {
        SkyPipelines.init();
        BlockEntityRenderers.register(SkyMod.SKY_ENTITY, context -> new SkyBlockRenderer());
        ClientBlockEntityEvents.BLOCK_ENTITY_LOAD.register((blockEntity, level) -> {
            if (blockEntity instanceof SkyBlockEntity sky) {
                LOADED.add(sky);
            }
        });
        ClientBlockEntityEvents.BLOCK_ENTITY_UNLOAD.register((blockEntity, level) -> LOADED.remove(blockEntity));
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> ThroughDepth.detect());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            SkyCubes.close();
            ShaderSkyHoles.close();
            ShadowHoles.close();
        });
        if (IRIS) {
            SkyBlock.solidWhileShowing = IrisAccess::packInUse;
            ClientTickEvents.END_CLIENT_TICK.register(client -> {
                boolean solid = IrisAccess.packInUse();
                if (solid != chunksBuiltSolid) {
                    chunksBuiltSolid = solid;
                    if (client.level != null && !LOADED.isEmpty()) {
                        client.levelExtractor.allChanged();
                    }
                }
            });
        }
    }

    public static void afterSky(GpuBufferSlice skyFog, SkyRenderState state) {
        if (LOADED.isEmpty() || SkyCubes.isRendering()) {
            return;
        }
        if (packInUse()) {
            ShaderSkyHoles.captureSky();
        } else {
            SkyCubes.render(skyFog, state);
        }
    }
}
