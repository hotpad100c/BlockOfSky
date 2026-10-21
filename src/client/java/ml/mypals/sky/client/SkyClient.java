package ml.mypals.sky.client;

import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import ml.mypals.sky.SkyBlock;
import ml.mypals.sky.SkyBlockEntity;
import ml.mypals.sky.SkyMod;
import ml.mypals.sky.client.iris.IrisAccess;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientBlockEntityEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.client.renderer.special.SpecialModelRenderers;
import net.minecraft.client.renderer.state.level.SkyRenderState;

import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class SkyClient implements ClientModInitializer {
    private static final boolean IRIS = FabricLoader.getInstance().isModLoaded("iris");
    private static final Set<SkyBlockEntity> LOADED = ConcurrentHashMap.newKeySet();
    private static boolean chunksBuiltSolid;
    private static boolean renderingLevel;

    public static boolean packInUse() {
        return IRIS && IrisAccess.packInUse();
    }

    /** Whether the level itself is being rendered (not the first-person hand, not a GUI). */
    public static boolean renderingLevel() {
        return renderingLevel;
    }

    public static void setRenderingLevel(boolean rendering) {
        renderingLevel = rendering;
        if (rendering) {
            ShaderSkyHoles.clearItems();
        }
    }

    public static Collection<SkyBlockEntity> loaded() {
        return Collections.unmodifiableSet(LOADED);
    }

    @Override
    public void onInitializeClient() {
        SkyPipelines.init();
        SpecialModelRenderers.ID_MAPPER.put(SkyMod.id("sky"), SkyItemRenderer.MAP_CODEC);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> SkyCommands.register(dispatcher));
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
        if (LOADED.isEmpty() && !SkyItemRenderer.drawnRecently() || SkyCubes.isRendering()) {
            return;
        }
        if (packInUse()) {
            ShaderSkyHoles.captureSky();
            // The item in the hand and in a GUI shows the sky cubes: the vanilla sky, drawn past the pack.
            if (SkyItemRenderer.cubesDrawnRecently()) {
                IrisAccess.runAsIs(() -> SkyCubes.render(skyFog, state));
            }
        } else {
            SkyCubes.render(skyFog, state);
        }
    }
}
