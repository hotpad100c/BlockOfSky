package ml.mypals.sky.client;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import ml.mypals.sky.SkyMod;
import net.minecraft.client.Camera;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

public final class SkyCubes {
    private static final int FACE_SIZE = 1024;
    private static final Direction[] FACES = Direction.values();

    private static TextureTarget[] targets;

    private SkyCubes() {
    }

    public static ResourceLocation faceTexture(Direction face) {
        return SkyMod.id("dynamic/sky_face_" + face.getName());
    }

    static void render(Camera camera, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        if (targets == null) {
            targets = new TextureTarget[FACES.length];
            for (Direction face : FACES) {
                targets[face.ordinal()] = new TextureTarget(FACE_SIZE, FACE_SIZE, true, Minecraft.ON_OSX);
                mc.getTextureManager().register(faceTexture(face), new FaceTexture(face));
            }
        }

        Vec3 eye = camera.getPosition();
        float renderDistance = mc.gameRenderer.getRenderDistance();
        boolean foggy = mc.level.effects().isFoggyAt(Mth.floor(eye.x), Mth.floor(eye.y)) || mc.gui.getBossOverlay().shouldCreateWorldFog();
        Runnable skyFog = () -> FogRenderer.setupFog(camera, FogRenderer.FogMode.FOG_SKY, renderDistance, foggy, partialTick);
        Matrix4f projection = new Matrix4f().setPerspective(Mth.HALF_PI, 1.0F, 0.05F, mc.gameRenderer.getDepthFar());

        FogRenderer.levelFogColor();
        float[] backdrop = RenderSystem.getShaderFogColor();
        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(projection, VertexSorting.DISTANCE_TO_ORIGIN);
        for (Direction face : FACES) {
            TextureTarget target = targets[face.ordinal()];
            target.setClearColor(backdrop[0], backdrop[1], backdrop[2], 1.0F);
            target.clear(Minecraft.ON_OSX);
            target.bindWrite(true);
            Matrix4f view = CubeFaces.lookingAt(face);
            RenderSystem.setShader(GameRenderer::getPositionShader);
            skyFog.run();
            mc.levelRenderer.renderSky(view, projection, partialTick, camera, foggy, skyFog);
            if (mc.options.getCloudsType() != CloudStatus.OFF) {
                FogRenderer.setupFog(camera, FogRenderer.FogMode.FOG_TERRAIN, Math.max(renderDistance, 32.0F), foggy, partialTick);
                mc.levelRenderer.renderClouds(new PoseStack(), view, projection, partialTick, eye.x, eye.y, eye.z);
            }
        }
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.restoreProjectionMatrix();
        skyFog.run();
        mc.getMainRenderTarget().bindWrite(true);
    }

    private static final class FaceTexture extends AbstractTexture {
        private final Direction face;

        FaceTexture(Direction face) {
            this.face = face;
        }

        @Override
        public int getId() {
            return targets == null ? 0 : targets[face.ordinal()].getColorTextureId();
        }

        @Override
        public void releaseId() {
        }

        @Override
        public void load(ResourceManager resourceManager) {
        }
    }
}
