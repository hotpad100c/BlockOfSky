package ml.mypals.sky.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import ml.mypals.sky.SkyMod;
import ml.mypals.sky.client.iris.IrisAccess;
import net.minecraft.client.renderer.ShaderInstance;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;

import java.io.IOException;

@EventBusSubscriber(modid = SkyMod.MOD_ID, value = Dist.CLIENT)
public final class Shaders {
    private static ShaderInstance skyFaces;
    private static ShaderInstance depthCopy;
    private static ShaderInstance hole;

    private Shaders() {
    }

    public static ShaderInstance skyFaces() {
        return skyFaces;
    }

    public static ShaderInstance depthCopy() {
        return depthCopy;
    }

    public static ShaderInstance hole() {
        return hole;
    }

    @SubscribeEvent
    static void onRegisterShaders(RegisterShadersEvent event) throws IOException {
        event.registerShader(new ShaderInstance(event.getResourceProvider(), SkyMod.id("sky_faces"), DefaultVertexFormat.POSITION),
                shader -> skyFaces = shader);
        event.registerShader(new ShaderInstance(event.getResourceProvider(), SkyMod.id("depth_copy"), DefaultVertexFormat.POSITION),
                shader -> depthCopy = allowDuringLevel(shader));
        event.registerShader(new ShaderInstance(event.getResourceProvider(), SkyMod.id("hole"), DefaultVertexFormat.POSITION),
                shader -> hole = allowDuringLevel(shader));
    }

    // Iris skips draws with shaders it does not know while it renders the level with a pack; the hole passes run there.
    private static ShaderInstance allowDuringLevel(ShaderInstance shader) {
        if (ModList.get().isLoaded("iris")) {
            IrisAccess.allowDuringLevel(shader);
        }
        return shader;
    }
}
