package ml.mypals.sky.client;

import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.declaration.DeclarationMember;
import io.github.douira.glsl_transformer.ast.node.declaration.FunctionParameter;
import io.github.douira.glsl_transformer.ast.node.declaration.TypeAndInitDeclaration;
import io.github.douira.glsl_transformer.ast.node.expression.Expression;
import io.github.douira.glsl_transformer.ast.node.expression.ReferenceExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.FunctionCallExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.GroupingExpression;
import io.github.douira.glsl_transformer.ast.node.external_declaration.FunctionDefinition;
import io.github.douira.glsl_transformer.ast.node.type.specifier.BuiltinFixedTypeSpecifier;
import io.github.douira.glsl_transformer.ast.node.type.specifier.BuiltinFixedTypeSpecifier.BuiltinType;
import io.github.douira.glsl_transformer.ast.query.Root;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.transform.ASTParser;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ShadowShaderPatch {
    private static final Set<String> SHADOW_MAPS = Set.of("shadowtex0", "shadowtex1", "shadow", "watershadow", "shadowtex0HW", "shadowtex1HW");
    private static final Map<String, Map<Integer, String>> WRAPPERS = Map.of(
            "texture", Map.of(2, "blockofsky_shadowTest"),
            "textureLod", Map.of(3, "blockofsky_shadowTestLod"),
            "textureOffset", Map.of(3, "blockofsky_pickOffset"),
            "textureGather", Map.of(3, "blockofsky_shadowGather"));
    public static java.util.function.Consumer<String> debugLog = line -> {
    };

    private ShadowShaderPatch() {
    }

    public static void patchShadowFragment(ASTParser t, TranslationUnit tree, Root root, boolean receiver) {
        root.rename("main", "blockofsky_main");
        tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
                "uniform sampler2D blockofsky_frontNear;",
                "uniform sampler2D blockofsky_throughNear;",
                "uniform sampler2D blockofsky_far;");
        String drop = """
                    if (blockofsky_near < 1.0) {
                        float blockofsky_start = blockofsky_through < 1.0 ? -1.0 : blockofsky_front - blockofsky_margin;
                        if (gl_FragCoord.z >= blockofsky_start && gl_FragCoord.z <= blockofsky_end + blockofsky_margin) {
                            discard;
                        }
                    }
                """;
        String body;
        if (receiver) {
            if (tree.getVersionStatement().version.number < 420) {
                tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_ALL, "#extension GL_ARB_shader_image_load_store : require\n");
            }
            tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_DECLARATIONS, "layout(r32ui) uniform uimage2D " + ThroughDepth.IMAGE + ";");
            body = drop + """
                        blockofsky_main();
                        if (blockofsky_near < 1.0 && gl_FragCoord.z > blockofsky_end + blockofsky_margin) {
                            imageAtomicMin(%s, blockofsky_texel, floatBitsToUint(gl_FragCoord.z));
                        }
                    """.formatted(ThroughDepth.IMAGE);
        } else {
            body = drop + """
                        blockofsky_main();
                    """;
        }
        tree.parseAndInjectNode(t, ASTInjectionPoint.END, """
                void main() {
                    ivec2 blockofsky_texel = ivec2(gl_FragCoord.xy);
                    float blockofsky_front = texelFetch(blockofsky_frontNear, blockofsky_texel, 0).r;
                    float blockofsky_through = texelFetch(blockofsky_throughNear, blockofsky_texel, 0).r;
                    float blockofsky_back = texelFetch(blockofsky_far, blockofsky_texel, 0).r;
                    float blockofsky_near = min(blockofsky_front, blockofsky_through);
                    float blockofsky_margin = 2.0e-5 + 2.0 * fwidth(gl_FragCoord.z);
                    float blockofsky_end = blockofsky_back < 1.0 ? max(blockofsky_near, blockofsky_back) : blockofsky_near;
                %s}
                """.formatted(body));
    }
    public record ReceiverStats(int comparisons, int rawCovered, int rawSkipped) {
        public boolean any() {
            return comparisons + rawCovered + rawSkipped > 0;
        }
    }
    public static ReceiverStats patchReceiver(ASTParser t, TranslationUnit tree, Root root) {
        RawShadowReads.Result raw = RawShadowReads.patch(t, tree, root);
        Set<String> shadowMaps = new HashSet<>();
        root.nodeIndex.getStream(TypeAndInitDeclaration.class)
                .filter(declaration -> isShadowSampler(declaration.getType().getTypeSpecifier()))
                .flatMap(declaration -> declaration.getMembers().stream())
                .map(DeclarationMember::getName)
                .filter(name -> name != null && SHADOW_MAPS.contains(name.getName()))
                .forEach(name -> shadowMaps.add(name.getName()));
        List<FunctionCallExpression> reads = root.nodeIndex.getStream(FunctionCallExpression.class)
                .filter(call -> wrapper(call) != null && readsShadowMap(call.getParameters().getFirst(), shadowMaps))
                .toList();
        for (FunctionCallExpression call : reads) {
            String name = call.getFunctionName().getName();
            if (!name.equals("textureOffset") && !name.equals("textureGather")) {
                call.getFunctionName().setName(wrapper(call));
                continue;
            }
            List<Expression> arguments = call.getParameters().stream()
                    .skip(name.equals("textureGather") ? 1 : 0)
                    .map(argument -> (Expression) argument.cloneInto(root))
                    .toList();
            FunctionCallExpression wrapped = (FunctionCallExpression) t.parseExpression(root, wrapper(call) + "()");
            call.replaceBy(wrapped);
            root.indexBuildSession(() -> {
                wrapped.getParameters().addAll(arguments);
                wrapped.getParameters().add(call);
            });
        }
        ReceiverStats stats = new ReceiverStats(reads.size(), raw.covered(), raw.skipped());
        if (reads.isEmpty() && raw.covered() == 0) {
            return stats;
        }
        tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_DECLARATIONS,
                "uniform sampler2D blockofsky_frontNear;",
                "uniform sampler2D blockofsky_far;",
                "uniform usampler2D " + ThroughDepth.SAMPLER + ";",
                "float " + RawShadowReads.Z_CHANNEL + " = 2.0;");
        tree.parseAndInjectNodes(t, ASTInjectionPoint.BEFORE_FUNCTIONS, """
                bool blockofsky_beyond(ivec2 blockofsky_texel, float blockofsky_depth, out float blockofsky_through) {
                    ivec2 blockofsky_size = textureSize(blockofsky_frontNear, 0);
                    if (blockofsky_depth > 1.5 || blockofsky_size.x <= 1 || any(lessThan(blockofsky_texel, ivec2(0)))
                            || any(greaterThanEqual(blockofsky_texel, blockofsky_size))) {
                        return false;
                    }
                    float blockofsky_front = texelFetch(blockofsky_frontNear, blockofsky_texel, 0).r;
                    if (blockofsky_front >= 1.0) {
                        return false;
                    }
                    float blockofsky_back = texelFetch(blockofsky_far, blockofsky_texel, 0).r;
                    float blockofsky_end = blockofsky_back < 1.0 ? max(blockofsky_front, blockofsky_back) : blockofsky_front;
                    if (blockofsky_depth <= blockofsky_end + 2.0e-4) {
                        return false;
                    }
                    blockofsky_through = uintBitsToFloat(texelFetch(%s, blockofsky_texel, 0).r);
                    return true;
                }
                """.formatted(ThroughDepth.SAMPLER), """
                ivec2 blockofsky_texelOf(vec2 blockofsky_uv) {
                    return ivec2(floor(blockofsky_uv * vec2(textureSize(blockofsky_frontNear, 0))));
                }
                """, """
                ivec2 blockofsky_gatherBase(vec2 blockofsky_uv) {
                    return ivec2(floor(blockofsky_uv * vec2(textureSize(blockofsky_frontNear, 0)) - 0.5));
                }
                """, """
                float blockofsky_lit(ivec2 blockofsky_texel, float blockofsky_depth, float blockofsky_shadowMap) {
                    float blockofsky_through;
                    if (blockofsky_beyond(blockofsky_texel, blockofsky_depth, blockofsky_through)) {
                        return blockofsky_depth <= blockofsky_through ? 1.0 : 0.0;
                    }
                    return blockofsky_shadowMap;
                }
                """, """
                float blockofsky_pick(vec3 blockofsky_coord, float blockofsky_shadowMap) {
                    return blockofsky_lit(blockofsky_texelOf(blockofsky_coord.xy), blockofsky_coord.z, blockofsky_shadowMap);
                }
                """, """
                float blockofsky_shadowTest(sampler2DShadow blockofsky_map, vec3 blockofsky_coord) {
                    return blockofsky_pick(blockofsky_coord, texture(blockofsky_map, blockofsky_coord));
                }
                """, """
                float blockofsky_shadowTestLod(sampler2DShadow blockofsky_map, vec3 blockofsky_coord, float blockofsky_lod) {
                    return blockofsky_pick(blockofsky_coord, textureLod(blockofsky_map, blockofsky_coord, blockofsky_lod));
                }
                """, """
                float blockofsky_pickOffset(sampler2DShadow blockofsky_map, vec3 blockofsky_coord, ivec2 blockofsky_offset, float blockofsky_shadowMap) {
                    vec2 blockofsky_shift = vec2(blockofsky_offset) / vec2(textureSize(blockofsky_map, 0));
                    return blockofsky_pick(vec3(blockofsky_coord.xy + blockofsky_shift, blockofsky_coord.z), blockofsky_shadowMap);
                }
                """, """
                vec4 blockofsky_shadowGather(vec2 blockofsky_uv, float blockofsky_depth, vec4 blockofsky_shadowMap) {
                    ivec2 blockofsky_base = blockofsky_gatherBase(blockofsky_uv);
                    return vec4(
                            blockofsky_lit(blockofsky_base + ivec2(0, 1), blockofsky_depth, blockofsky_shadowMap.x),
                            blockofsky_lit(blockofsky_base + ivec2(1, 1), blockofsky_depth, blockofsky_shadowMap.y),
                            blockofsky_lit(blockofsky_base + ivec2(1, 0), blockofsky_depth, blockofsky_shadowMap.z),
                            blockofsky_lit(blockofsky_base, blockofsky_depth, blockofsky_shadowMap.w));
                }
                """, """
                float blockofsky_effective(ivec2 blockofsky_texel, float blockofsky_depth, float blockofsky_raw) {
                    float blockofsky_through;
                    return blockofsky_beyond(blockofsky_texel, blockofsky_depth, blockofsky_through) ? blockofsky_through : blockofsky_raw;
                }
                """, """
                vec4 blockofsky_rawTexel(ivec2 blockofsky_texel, float blockofsky_depth, vec4 blockofsky_raw) {
                    blockofsky_raw.x = blockofsky_effective(blockofsky_texel, blockofsky_depth, blockofsky_raw.x);
                    return blockofsky_raw;
                }
                """, """
                vec4 blockofsky_rawUV(vec2 blockofsky_uv, float blockofsky_depth, vec4 blockofsky_raw) {
                    return blockofsky_rawTexel(blockofsky_texelOf(blockofsky_uv), blockofsky_depth, blockofsky_raw);
                }
                """, """
                vec4 blockofsky_rawGather(vec2 blockofsky_uv, float blockofsky_depth, vec4 blockofsky_raw) {
                    ivec2 blockofsky_base = blockofsky_gatherBase(blockofsky_uv);
                    return vec4(
                            blockofsky_effective(blockofsky_base + ivec2(0, 1), blockofsky_depth, blockofsky_raw.x),
                            blockofsky_effective(blockofsky_base + ivec2(1, 1), blockofsky_depth, blockofsky_raw.y),
                            blockofsky_effective(blockofsky_base + ivec2(1, 0), blockofsky_depth, blockofsky_raw.z),
                            blockofsky_effective(blockofsky_base, blockofsky_depth, blockofsky_raw.w));
                }
                """, """
                float blockofsky_withZ(float blockofsky_set, float blockofsky_value) {
                    return blockofsky_value;
                }
                """);
        return stats;
    }

    private static String wrapper(FunctionCallExpression call) {
        if (call.getFunctionName() == null) {
            return null;
        }
        Map<Integer, String> byCount = WRAPPERS.get(call.getFunctionName().getName());
        return byCount == null ? null : byCount.get(call.getParameters().size());
    }

    private static boolean readsShadowMap(Expression sampler, Set<String> shadowMaps) {
        while (sampler instanceof GroupingExpression grouping) {
            sampler = grouping.getOperand();
        }
        if (!(sampler instanceof ReferenceExpression reference)) {
            return false;
        }
        String name = reference.getIdentifier().getName();
        FunctionDefinition function = sampler.getAncestor(FunctionDefinition.class);
        if (function != null) {
            for (FunctionParameter parameter : function.getFunctionPrototype().getChildren()) {
                if (parameter.getName() != null && parameter.getName().getName().equals(name)) {
                    return isShadowSampler(parameter.getType().getTypeSpecifier());
                }
            }
        }
        return shadowMaps.contains(name);
    }

    private static boolean isShadowSampler(Object typeSpecifier) {
        return typeSpecifier instanceof BuiltinFixedTypeSpecifier fixed && fixed.type == BuiltinType.SAMPLER2DSHADOW;
    }
}
