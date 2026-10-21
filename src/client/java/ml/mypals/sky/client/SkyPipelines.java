package ml.mypals.sky.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import ml.mypals.sky.SkyMod;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import org.jspecify.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

public final class SkyPipelines {
    public static final RenderPipeline SKY_FACES = skyFaces("sky_faces", false);
    public static final RenderPipeline SKY_ITEM = skyFaces("sky_item", true);

    public static final RenderPipeline HOLE = hole("hole", null, null, "Before", "After");
    private static final Map<GpuFormat, RenderPipeline> HOLE_WITH_SKY = new EnumMap<>(GpuFormat.class);
    public static final RenderPipeline HOLE_RESTORE = hole("hole_restore", "RESTORE_DEPTH", null, "Before", "After");
    public static final RenderPipeline HOLE_FAR_AGAIN = hole("hole_far_again", "FAR_AGAIN", null, "Before", "After", "CurrentDepth");

    static {
        for (GpuFormat format : new GpuFormat[]{GpuFormat.RGBA8_UNORM, GpuFormat.RGBA16_UNORM, GpuFormat.RGBA16_FLOAT,
                GpuFormat.RGBA32_FLOAT, GpuFormat.RG11B10_FLOAT, GpuFormat.RGB10A2_UNORM}) {
            HOLE_WITH_SKY.put(format, hole("hole_" + format.name().toLowerCase(), "SKY_COLOR",
                    new ColorTargetState(Optional.empty(), format, ColorTargetState.WRITE_ALL), "Before", "After", "SkySnapshot"));
        }
    }

    private SkyPipelines() {
    }

    public static void init() {
    }

    public static @Nullable RenderPipeline holeWithSky(GpuFormat format) {
        return HOLE_WITH_SKY.get(format);
    }

    private static RenderPipeline skyFaces(String name, boolean item) {
        RenderPipeline.Builder builder = RenderPipeline.builder(RenderPipelines.GLOBALS_SNIPPET)
                .withLocation(SkyMod.id("pipeline/" + name))
                .withVertexShader(SkyMod.id("core/sky_faces"))
                .withFragmentShader(SkyMod.id("core/sky_faces"))
                .withBindGroupLayout(BindGroupLayouts.PROJECTION)
                .withBindGroupLayout(BindGroupLayouts.DYNAMIC_TRANSFORMS)
                .withBindGroupLayout(samplers("Sampler0", "Sampler1", "Sampler2", "Sampler3", "Sampler4", "Sampler5"))
                .withVertexBinding(0, item ? DefaultVertexFormat.POSITION_COLOR : DefaultVertexFormat.POSITION)
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
                .withColorTargetState(ColorTargetState.DEFAULT)
                .withDepthStencilState(new DepthStencilState(CompareOp.GREATER_THAN_OR_EQUAL, true));
        if (item) {
            builder.withShaderDefine("ITEM");
        }
        return RenderPipelines.register(builder.build());
    }

    private static BindGroupLayout samplers(String... names) {
        BindGroupLayout.Builder layout = BindGroupLayout.builder();
        for (String name : names) {
            layout.withUniform(name, UniformType.COMBINED_IMAGE_SAMPLER);
        }
        return layout.build();
    }

    private static RenderPipeline hole(String name, @Nullable String define, @Nullable ColorTargetState color, String... samplers) {
        RenderPipeline.Builder builder = RenderPipeline.builder()
                .withLocation(SkyMod.id("pipeline/" + name))
                .withVertexShader(SkyMod.id("core/screen"))
                .withFragmentShader(SkyMod.id("core/hole"))
                .withBindGroupLayout(BindGroupLayouts.PROJECTION)
                .withBindGroupLayout(samplers(samplers))
                .withVertexBinding(0, DefaultVertexFormat.POSITION)
                .withPrimitiveTopology(PrimitiveTopology.QUADS)
                .withCull(false)
                .withDepthStencilState(new DepthStencilState(CompareOp.ALWAYS_PASS, true));
        if (define != null) {
            builder.withShaderDefine(define);
        }
        if (color != null) {
            builder.withColorTargetState(color);
        }
        return RenderPipelines.register(builder.build());
    }
}
