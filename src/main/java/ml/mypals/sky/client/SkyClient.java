package ml.mypals.sky.client;

import ml.mypals.sky.SkyBlock;
import ml.mypals.sky.SkyBlockEntity;
import ml.mypals.sky.SkyMod;
import ml.mypals.sky.client.iris.IrisAccess;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

@EventBusSubscriber(modid = SkyMod.MOD_ID, value = Dist.CLIENT)
public final class SkyClient {
    private static final boolean IRIS = ModList.get().isLoaded("iris");
    private static boolean chunksBuiltSolid;

    private SkyClient() {
    }

    public static boolean packInUse() {
        return IRIS && IrisAccess.packInUse();
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        if (IRIS) {
            SkyBlock.solidWhileShowing = IrisAccess::packInUse;
        }
        NeoForge.EVENT_BUS.addListener(SkyClient::onClientTick);
        NeoForge.EVENT_BUS.addListener(SkyClient::onRenderLevelStage);
    }

    @SubscribeEvent
    static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(SkyMod.SKY_ENTITY.get(), context -> new SkyBlockRenderer());
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        boolean solid = packInUse();
        if (solid != chunksBuiltSolid) {
            chunksBuiltSolid = solid;
            Minecraft mc = Minecraft.getInstance();
            if (mc.level != null && !SkyBlockEntity.loadedOnClient().isEmpty()) {
                mc.levelRenderer.allChanged();
            }
        }
    }

    private static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY || SkyBlockEntity.loadedOnClient().isEmpty()) {
            return;
        }
        if (packInUse()) {
            ShaderSkyHoles.captureSky();
        } else {
            SkyCubes.render(event.getCamera(), event.getPartialTick().getGameTimeDeltaPartialTick(false));
        }
    }
}
