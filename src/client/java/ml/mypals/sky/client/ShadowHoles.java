package ml.mypals.sky.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.textures.GpuTexture;
import ml.mypals.sky.SkyBlockEntity;
import ml.mypals.sky.client.iris.IrisAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.rendertype.PreparedRenderType;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.util.LightCoordsUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.function.IntSupplier;

/**
 * Shadows through the sky block with an Iris shader pack. Every shadow fragment program of the pack is patched to
 * discard the fragments in a depth range per shadow texel (see {@code CommonTransformerMixin}); this class fills the
 * three depth maps that range is read from, at the start of the shadow pass and before any terrain:
 * <ul>
 *     <li>{@link #FRONT_NEAR}: the faces toward the sun of the blocks in "front" mode. Only the block itself is
 *     dropped there, so it casts no shadow and everything else still does.</li>
 *     <li>{@link #THROUGH_NEAR}: the faces toward the sun of the blocks in "through" mode. Everything nearer the sun
 *     than the block is dropped there: seen from behind the block, it is sky.</li>
 *     <li>{@link #FAR}: the faces away from the sun that border on air, so the back of the block is dropped too.</li>
 * </ul>
 * The maps are drawn through the pack's own shadow program, so they land where its vertex shader puts the terrain
 * (distortion included). A block is in "through" mode when the camera sees one of its faces away from the sun that
 * borders on air: only then can it see what the block lights.
 */
public final class ShadowHoles {
    static final int FRONT_NEAR = 0;
    static final int THROUGH_NEAR = 1;
    static final int FAR = 2;
    private static final String[] LABELS = {"sky shadow front near", "sky shadow through near", "sky shadow far"};

    public static final String[] SAMPLERS = {"blockofsky_frontNear", "blockofsky_throughNear", "blockofsky_far"};
    private static final GpuTexture[] maps = new GpuTexture[3];
    private static @Nullable GpuTexture cleared;
    private static boolean drawing;

    /** Set by the Iris mixins once they applied; the smoke check reads them. */
    public static boolean patchedShadowFragments;
    public static int patchedShaders;
    public static int framesPrepared;
    public static final int[] lastQuads = new int[3];
    /** Fragments of each set that passed the depth test in the last frame, and frames where a set drew nothing. */
    public static final int[] lastSamples = new int[3];
    public static final int[] emptyFrames = new int[3];
    private static int query;

    private ShadowHoles() {
    }

    public static boolean enabled() {
        return !"false".equals(System.getProperty("sky.shadowHoles"));
    }

    /** Whether a draw is one of ours: the color writes are off then, so only the depth is drawn. */
    public static boolean drawing() {
        return drawing;
    }

    /** The GL texture of a map for the pack's sampler, 0 before the first shadow pass. */
    public static IntSupplier glId(int map, java.util.function.ToIntFunction<GpuTexture> id) {
        // While the sky blocks themselves are drawn, the programs see only the far depth: the maps of the previous
        // frame would otherwise make them discard the very faces being drawn.
        return () -> {
            GpuTexture texture = drawing ? cleared : maps[map];
            return texture == null ? 0 : id.applyAsInt(texture);
        };
    }

    public static @Nullable GpuTexture map(int map) {
        return maps[map];
    }

    /**
     * Called at the start of the shadow pass, after Iris cleared the shadow map and set the shadow matrices, before
     * the terrain. Leaves the shadow map as it found it.
     */
    public static void prepare(GpuTexture shadowDepth, Matrix4f shadowModelView) {
        int size = shadowDepth.getWidth(0);
        ensure(size);
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        encoder.copyTextureToTexture(shadowDepth, cleared, 0, 0, 0, 0, 0, size, size);

        List<List<Face>> sets = enabled() ? faces(shadowModelView) : List.of(List.of(), List.of(), List.of());
        for (int i = 0; i < 3; i++) {
            List<Face> set = sets.get(i);
            lastQuads[i] = set.size();
            if (set.isEmpty()) {
                lastSamples[i] = 0;
                encoder.copyTextureToTexture(cleared, maps[i], 0, 0, 0, 0, 0, size, size);
                continue;
            }
            // Faces away from the sun are back faces seen from it: their corners go the other way round, or the
            // pipeline's culling drops them.
            IrisAccess.bindShadowFramebuffer();
            if (query == 0) {
                query = org.lwjgl.opengl.GL15.glGenQueries();
            }
            org.lwjgl.opengl.GL15.glBeginQuery(org.lwjgl.opengl.GL15.GL_SAMPLES_PASSED, query);
            draw(set, i == FAR);
            org.lwjgl.opengl.GL15.glEndQuery(org.lwjgl.opengl.GL15.GL_SAMPLES_PASSED);
            lastSamples[i] = org.lwjgl.opengl.GL15.glGetQueryObjecti(query, org.lwjgl.opengl.GL15.GL_QUERY_RESULT);
            if (lastSamples[i] == 0) {
                emptyFrames[i]++;
            }
            encoder.copyTextureToTexture(shadowDepth, maps[i], 0, 0, 0, 0, 0, size, size);
            encoder.copyTextureToTexture(cleared, shadowDepth, 0, 0, 0, 0, 0, size, size);
        }
        IrisAccess.bindShadowFramebuffer();
        framesPrepared++;
    }

    private record Face(BlockPos pos, Direction direction) {
    }

