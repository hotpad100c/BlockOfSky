package ml.mypals.sky.client.iris;

import ml.mypals.sky.mixin.iris.IrisRenderingPipelineAccessor;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.api.v0.IrisApi;
import net.irisshaders.iris.compat.SkipList;
import net.irisshaders.iris.mixinterface.ShaderInstanceInterface;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.targets.RenderTarget;
import net.minecraft.client.renderer.ShaderInstance;
import org.jetbrains.annotations.Nullable;

public final class IrisAccess {
    private IrisAccess() {
    }

    public static boolean packInUse() {
        return IrisApi.getInstance().isShaderPackInUse();
    }

    // With allowUnknownShaders off (the default), Iris skips every draw with a shader it does not know while the level
    // renders with a pack. NONE_FORCE exempts this shader.
    public static void allowDuringLevel(ShaderInstance shader) {
        ((ShaderInstanceInterface) shader).setShouldSkip(SkipList.NONE_FORCE);
    }

    public static @Nullable ColorTarget colortex0() {
        if (!(Iris.getPipelineManager().getPipelineNullable() instanceof IrisRenderingPipeline pipeline)) {
            return null;
        }
        RenderTarget target = ((IrisRenderingPipelineAccessor) pipeline).sky$getRenderTargets().get(0);
        return target == null ? null : new ColorTarget(target.getMainTexture(), target.getWidth(), target.getHeight());
    }

    public record ColorTarget(int id, int width, int height) {
    }
}
