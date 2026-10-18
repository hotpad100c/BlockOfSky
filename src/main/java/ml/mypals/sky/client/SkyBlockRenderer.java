package ml.mypals.sky.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import ml.mypals.sky.SkyBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.core.Direction;
import org.joml.Matrix4f;

public class SkyBlockRenderer implements BlockEntityRenderer<SkyBlockEntity> {
    private static final RenderType SKY_FACES = createRenderType();

    private static RenderType createRenderType() {
        RenderStateShard.MultiTextureStateShard.Builder textures = RenderStateShard.MultiTextureStateShard.builder();
        for (Direction face : Direction.values()) {
            textures.add(SkyCubes.faceTexture(face), false, false);
        }
        return RenderType.create("sky_faces", DefaultVertexFormat.POSITION, VertexFormat.Mode.QUADS, 256,
                RenderType.CompositeState.builder()
                        .setShaderState(new RenderStateShard.ShaderStateShard(Shaders::skyFaces))
                        .setTextureState(textures.build())
                        .createCompositeState(false));
    }

    @Override
    public void render(SkyBlockEntity sky, float partialTick, PoseStack poseStack, MultiBufferSource buffers, int packedLight, int packedOverlay) {
        if (!sky.showsSky() || SkyClient.packInUse()) {
            return;
        }
        VertexConsumer consumer = buffers.getBuffer(SKY_FACES);
        Matrix4f pose = poseStack.last().pose();
        for (Direction face : Direction.values()) {
            if (sky.faceShows(face)) {
                CubeFaces.corners(face, 0, 0, 0, (x, y, z, u, v) -> consumer.addVertex(pose, x, y, z));
            }
        }
    }

    @Override
    public int getViewDistance() {
        return Minecraft.getInstance().options.getEffectiveRenderDistance() * 16;
    }
}
