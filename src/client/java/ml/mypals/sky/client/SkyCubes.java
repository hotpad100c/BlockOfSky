package ml.mypals.sky.client;

import com.mojang.blaze3d.ProjectionType;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import ml.mypals.sky.SkyMod;
import ml.mypals.sky.mixin.client.CloudRendererAccessor;
import ml.mypals.sky.mixin.client.LevelRendererAccessor;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.CloudRenderer;
import net.minecraft.client.renderer.ProjectionMatrixBuffer;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.util.Optional;
import java.util.OptionalDouble;

public final class SkyCubes {
    private static final int FACE_SIZE = 1024;
    private static final Direction[] FACES = Direction.values();

    private static TextureTarget[] targets;
    private static SkyRenderer[] skyRenderers;
    private static ProjectionMatrixBuffer projection;
    private static boolean rendering;
    private static final Vector4f BACKDROP = new Vector4f(0.0F, 0.0F, 0.0F, 1.0F);

    private SkyCubes() {
    }

    public static void setBackdrop(Vector4f fogColor) {
        BACKDROP.set(fogColor.x, fogColor.y, fogColor.z, 1.0F);
    }

    public static Identifier faceTexture(Direction face) {
        return SkyMod.id("dynamic/sky_face_" + face.getName());
    }

    static boolean isRendering() {
        return rendering;
    }

    static void render(GpuBufferSlice skyFog, SkyRenderState state) {
        Minecraft mc = Minecraft.getInstance();
        if (targets == null) {
            targets = new TextureTarget[FACES.length];
            skyRenderers = new SkyRenderer[FACES.length];
            for (Direction face : FACES) {
                TextureTarget target = new TextureTarget("sky face " + face.getName(), FACE_SIZE, FACE_SIZE, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
                targets[face.ordinal()] = target;
                skyRenderers[face.ordinal()] = new SkyRenderer(mc.getTextureManager(), mc.getAtlasManager(), target);
                mc.getTextureManager().register(faceTexture(face), new FaceTexture(face));
            }
            projection = new ProjectionMatrixBuffer("sky faces");
        }

        float far = Math.max(mc.options.getEffectiveRenderDistance(), mc.options.cloudRange().get()) * 64.0F;
        Matrix4f faceProjection = new Matrix4f().setPerspective(Mth.HALF_PI, 1.0F, far, 0.05F, RenderSystem.getDevice().getDeviceInfo().isZZeroToOne());
        CloudRenderer clouds = ((LevelRendererAccessor) mc.levelRenderer).sky$getCloudRenderer();
        CloudStatus cloudStatus = mc.options.getCloudStatus();

        rendering = true;
        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(projection.getBuffer(faceProjection), ProjectionType.PERSPECTIVE);
        for (Direction face : FACES) {
            TextureTarget target = targets[face.ordinal()];
            RenderSystem.getModelViewStack().pushMatrix();
            RenderSystem.getModelViewStack().set(CubeFaces.lookingAt(face));
            RenderSystem.getDevice().createCommandEncoder()
                    .clearColorAndDepthTextures(target.getColorTexture(), BACKDROP, target.getDepthTexture(), 0.0);
            skyRenderers[face.ordinal()].render(skyFog, state);
            int cloudQuads = ((CloudRendererAccessor) clouds).sky$getQuadCount();
            if (cloudStatus != CloudStatus.OFF && cloudQuads > 0) {
                RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS).getBuffer(6 * cloudQuads);
                try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
                        .createRenderPass(() -> "sky face clouds", target.getColorTextureView(), Optional.empty(), target.getDepthTextureView(), OptionalDouble.empty())) {
                    clouds.render(cloudStatus, pass);
                }
            }
            RenderSystem.getModelViewStack().popMatrix();
        }
        RenderSystem.restoreProjectionMatrix();
        rendering = false;
    }

    static void close() {
        if (targets == null) {
            return;
        }
        for (Direction face : FACES) {
            skyRenderers[face.ordinal()].close();
            targets[face.ordinal()].destroyBuffers();
        }
        projection.close();
        targets = null;
    }

    private static final class FaceTexture extends AbstractTexture {
        private final Direction face;

        FaceTexture(Direction face) {
            this.face = face;
            this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
        }

        @Override
        public GpuTexture getTexture() {
            return targets[face.ordinal()].getColorTexture();
        }

        @Override
        public GpuTextureView getTextureView() {
            return targets[face.ordinal()].getColorTextureView();
        }

        @Override
        public void close() {
        }
    }
}
