package ml.mypals.sky.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import ml.mypals.sky.SkyBlockEntity;
import ml.mypals.sky.SkyMod;
import ml.mypals.sky.client.iris.IrisAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;


public final class ShaderSkyHoles {
    private static final Identifier TEXTURE = SkyMod.id("textures/block/sky.png");
    private static final float NEARER = 1.0F - 1.0F / 4096.0F;

    private static @Nullable TextureTarget beforeDepth;
    private static @Nullable TextureTarget afterDepth;
    private static @Nullable TextureTarget currentDepth;
    private static @Nullable GpuTexture skySnapshot;
    private static @Nullable GpuTextureView skySnapshotView;
    private static boolean skyCaptured;
    private static @Nullable GpuBuffer screenQuad;
    private static boolean restorePending;
    private static boolean farPending;
    private static final List<Matrix4f> ITEMS = new ArrayList<>();
    public static int lastItems;

    private ShaderSkyHoles() {
    }

    /** A sky block item in the level this frame, by its pose: relative to the camera, in world axes, as the blocks. */
    static void addItem(Matrix4f pose) {
        ITEMS.add(new Matrix4f(pose));
    }

    static void clearItems() {
        ITEMS.clear();
    }

    static void captureSky() {
        skyCaptured = false;
        if (!RenderSystem.isOnRenderThread()) {
            return;
        }
        GpuTextureView colortex0 = IrisAccess.colortex0();
        if (colortex0 == null) {
            return;
        }
        GpuTexture source = colortex0.texture();
        int width = source.getWidth(0);
        int height = source.getHeight(0);
        if (skySnapshot == null || skySnapshot.getWidth(0) != width || skySnapshot.getHeight(0) != height || skySnapshot.getFormat() != source.getFormat()) {
            closeSnapshot();
            skySnapshot = RenderSystem.getDevice().createTexture(() -> "sky snapshot",
                    GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING, source.getFormat(), width, height, 1, 1);
            skySnapshotView = RenderSystem.getDevice().createTextureView(skySnapshot);
        }
        RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(source, skySnapshot, 0, 0, 0, 0, 0, width, height);
        skyCaptured = true;
    }

