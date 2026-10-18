package ml.mypals.sky.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import ml.mypals.sky.SkyBlockEntity;
import ml.mypals.sky.SkyMod;
import ml.mypals.sky.client.iris.IrisAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fStack;
import org.lwjgl.opengl.GL30;

/**
 * The sky block with an Iris shader pack. The pack draws the block as a solid cube, so its buffers hold the block and
 * not what is behind it. The pixels where the block shows then become "sky" for the pack, which draws its own sky
 * there:
 * <ol>
 *     <li>{@link #captureSky}, right after the sky: copy the pack's colortex0, which holds only its sky then.</li>
 *     <li>{@link #draw}, at Iris's {@code beginHand} (opaque scene done, before the deferred passes): copy the depth,
 *     draw the block again with the entity render type, whose shader Iris replaces with the pack's, slightly nearer
 *     the camera, and copy the depth again. The pixels whose depth changed are where the pack drew the block, wherever
 *     its vertex shader put them (render scale, TAA jitter, ...). They get the far depth and the copied sky.</li>
 *     <li>{@link #restoreDepth}, after the deferred passes: the block's depth again, so translucent surfaces behind
 *     the block fail the depth test and those in front still draw.</li>
 *     <li>{@link #farAgain}, before the composite passes: the far depth again where nothing translucent was drawn in
 *     front, so the pack's sky, fog and temporal reprojection treat them as sky.</li>
 * </ol>
 * The depths are copied into float colour textures by a shader, whatever format the main depth has.
 */
public final class ShaderSkyHoles {
    private static final ResourceLocation TEXTURE = SkyMod.id("textures/block/sky.png");
    private static final float NEARER = 1.0F - 1.0F / 4096.0F;

    private static int beforeDepth;
    private static int afterDepth;
    private static int currentDepth;
    private static int depthWidth;
    private static int depthHeight;
    private static int skySnapshot;
    private static int snapshotWidth;
    private static int snapshotHeight;
    private static boolean skyCaptured;
    private static boolean restorePending;
    private static boolean farPending;

    private ShaderSkyHoles() {
    }

    static void captureSky() {
        skyCaptured = false;
        IrisAccess.ColorTarget colortex0 = IrisAccess.colortex0();
        if (colortex0 == null) {
            return;
        }
        if (skySnapshot == 0 || snapshotWidth != colortex0.width() || snapshotHeight != colortex0.height()) {
            snapshotWidth = colortex0.width();
            snapshotHeight = colortex0.height();
            skySnapshot = Gl.texture(skySnapshot, GL30.GL_RGBA16F, snapshotWidth, snapshotHeight);
        }
        Gl.copyColor(colortex0.id(), skySnapshot, snapshotWidth, snapshotHeight);
        skyCaptured = true;
    }

    public static void draw() {
        restorePending = false;
        farPending = false;
        if (!RenderSystem.isOnRenderThread() || Shaders.hole() == null) {
            return;
        }
        MeshData mesh = buildBlocks();
        if (mesh == null) {
            return;
        }

        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        ensureDepthCopies(main.width, main.height);
        copyDepth(main, beforeDepth);
        drawThroughPack(mesh);
        copyDepth(main, afterDepth);

        IrisAccess.ColorTarget colortex0 = skyCaptured ? IrisAccess.colortex0() : null;
        boolean withColor = colortex0 != null && colortex0.width() == snapshotWidth && colortex0.height() == snapshotHeight;
        Gl.drawScreen(holeShader(0, withColor ? skySnapshot : afterDepth), withColor ? colortex0.id() : 0, main.getDepthTextureId(), main.width, main.height);
        restorePending = true;
        farPending = true;
    }

    private static MeshData buildBlocks() {
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        // Iris widens the entity format while it renders the level, as for any block entity.
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.NEW_ENTITY);
        for (SkyBlockEntity sky : SkyBlockEntity.loadedOnClient()) {
            if (!sky.showsSky()) {
                continue;
            }
            BlockPos pos = sky.getBlockPos();
            float x = (float) (pos.getX() - camera.x);
            float y = (float) (pos.getY() - camera.y);
            float z = (float) (pos.getZ() - camera.z);
            for (Direction face : Direction.values()) {
                if (sky.faceShows(face)) {
                    CubeFaces.corners(face, x, y, z, (vx, vy, vz, u, v) -> builder.addVertex(vx, vy, vz, -1, u, v,
                            OverlayTexture.NO_OVERLAY, LightTexture.FULL_BRIGHT, face.getStepX(), face.getStepY(), face.getStepZ()));
                }
            }
        }
        return builder.build();
    }

    private static void drawThroughPack(MeshData mesh) {
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        modelView.scale(NEARER);
        RenderSystem.applyModelViewMatrix();
        RenderType.entitySolid(TEXTURE).draw(mesh);
        modelView.popMatrix();
        RenderSystem.applyModelViewMatrix();
    }

    public static void restoreDepth() {
        if (!restorePending || !RenderSystem.isOnRenderThread()) {
            return;
        }
        restorePending = false;
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        Gl.drawScreen(holeShader(1, afterDepth), 0, main.getDepthTextureId(), main.width, main.height);
    }

    public static void farAgain() {
        if (!farPending || !RenderSystem.isOnRenderThread()) {
            return;
        }
        farPending = false;
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        copyDepth(main, currentDepth);
        Gl.drawScreen(holeShader(2, afterDepth), 0, main.getDepthTextureId(), main.width, main.height);
    }

    private static ShaderInstance holeShader(int mode, int skyTexture) {
        ShaderInstance shader = Shaders.hole();
        shader.setSampler("Before", beforeDepth);
        shader.setSampler("After", afterDepth);
        shader.setSampler("SkySnapshot", skyTexture);
        shader.setSampler("CurrentDepth", currentDepth);
        shader.safeGetUniform("Mode").set(mode);
        return shader;
    }

    private static void ensureDepthCopies(int width, int height) {
        if (beforeDepth != 0 && depthWidth == width && depthHeight == height) {
            return;
        }
        depthWidth = width;
        depthHeight = height;
        beforeDepth = Gl.texture(beforeDepth, GL30.GL_R32F, width, height);
        afterDepth = Gl.texture(afterDepth, GL30.GL_R32F, width, height);
        currentDepth = Gl.texture(currentDepth, GL30.GL_R32F, width, height);
    }

    private static void copyDepth(RenderTarget main, int target) {
        ShaderInstance shader = Shaders.depthCopy();
        shader.setSampler("Depth", main.getDepthTextureId());
        Gl.drawScreen(shader, target, 0, main.width, main.height);
    }
}
