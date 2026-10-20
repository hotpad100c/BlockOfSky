package ml.mypals.sky.mixin.client.iris;

import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.query.Root;
import io.github.douira.glsl_transformer.ast.transform.ASTParser;
import ml.mypals.sky.client.ShadowHoles;
import ml.mypals.sky.client.ShadowShaderPatch;
import ml.mypals.sky.client.ThroughDepth;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.parameter.Parameters;
import net.irisshaders.iris.pipeline.transform.transformer.CommonTransformer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The pack's programs, patched for the sky blocks (see {@link ShadowShaderPatch}). Iris runs this for each stage of
 * every program it patches, after its own transformations, so the main renamed here is whatever main Iris left (its
 * own wrapper included).
 */
@Pseudo
@Mixin(value = CommonTransformer.class, remap = false)
public class CommonTransformerMixin {
    @Inject(method = "transformDepth", at = @At("TAIL"), require = 0)
    private static void sky$shadowHoles(ASTParser t, TranslationUnit tree, Root root, Parameters parameters, CallbackInfo ci) {
        if (CommonTransformer.isShadowPass(parameters, parameters.name)) {
            if (parameters.type == PatchShaderType.FRAGMENT) {
                ShadowShaderPatch.patchShadowFragment(t, tree, root, ThroughDepth.enabled());
                ShadowHoles.patchedShadowFragments = true;
                ShadowHoles.patchedShaders++;
                ThroughDepth.LOGGER.info("Shadow holes: patched shadow program {} ({} mode)", parameters.name,
                        ThroughDepth.enabled() ? "receiver" : "maps");
            }
        } else if (ThroughDepth.enabled()) {
            ShadowShaderPatch.ReceiverStats stats = ShadowShaderPatch.patchReceiver(t, tree, root);
            ShadowHoles.wrappedShadowReads += stats.comparisons() + stats.rawCovered();
            if (stats.any()) {
                ThroughDepth.LOGGER.info("Shadow holes: {} ({}): {} comparison read(s) wrapped, {} raw read(s) covered, {} raw read(s) left alone",
                        parameters.name, parameters.type, stats.comparisons(), stats.rawCovered(), stats.rawSkipped());
            }
        }
    }
}
