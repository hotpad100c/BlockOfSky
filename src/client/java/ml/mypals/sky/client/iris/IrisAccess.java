package ml.mypals.sky.client.iris;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.opengl.FrameBufferCache;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import ml.mypals.sky.client.ThroughDepth;
import ml.mypals.sky.mixin.client.FrontendGpuDeviceAccessor;
import ml.mypals.sky.mixin.client.GlDeviceAccessor;
import ml.mypals.sky.mixin.client.iris.IrisRenderingPipelineAccessor;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.api.v0.IrisApi;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shadows.ShadowRenderTargets;
import net.irisshaders.iris.targets.RenderTarget;
import net.irisshaders.iris.vertices.ImmediateState;
import org.jspecify.annotations.Nullable;

import java.io.IOException;

public final class IrisAccess {
    private static @Nullable PackTexture wrapped;
    private static @Nullable GpuTextureView wrappedView;

    private IrisAccess() {
    }

    /**
     * One of our own pipelines, as it is. While a pack renders the level, Iris looks up a program of the pack for any
     * pipeline, finds none for ours and logs it once per pipeline before falling back to it.
     */
    public static CompiledRenderPipeline compiledAsIs(RenderPipeline pipeline) {
        boolean bypass = ImmediateState.bypass;
        ImmediateState.bypass = true;
        try {
            return RenderSystem.getCompiledPipeline(pipeline);
        } finally {
            ImmediateState.bypass = bypass;
        }
    }

    /** Compiles the pack's programs again: the shadow programs are patched for the sky blocks when they compile. */
    public static void reload() {
        try {
            Iris.reload();
        } catch (IOException e) {
            ThroughDepth.LOGGER.error("Shadow holes: could not reload the shader pack", e);
        }
    }

    /** Draws with our own and vanilla pipelines as they are, not the pack's programs. */
    public static void runAsIs(Runnable draw) {
        boolean bypass = ImmediateState.bypass;
        ImmediateState.bypass = true;
        try {
            draw.run();
        } finally {
            ImmediateState.bypass = bypass;
        }
    }

    public static boolean renderingShadowPass() {
        return IrisApi.getInstance().isRenderingShadowPass();
    }

    public static boolean packInUse() {
        return IrisApi.getInstance().isShaderPackInUse();
    }

    // Iris keeps its colortex as bare GL textures; wrapped as a renderpearl texture, render passes can copy, sample and
    // draw into colortex0. The wrapper never deletes the texture: Iris owns it.
    public static @Nullable GpuTextureView colortex0() {
        if (!(Iris.getPipelineManager().getPipelineNullable() instanceof IrisRenderingPipeline pipeline)) {
            return null;
        }
        RenderTarget target = ((IrisRenderingPipelineAccessor) pipeline).sky$getRenderTargets().get(0);
        if (target == null) {
            return null;
        }
        GpuFormat format = format(target.getInternalFormat().name());
        if (format == null) {
            return null;
        }
        int id = target.getMainTexture();
        int width = target.getWidth();
        int height = target.getHeight();
        if (wrapped == null || wrapped.glId() != id || wrapped.getWidth(0) != width || wrapped.getHeight(0) != height || wrapped.getFormat() != format) {
            if (wrappedView != null) {
                wrappedView.close();
            }
            FrameBufferCache cache = ((GlDeviceAccessor) ((FrontendGpuDeviceAccessor) RenderSystem.getDevice()).sky$getBackend()).sky$frameBufferCache();
            wrapped = new PackTexture(format, width, height, id, cache);
            wrappedView = RenderSystem.getDevice().createTextureView(wrapped);
        }
        return wrappedView;
    }

    public static @Nullable GpuTexture shadowDepth() {
        if (!(Iris.getPipelineManager().getPipelineNullable() instanceof IrisRenderingPipeline pipeline)) {
            return null;
        }
        ShadowRenderTargets targets = ((IrisRenderingPipelineAccessor) pipeline).sky$getShadowRenderTargets();
        return targets == null ? null : targets.getDepthTexture();
    }

    /**
     * Binds Iris's framebuffer on the shadow map. In the shadow pass Iris ignores the framebuffers of render passes and
     * draws into whatever is bound, and a texture copy leaves its own framebuffer bound.
     */
    public static void bindShadowFramebuffer() {
        if (Iris.getPipelineManager().getPipelineNullable() instanceof IrisRenderingPipeline pipeline) {
            ShadowRenderTargets targets = ((IrisRenderingPipelineAccessor) pipeline).sky$getShadowRenderTargets();
            if (targets != null) {
                targets.getDepthSourceFb().bind();
            }
        }
    }

    private static @Nullable GpuFormat format(String irisFormat) {
        return switch (irisFormat) {
            case "RGBA", "RGBA8" -> GpuFormat.RGBA8_UNORM;
            case "RGBA16" -> GpuFormat.RGBA16_UNORM;
            case "RGBA16F" -> GpuFormat.RGBA16_FLOAT;
            case "RGBA32F" -> GpuFormat.RGBA32_FLOAT;
            case "R11F_G11F_B10F" -> GpuFormat.RG11B10_FLOAT;
            case "RGB10_A2" -> GpuFormat.RGB10A2_UNORM;
            default -> null;
        };
    }

    private static final class PackTexture extends GlTexture {
        PackTexture(GpuFormat format, int width, int height, int id, FrameBufferCache cache) {
            super(GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT,
                    "sky colortex0", format, width, height, 1, 1, id, cache);
        }

        @Override
        public void close() {
        }
    }
}