    private static List<List<Face>> faces(Matrix4f shadowModelView) {
        List<List<Face>> sets = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        Level level = Minecraft.getInstance().level;
        if (level == null) {
            return sets;
        }
        // The shadow camera looks down its -Z axis, so the sun is along +Z: the third row of the view rotation.
        double sunX = shadowModelView.m02();
        double sunY = shadowModelView.m12();
        double sunZ = shadowModelView.m22();
        Vec3 camera = Minecraft.getInstance().gameRenderer.mainCamera().position();
        for (SkyBlockEntity sky : SkyClient.loaded()) {
            if (!sky.showsSky()) {
                continue;
            }
            BlockPos pos = sky.getBlockPos();
            boolean through = false;
            List<Face> near = new ArrayList<>();
            for (Direction direction : Direction.values()) {
                if (!sky.faceShows(direction)) {
                    continue;
                }
                double toSun = direction.getStepX() * sunX + direction.getStepY() * sunY + direction.getStepZ() * sunZ;
                if (toSun > 1.0E-4) {
                    near.add(new Face(pos, direction));
                } else if (toSun < -1.0E-4 && !level.getBlockState(pos.relative(direction)).canOcclude()) {
                    sets.get(FAR).add(new Face(pos, direction));
                    // The camera is on the open side of this face: it can see what comes in through the block.
                    double planeX = pos.getX() + 0.5 + direction.getStepX() * 0.5;
                    double planeY = pos.getY() + 0.5 + direction.getStepY() * 0.5;
                    double planeZ = pos.getZ() + 0.5 + direction.getStepZ() * 0.5;
                    double side = (camera.x - planeX) * direction.getStepX() + (camera.y - planeY) * direction.getStepY()
                            + (camera.z - planeZ) * direction.getStepZ();
                    if (side > 0.0 && sees(level, camera, new Vec3(planeX, planeY, planeZ), direction)) {
                        through = true;
                    }
                }
            }
            // For comparison only: every block in "through" mode, whatever the camera sees.
            if (Boolean.getBoolean("sky.shadowHoles.forceThrough")) {
                through = true;
            }
            sets.get(through ? THROUGH_NEAR : FRONT_NEAR).addAll(near);
        }
        return sets;
    }

    /** Whether nothing stands between the camera and the middle of a face (a single ray: good enough here). */
    private static boolean sees(Level level, Vec3 camera, Vec3 faceCenter, Direction direction) {
        Vec3 target = faceCenter.add(direction.getStepX() * 0.01, direction.getStepY() * 0.01, direction.getStepZ() * 0.01);
        BlockHitResult hit = level.clip(new ClipContext(camera, target, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty()));
        return hit.getType() == HitResult.Type.MISS;
    }

    private static void draw(List<Face> faces, boolean reversed) {
        Vec3 camera = Minecraft.getInstance().gameRenderer.mainCamera().position();
        // The pack's shadow program for terrain, the one Sodium's chunks go through; an opaque sprite keeps any alpha
        // test of the pack from dropping fragments.
        RenderType type = RenderTypes.solidMovingBlock();
        TextureAtlasSprite stone = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS)
                .getSprite(Identifier.withDefaultNamespace("block/stone"));
        float u = (stone.getU0() + stone.getU1()) * 0.5F;
        float v = (stone.getV0() + stone.getV1()) * 0.5F;
        MeshData mesh = null;
        ByteBufferBuilder bytes = new ByteBufferBuilder(4096);
        try {
            BufferBuilder builder = new BufferBuilder(bytes, PrimitiveTopology.QUADS, type.format());
            for (Face face : faces) {
                Direction d = face.direction();
                float x = (float) (face.pos().getX() - camera.x);
                float y = (float) (face.pos().getY() - camera.y);
                float z = (float) (face.pos().getZ() - camera.z);
                float[][] corners = new float[4][];
                int[] n = {0};
                CubeFaces.corners(d, x, y, z, (vx, vy, vz, cu, cv) -> corners[n[0]++] = new float[]{vx, vy, vz});
                for (int k = 0; k < 4; k++) {
                    float[] c = corners[reversed ? 3 - k : k];
                    builder.addVertex(c[0], c[1], c[2]).setColor(-1).setUv(u, v).setLight(LightCoordsUtil.FULL_BRIGHT)
                            .setNormal(d.getStepX(), d.getStepY(), d.getStepZ());
                }
            }
            mesh = builder.build();
            if (mesh == null) {
                return;
            }
            int indexCount = faces.size() * 6;
            PreparedRenderType prepared = type.prepare();
            Matrix4f modelView = RenderSystem.getModelViewMatrixCopy();
            RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
            var main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
            try (GpuBuffer vertices = RenderSystem.getDevice().createBuffer(() -> "sky shadow faces", GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer())) {
                // Iris keeps the shadow framebuffer bound in the shadow pass; the attachments here are not used.
                RenderPassDescriptor descriptor = RenderPassDescriptor.builder(() -> "sky shadow faces")
                        .withColorAttachment(main.getColorTextureView())
                        .withDepthAttachment(main.getDepthTextureView(), OptionalDouble.empty())
                        .build();
                drawing = true;
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
                } finally {
                    drawing = false;
                }
            }
        } finally {
            if (mesh != null) {
                mesh.close();
            }
            bytes.close();
        }
    }

    private static void ensure(int size) {
        if (cleared != null && cleared.getWidth(0) == size) {
            return;
        }
        close();
        int usage = GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING;
        cleared = RenderSystem.getDevice().createTexture(() -> "sky shadow cleared", usage, GpuFormat.D32_FLOAT, size, size, 1, 1);
        for (int i = 0; i < 3; i++) {
            String label = LABELS[i];
            maps[i] = RenderSystem.getDevice().createTexture(() -> label, usage, GpuFormat.D32_FLOAT, size, size, 1, 1);
        }
    }

    static void close() {
        if (cleared != null) {
            cleared.close();
            cleared = null;
        }
        for (int i = 0; i < 3; i++) {
            if (maps[i] != null) {
                maps[i].close();
                maps[i] = null;
            }
        }
    }
}
