package ml.mypals.sky.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.serialization.MapCodec;
import ml.mypals.sky.SkyMod;
import ml.mypals.sky.client.iris.IrisAccess;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.special.NoDataSpecialModelRenderer;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.util.LightCoordsUtil;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import java.util.function.Consumer;

/**
 * The sky block as an item shows the sky too: in the world (in a hand, on the ground, in a frame) the sky behind it,
 * in a GUI the sky on each side of the cube. With a shader pack, an item in the level is drawn like the blocks: as the
 * block's texture through the pack, then a hole into the pack's sky ({@link ShaderSkyHoles}); in the first-person hand
 * and in a GUI, outside the pack's level, it shows the sky cubes.
 */
public final class SkyItemRenderer implements NoDataSpecialModelRenderer {
    public static final MapCodec<Unbaked> MAP_CODEC = MapCodec.unit(new Unbaked());
    private static final RenderType SKY_ITEM = SkyBlockRenderer.createRenderType(SkyPipelines.SKY_ITEM, "sky_item");
    private static final RenderType TEXTURED = RenderTypes.entitySolid(SkyMod.id("textures/block/sky.png"));
    private static final long RECENT_NANOS = 1_000_000_000L;
    private static volatile long lastSubmit;
    private static volatile long lastCubesSubmit;

    /** Whether an item was drawn in the last second: the sky is kept up to date for it. */
    public static boolean drawnRecently() {
        return System.nanoTime() - lastSubmit < RECENT_NANOS;
    }

    /** Whether an item was drawn with the sky cubes in the last second (with a pack, in the hand or a GUI). */
    public static boolean cubesDrawnRecently() {
        return System.nanoTime() - lastCubesSubmit < RECENT_NANOS;
    }

    @Override
    public void submit(PoseStack poseStack, SubmitNodeCollector collector, int lightCoords, int overlayCoords, boolean hasFoil, int outlineColor) {
        lastSubmit = System.nanoTime();
        if (SkyClient.packInUse() && SkyClient.renderingLevel()) {
            // In the shadow pass the pose is the sun's view: the item only casts its shadow.
            if (!IrisAccess.renderingShadowPass()) {
                ShaderSkyHoles.addItem(poseStack.last().pose());
            }
            collector.submitCustomGeometry(poseStack, TEXTURED, (pose, buffer) -> {
                for (Direction face : Direction.values()) {
                    CubeFaces.corners(face, 0, 0, 0, (x, y, z, u, v) -> buffer.addVertex(pose, x, y, z).setColor(-1).setUv(u, v)
                            .setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightCoordsUtil.FULL_BRIGHT)
                            .setNormal(pose, face.getStepX(), face.getStepY(), face.getStepZ()));
                }
            });
            return;
        }
        lastCubesSubmit = lastSubmit;
        collector.submitCustomGeometry(poseStack, SKY_ITEM, (pose, buffer) -> {
            for (Direction face : Direction.values()) {
                // The corner of the cube goes in the color, for the GUI.
                CubeFaces.corners(face, 0, 0, 0, (x, y, z, u, v) -> buffer.addVertex(pose, x, y, z)
                        .setColor(Math.round(x * 255), Math.round(y * 255), Math.round(z * 255), 255));
            }
        });
    }

    @Override
    public void getExtents(Consumer<Vector3fc> output) {
        for (int i = 0; i < 8; i++) {
            output.accept(new Vector3f(i & 1, i >> 1 & 1, i >> 2 & 1));
        }
    }

    public record Unbaked() implements NoDataSpecialModelRenderer.Unbaked {
        @Override
        public SpecialModelRenderer<Void> bake(SpecialModelRenderer.BakingContext context) {
            return new SkyItemRenderer();
        }

        @Override
        public MapCodec<Unbaked> type() {
            return MAP_CODEC;
        }
    }
}