    public static void draw() {
        restorePending = false;
        farPending = false;
        lastItems = ITEMS.size();
        if (!RenderSystem.isOnRenderThread() || SkyClient.loaded().isEmpty() && ITEMS.isEmpty()) {
            return;
        }
        Vec3 camera = Minecraft.getInstance().gameRenderer.mainCamera().position();
        RenderType type = RenderTypes.entitySolid(TEXTURE);
        int quadCount = 0;
        MeshData mesh = null;
        ByteBufferBuilder bytes = new ByteBufferBuilder(4096);
        try {
            // Iris widens the entity format while it renders the level, as for any block entity.
            BufferBuilder builder = new BufferBuilder(bytes, PrimitiveTopology.QUADS, type.format());
            for (SkyBlockEntity sky : SkyClient.loaded()) {
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
                                OverlayTexture.NO_OVERLAY, LightCoordsUtil.FULL_BRIGHT, face.getStepX(), face.getStepY(), face.getStepZ()));
                        quadCount++;
                    }
                }
            }
            Vector3f corner = new Vector3f();
            Vector3f normal = new Vector3f();
            for (Matrix4f item : ITEMS) {
                for (Direction face : Direction.values()) {
                    item.transformDirection(normal.set(face.getStepX(), face.getStepY(), face.getStepZ())).normalize();
                    CubeFaces.corners(face, 0, 0, 0, (vx, vy, vz, u, v) -> {
                        item.transformPosition(vx, vy, vz, corner);
                        builder.addVertex(corner.x, corner.y, corner.z, -1, u, v,
                                OverlayTexture.NO_OVERLAY, LightCoordsUtil.FULL_BRIGHT, normal.x, normal.y, normal.z);
                    });
                    quadCount++;
                }
            }
            mesh = builder.build();
            if (mesh == null || quadCount == 0) {
                return;
            }

            RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
            beforeDepth = copyDepth(beforeDepth, "sky depth before", main);
            drawThroughPack(main, type, mesh, quadCount * 6);
            afterDepth = copyDepth(afterDepth, "sky depth after", main);

            GpuTextureView colortex0 = skyCaptured ? IrisAccess.colortex0() : null;
            RenderPipeline withSky = colortex0 != null && skySnapshotView != null && colortex0.texture().getFormat() == skySnapshotView.texture().getFormat()
                    ? SkyPipelines.holeWithSky(colortex0.texture().getFormat()) : null;
            if (withSky != null) {
                drawScreen(main, colortex0, withSky, skySnapshotView, "SkySnapshot");
            } else {
                drawScreen(main, null, SkyPipelines.HOLE, null, null);
            }
            restorePending = true;
            farPending = true;
        } finally {
            if (mesh != null) {
                mesh.close();
            }
            bytes.close();
        }
    }

    private static void drawThroughPack(RenderTarget main, RenderType type, MeshData mesh, int indexCount) {
        PreparedRenderType prepared = type.prepare();
        Matrix4f modelView = RenderSystem.getModelViewMatrixCopy();
        modelView.scale(NEARER);
        RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        try (GpuBuffer vertices = RenderSystem.getDevice().createBuffer(() -> "sky blocks", GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer())) {
            RenderPassDescriptor descriptor = RenderPassDescriptor.builder(() -> "sky blocks")
                    .withColorAttachment(main.getColorTextureView())
                    .withDepthAttachment(main.getDepthTextureView(), OptionalDouble.empty())
                    .build();
            try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(descriptor)) {
                pass.setPipeline(RenderSystem.getCompiledPipeline(type.pipeline()));
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("DynamicTransforms", RenderSystem.getDynamicUniforms().writeTransform(modelView));
                for (PreparedRenderType.Texture texture : prepared.textures()) {
                    pass.setUniform(texture.name(), texture.textureView(), texture.sampler());
                }
                pass.setVertexBuffer(0, vertices.slice());
                pass.setIndexBuffer(indices.getBuffer(indexCount), indices.type());
                pass.drawIndexed(indexCount, 1, 0, 0, 0);
            }
        }
    }

    public static void restoreDepth() {
        if (!restorePending || afterDepth == null || !RenderSystem.isOnRenderThread()) {
            return;
        }
        restorePending = false;
        drawScreen(Minecraft.getInstance().gameRenderer.mainRenderTarget(), null, SkyPipelines.HOLE_RESTORE, null, null);
    }

    public static void farAgain() {
        if (!farPending || afterDepth == null || !RenderSystem.isOnRenderThread()) {
            return;
        }
        farPending = false;
        RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        currentDepth = copyDepth(currentDepth, "sky depth now", main);
        drawScreen(main, null, SkyPipelines.HOLE_FAR_AGAIN, currentDepth.getDepthTextureView(), "CurrentDepth");
    }

    private static TextureTarget copyDepth(@Nullable TextureTarget target, String label, RenderTarget main) {
        if (target == null) {
            target = new TextureTarget(label, main.width, main.height, null, GpuFormat.D32_FLOAT);
        } else if (target.width != main.width || target.height != main.height) {
            target.resize(main.width, main.height);
        }
        target.copyDepthFrom(main);
        return target;
    }

    private static void drawScreen(RenderTarget main, @Nullable GpuTextureView color, RenderPipeline pipeline,
                                   @Nullable GpuTextureView extra, @Nullable String extraName) {
        if (screenQuad == null) {
            ByteBuffer data = ByteBuffer.allocateDirect(4 * 3 * Float.BYTES).order(ByteOrder.nativeOrder());
            data.asFloatBuffer().put(new float[]{-1, -1, 0, 1, -1, 0, 1, 1, 0, -1, 1, 0});
            screenQuad = RenderSystem.getDevice().createBuffer(() -> "sky screen", GpuBuffer.USAGE_VERTEX, data);
        }
        RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        RenderPassDescriptor.Builder descriptor = RenderPassDescriptor.builder(() -> "sky holes")
                .withDepthAttachment(main.getDepthTextureView(), OptionalDouble.empty());
        if (color != null) {
            descriptor.withColorAttachment(color);
        }
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(descriptor.build())) {
            pass.setPipeline(IrisAccess.compiledAsIs(pipeline));
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("Before", beforeDepth.getDepthTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            pass.setUniform("After", afterDepth.getDepthTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            if (extra != null) {
                pass.setUniform(extraName, extra, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            }
            pass.setVertexBuffer(0, screenQuad.slice());
            pass.setIndexBuffer(indices.getBuffer(6), indices.type());
            pass.drawIndexed(6, 1, 0, 0, 0);
        }
    }

    private static void closeSnapshot() {
        if (skySnapshotView != null) {
            skySnapshotView.close();
            skySnapshotView = null;
        }
        if (skySnapshot != null) {
            skySnapshot.close();
            skySnapshot = null;
        }
    }

    static void close() {
        restorePending = false;
        farPending = false;
        skyCaptured = false;
        if (screenQuad != null) {
            screenQuad.close();
            screenQuad = null;
        }
        for (TextureTarget target : new TextureTarget[]{beforeDepth, afterDepth, currentDepth}) {
            if (target != null) {
                target.destroyBuffers();
            }
        }
        beforeDepth = null;
        afterDepth = null;
        currentDepth = null;
        closeSnapshot();
    }
}
