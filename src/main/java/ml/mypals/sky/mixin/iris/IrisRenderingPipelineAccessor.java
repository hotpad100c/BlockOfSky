package ml.mypals.sky.mixin.iris;

import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.targets.RenderTargets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = IrisRenderingPipeline.class, remap = false)
public interface IrisRenderingPipelineAccessor {
    @Accessor("renderTargets")
    RenderTargets sky$getRenderTargets();
}
