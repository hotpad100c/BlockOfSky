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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.function.IntSupplier;


public final class ShadowHoles {
    public static final int FRONT_NEAR = 0;
    static final int THROUGH_NEAR = 1;
    public static final int FAR = 2;
    private static final String[] LABELS = {"sky shadow front near", "sky shadow through near", "sky shadow far"};

    public static final String[] SAMPLERS = {"blockofsky_frontNear", "blockofsky_throughNear", "blockofsky_far"};
    private static final GpuTexture[] maps = new GpuTexture[3];
    private static @Nullable GpuTexture cleared;
    private static boolean drawing;

    public static boolean patchedShadowFragments;
    public static int patchedShaders;
    public static int wrappedShadowReads;
    public static int framesPrepared;
    public static final int[] lastQuads = new int[3];
    public static final int[] lastSamples = new int[3];
    public static final int[] emptyFrames = new int[3];
    private static int query;
    private static final double SHAFT_LENGTH = 48.0;
    private static final int CHECK_EVERY = 4;
    private static final int HOLD_FRAMES = 12;
    private static final double[][] FACE_SAMPLES = {{0, 0}, {-0.35, -0.35}, {0.35, -0.35}, {0.35, 0.35}, {-0.35, 0.35}};
    private static Map<BlockPos, Integer> lastSeen = new HashMap<>();

    private ShadowHoles() {
    }

    public static boolean enabled() {
        return !"false".equals(System.getProperty("sky.shadowHoles"));
    }
    public static boolean drawing() {
        return drawing;
    }
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
    public static void prepare(GpuTexture shadowDepth, Matrix4f shadowModelView) {
        int size = shadowDepth.getWidth(0);
        ensure(size);
        if (ThroughDepth.enabled()) {
            ThroughDepth.clear(size);
        }
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
            // Faces away from the sun are back faces seen from it, their corners go the other way round, or the
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
        Vec3 sun = new Vec3(sunX, sunY, sunZ);
        Map<BlockPos, Integer> nextSeen = new HashMap<>();
        for (SkyBlockEntity sky : SkyClient.loaded()) {
            if (!sky.showsSky()) {
                continue;
            }
            BlockPos pos = sky.getBlockPos();
            List<Face> near = new ArrayList<>();
            List<Direction> back = new ArrayList<>();
            for (Direction direction : Direction.values()) {
                if (!sky.faceShows(direction)) {
                    continue;
                }
                double toSun = direction.getStepX() * sunX + direction.getStepY() * sunY + direction.getStepZ() * sunZ;
                if (toSun > 1.0E-4) {
                    near.add(new Face(pos, direction));
                } else if (toSun < -1.0E-4 && !level.getBlockState(pos.relative(direction)).canOcclude()) {
                    sets.get(FAR).add(new Face(pos, direction));
                    back.add(direction);
                }
            }
            // In the receiver mode every block is in "front" mode: the shadow map drops only the block, and the wrapped
            // lookups find the light beyond it, wherever the camera is.
            if (ThroughDepth.enabled()) {
                sets.get(FRONT_NEAR).addAll(near);
                continue;
            }
            Integer previous = lastSeen.get(pos);
            int seen = previous == null ? Integer.MIN_VALUE / 2 : previous;
            if ((previous == null || Math.floorMod(framesPrepared + pos.hashCode(), CHECK_EVERY) == 0)
                    && seesLight(level, camera, sun, pos, back)) {
                seen = framesPrepared;
            }
            nextSeen.put(pos, seen);
            boolean through = framesPrepared - seen <= HOLD_FRAMES;
            // For comparison only: every block in "through" mode, whatever the camera sees.
            if (Boolean.getBoolean("sky.shadowHoles.forceThrough")) {
                through = true;
            }
            sets.get(through ? THROUGH_NEAR : FRONT_NEAR).addAll(near);
        }
        lastSeen = nextSeen;
        return sets;
    }

    private static boolean seesLight(Level level, Vec3 camera, Vec3 sun, BlockPos pos, List<Direction> back) {
        // The faces themselves first: they are the cheapest to check.
        for (Direction direction : back) {
            Vec3 center = faceCenter(pos, direction);
            Vec3 normal = normal(direction);
            if (camera.subtract(center).dot(normal) > 0.0 && sees(level, camera, center.add(normal.scale(0.01)))) {
                return true;
            }
        }
        for (Direction direction : back) {
            Vec3 center = faceCenter(pos, direction);
            Vec3 normal = normal(direction);
            Vec3 across = Math.abs(normal.y) > 0.5 ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
            Vec3 along = normal.cross(across);
            for (double[] sample : FACE_SAMPLES) {
                Vec3 start = center.add(across.scale(sample[0])).add(along.scale(sample[1])).add(normal.scale(0.01));
                Vec3 end = start.subtract(sun.scale(SHAFT_LENGTH));
                BlockHitResult hit = level.clip(new ClipContext(start, end, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, CollisionContext.empty()));
                Vec3 land = hit.getType() == HitResult.Type.MISS ? end : hit.getLocation();
                // Where the light lands, nudged toward the camera so the surface it lands on does not hide it, and
                // the middle of the shaft, which packs with volumetric light show too.
                Vec3 toCamera = camera.subtract(land);
                Vec3 landing = toCamera.lengthSqr() > 1.0E-4 ? land.add(toCamera.normalize().scale(0.05)) : land;
                if (sees(level, camera, landing) || sees(level, camera, start.add(land).scale(0.5))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Vec3 normal(Direction direction) {
        return new Vec3(direction.getStepX(), direction.getStepY(), direction.getStepZ());
    }

    private static Vec3 faceCenter(BlockPos pos, Direction direction) {
        return new Vec3(pos.getX() + 0.5 + direction.getStepX() * 0.5, pos.getY() + 0.5 + direction.getStepY() * 0.5,
                pos.getZ() + 0.5 + direction.getStepZ() * 0.5);
    }

    private static boolean sees(Level level, Vec3 camera, Vec3 target) {
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
        ThroughDepth.close();
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
