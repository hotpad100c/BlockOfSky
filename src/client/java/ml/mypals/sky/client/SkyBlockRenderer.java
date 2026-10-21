package ml.mypals.sky.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import ml.mypals.sky.SkyBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.feature.ModelFeatureRenderer;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.EnumSet;
import java.util.Set;

public class SkyBlockRenderer implements BlockEntityRenderer<SkyBlockEntity, SkyBlockRenderer.State> {
    private static final RenderType SKY_FACES = createRenderType(SkyPipelines.SKY_FACES, "sky_faces");

    public static class State extends BlockEntityRenderState {
        final Set<Direction> faces = EnumSet.noneOf(Direction.class);
    }

    static RenderType createRenderType(RenderPipeline pipeline, String name) {
        RenderSetup.RenderSetupBuilder setup = RenderSetup.builder(pipeline);
        for (Direction face : Direction.values()) {
            setup.withTexture("Sampler" + face.ordinal(), SkyCubes.faceTexture(face));
        }
        return RenderType.create(name, setup.createRenderSetup());
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(SkyBlockEntity sky, State state, float partialTicks, Vec3 cameraPosition,
                                   ModelFeatureRenderer.@Nullable CrumblingOverlay breakProgress) {
        BlockEntityRenderer.super.extractRenderState(sky, state, partialTicks, cameraPosition, breakProgress);
        state.faces.clear();
        if (sky.showsSky() && !SkyClient.packInUse()) {
            for (Direction face : Direction.values()) {
                if (sky.faceShows(face)) {
                    state.faces.add(face);
                }
            }
        }
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector collector, CameraRenderState camera) {
        if (state.faces.isEmpty()) {
            return;
        }
        collector.submitCustomGeometry(poseStack, SKY_FACES, (pose, buffer) -> {
            for (Direction face : state.faces) {
                CubeFaces.corners(face, 0, 0, 0, (x, y, z, u, v) -> buffer.addVertex(pose.pose(), x, y, z));
            }
        });
    }

    @Override
    public int getViewDistance() {
        return Minecraft.getInstance().options.getEffectiveRenderDistance() * 16;
    }
}
