package ml.mypals.sky.mixin.client.iris;

import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.query.Root;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.transform.ASTParser;
import ml.mypals.sky.client.ShadowHoles;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.parameter.Parameters;
import net.irisshaders.iris.pipeline.transform.transformer.CommonTransformer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every fragment program of the shadow pass starts by dropping the fragments in the sky blocks' depth range of their
 * shadow texel; see {@link ShadowHoles}. Iris runs this for each stage of every program it patches, after its own
 * transformations, so the main renamed here is whatever main Iris left (its own wrapper included).
 */
@Pseudo
@Mixin(value = CommonTransformer.class, remap = false)
public class CommonTransformerMixin {
    @Inject(method = "transformDepth", at = @At("TAIL"), require = 0)
    private static void sky$shadowHoles(ASTParser t, TranslationUnit tree, Root root, Parameters parameters, CallbackInfo ci) {
        if (parameters.type != PatchShaderType.FRAGMENT || !CommonTransformer.isShadowPass(parameters, parameters.name)) {
            return;
        }
        root.rename("main", "blockofsky_main");
        tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
                "uniform sampler2D blockofsky_frontNear;",
                "uniform sampler2D blockofsky_throughNear;",
                "uniform sampler2D blockofsky_far;");
        // The maps hold the far depth (1) where no sky block was drawn. The margin covers the gap between the faces
        // drawn here and the same faces in Sodium's compact chunk format.
        tree.parseAndInjectNode(t, ASTInjectionPoint.END, """
                void main() {
                    ivec2 blockofsky_texel = ivec2(gl_FragCoord.xy);
                    float blockofsky_front = texelFetch(blockofsky_frontNear, blockofsky_texel, 0).r;
                    float blockofsky_through = texelFetch(blockofsky_throughNear, blockofsky_texel, 0).r;
                    float blockofsky_back = texelFetch(blockofsky_far, blockofsky_texel, 0).r;
                    float blockofsky_near = min(blockofsky_front, blockofsky_through);
                    float blockofsky_margin = 2.0e-5 + 2.0 * fwidth(gl_FragCoord.z);
                    if (blockofsky_near < 1.0) {
                        float blockofsky_end = blockofsky_back < 1.0 ? max(blockofsky_near, blockofsky_back) : blockofsky_near;
                        float blockofsky_start = blockofsky_through < 1.0 ? -1.0 : blockofsky_front - blockofsky_margin;
                        if (gl_FragCoord.z >= blockofsky_start && gl_FragCoord.z <= blockofsky_end + blockofsky_margin) {
                            discard;
                        }
                    }
                    blockofsky_main();
                }
                """);
        ShadowHoles.patchedShadowFragments = true;
        ShadowHoles.patchedShaders++;
    }
}
