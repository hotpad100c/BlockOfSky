package ml.mypals.sky.client;

import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.abstract_node.ASTNode;
import io.github.douira.glsl_transformer.ast.node.abstract_node.InnerASTNode;
import io.github.douira.glsl_transformer.ast.node.declaration.DeclarationMember;
import io.github.douira.glsl_transformer.ast.node.declaration.FunctionParameter;
import io.github.douira.glsl_transformer.ast.node.declaration.TypeAndInitDeclaration;
import io.github.douira.glsl_transformer.ast.node.expression.Expression;
import io.github.douira.glsl_transformer.ast.node.expression.ReferenceExpression;
import io.github.douira.glsl_transformer.ast.node.expression.binary.AssignmentExpression;
import io.github.douira.glsl_transformer.ast.node.expression.binary.BinaryExpression;
import io.github.douira.glsl_transformer.ast.node.expression.binary.GreaterThanEqualExpression;
import io.github.douira.glsl_transformer.ast.node.expression.binary.GreaterThanExpression;
import io.github.douira.glsl_transformer.ast.node.expression.binary.LessThanEqualExpression;
import io.github.douira.glsl_transformer.ast.node.expression.binary.LessThanExpression;
import io.github.douira.glsl_transformer.ast.node.expression.binary.SubtractionAssignmentExpression;
import io.github.douira.glsl_transformer.ast.node.expression.binary.SubtractionExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.DecrementPostfixExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.DecrementPrefixExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.FunctionCallExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.GroupingExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.IncrementPostfixExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.IncrementPrefixExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.MemberAccessExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.UnaryExpression;
import io.github.douira.glsl_transformer.ast.node.external_declaration.FunctionDefinition;
import io.github.douira.glsl_transformer.ast.node.statement.CompoundStatement;
import io.github.douira.glsl_transformer.ast.node.statement.loop.ForLoopStatement;
import io.github.douira.glsl_transformer.ast.node.statement.terminal.ReturnStatement;
import io.github.douira.glsl_transformer.ast.node.type.initializer.ExpressionInitializer;
import io.github.douira.glsl_transformer.ast.node.type.specifier.BuiltinFixedTypeSpecifier;
import io.github.douira.glsl_transformer.ast.node.type.specifier.BuiltinFixedTypeSpecifier.BuiltinType;
import io.github.douira.glsl_transformer.ast.print.ASTPrinter;
import io.github.douira.glsl_transformer.ast.query.Root;
import io.github.douira.glsl_transformer.ast.transform.ASTParser;
import io.github.douira.glsl_transformer.ast.traversal.ASTListener;
import io.github.douira.glsl_transformer.ast.traversal.ASTWalker;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Raw reads of the shadow map (receiver mode): the pack reads the depth from a {@code sampler2D} and compares it to
 * the depth of the point it shades itself. A raw read gives no such depth, but the comparison that follows does: across
 * the packs looked at, the depth read is combined with the point's depth by {@code step}, a comparison or a subtraction,
 * right away or through a variable. That depth is found there and the read is replaced by an "effective" depth: where
 * the point is beyond a sky block, the depth of the first surface beyond the block ({@link ThroughDepth}), elsewhere
 * the depth read. The pack's own comparison then lets the light out of the block.
 * <p>
 * Also handled: a sampler passed to a helper function (only when every call passes a shadow map), and a helper that
 * returns the depth it reads (the point's depth is found at each call and handed over through a global). A read whose
 * point depth is not found, is a constant, or is another depth read, is left alone.
 */
final class RawShadowReads {
    static final String Z_CHANNEL = "blockofsky_z";
    /** No point depth known: the effective depth is the depth read. */
    private static final String NO_DEPTH = "2.0";
    private static final Set<String> MAPS = Set.of("shadowtex0", "shadowtex1", "shadow", "watershadow");
    private static final Set<String> READS = Set.of("texture", "texture2D", "textureLod", "texture2DLod", "texelFetch", "textureGather");
    private static final Set<String> SCALAR_MEMBERS = Set.of("x", "r");
    private static final Set<String> COMPONENTS = Set.of("x", "y", "z", "w", "r", "g", "b", "a");

    record Result(int covered, int skipped, boolean usesChannel) {
    }

    private final ASTParser t;
    private final TranslationUnit tree;
    private final Root root;
    private final Map<ASTNode, Integer> order = new IdentityHashMap<>();
    private final Set<String> rawUniforms = new HashSet<>();
    private final Set<FunctionParameter> rawParameters = new HashSet<>();
    private final List<Runnable> rewrites = new ArrayList<>();
    private int covered;
    private int skipped;
    private boolean usesChannel;

    private RawShadowReads(ASTParser t, TranslationUnit tree, Root root) {
        this.t = t;
        this.tree = tree;
        this.root = root;
    }

    static Result patch(ASTParser t, TranslationUnit tree, Root root) {
        RawShadowReads reads = new RawShadowReads(t, tree, root);
        reads.run();
        return new Result(reads.covered, reads.skipped, reads.usesChannel);
    }

    private void run() {
        ASTWalker.walk(new ASTListener() {
            @Override
            public void enterEveryNode(InnerASTNode node) {
                order.put(node, order.size());
            }
        }, tree);
        root.nodeIndex.getStream(TypeAndInitDeclaration.class)
                .filter(declaration -> declaration.getAncestor(FunctionDefinition.class) == null && isType(declaration.getType().getTypeSpecifier(), BuiltinType.SAMPLER2D))
                .flatMap(declaration -> declaration.getMembers().stream())
                .map(member -> member.getName().getName())
                .filter(MAPS::contains)
                .forEach(rawUniforms::add);
        if (rawUniforms.isEmpty()) {
            return;
        }
        findRawParameters();

        Set<FunctionDefinition> helpers = new LinkedHashSet<>();
        for (FunctionCallExpression read : root.nodeIndex.getStream(FunctionCallExpression.class).toList()) {
            if (!isRawRead(read)) {
                continue;
            }
            Expression value = read;
            if (unwrapParent(read) instanceof MemberAccessExpression access) {
                if (!SCALAR_MEMBERS.contains(access.getMember().getName())) {
                    skipped++;
                    log("left alone (swizzle ." + access.getMember().getName() + ")", read, null);
                    continue;
                }
                value = access;
            }
            // vec4(read0.x, read1.x, ...): the depths go on together (a manual bilinear filter, say).
            if (unwrapParent(value) instanceof FunctionCallExpression constructor && isVectorConstructor(constructor)) {
                value = constructor;
            }
            Expression depth = pointDepth(value, read);
            if (depth != null) {
                log("covered", read, depth);
                replaceRead(read, depth.cloneInto(root));
                covered++;
            } else if (value != read && unwrapParent(value) instanceof ReturnStatement && returnsFloat(read.getAncestor(FunctionDefinition.class))) {
                log("covered (helper " + read.getAncestor(FunctionDefinition.class).getFunctionPrototype().getName().getName() + ")", read, null);
                replaceRead(read, t.parseExpression(root, Z_CHANNEL));
                helpers.add(read.getAncestor(FunctionDefinition.class));
                covered++;
            } else {
                log("left alone", read, null);
                skipped++;
            }
        }
        for (FunctionDefinition helper : helpers) {
            String name = helper.getFunctionPrototype().getName().getName();
            int arity = helper.getFunctionPrototype().getParameters().size();
            for (FunctionCallExpression call : calls(name, arity)) {
                Expression depth = pointDepth(call, call);
                log("helper call", call, depth);
                Expression handed = depth != null ? depth.cloneInto(root) : t.parseExpression(root, NO_DEPTH);
                rewrites.add(() -> {
                    // Parsed whole, so that every node of it belongs to the tree; the depth goes into the float().
                    FunctionCallExpression wrapper = (FunctionCallExpression) t.parseExpression(root, "blockofsky_withZ(" + Z_CHANNEL + " = float())");
                    FunctionCallExpression cast = (FunctionCallExpression) ((AssignmentExpression) unwrap(wrapper.getParameters().getFirst())).getRight();
                    call.replaceBy(wrapper);
                    root.indexBuildSession(() -> {
                        cast.getParameters().add(handed);
                        wrapper.getParameters().add(call);
                    });
                });
                usesChannel = true;
            }
        }
        rewrites.forEach(Runnable::run);
    }
   private void findRawParameters() {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (FunctionDefinition function : root.nodeIndex.getStream(FunctionDefinition.class).toList()) {
                List<FunctionParameter> parameters = function.getFunctionPrototype().getParameters();
                for (int i = 0; i < parameters.size(); i++) {
                    FunctionParameter parameter = parameters.get(i);
                    if (rawParameters.contains(parameter) || !isType(parameter.getType().getTypeSpecifier(), BuiltinType.SAMPLER2D)) {
                        continue;
                    }
                    List<FunctionCallExpression> calls = calls(function.getFunctionPrototype().getName().getName(), parameters.size());
                    int index = i;
                    if (!calls.isEmpty() && calls.stream().allMatch(call -> isRawSampler(call.getParameters().get(index)))) {
                        rawParameters.add(parameter);
                        changed = true;
                    }
                }
            }
        }
    }

    private List<FunctionCallExpression> calls(String name, int arity) {
        return root.nodeIndex.getStream(FunctionCallExpression.class)
                .filter(call -> call.getFunctionName() != null && call.getFunctionName().getName().equals(name) && call.getParameters().size() == arity)
                .toList();
    }

    private boolean isRawRead(FunctionCallExpression call) {
        if (call.getFunctionName() == null || !READS.contains(call.getFunctionName().getName()) || call.getParameters().size() < 2) {
            return false;
        }
        if (call.getFunctionName().getName().equals("textureGather") && call.getParameters().size() != 2) {
            return false;
        }
        return isRawSampler(call.getParameters().getFirst());
    }

    private boolean isRawSampler(Expression sampler) {
        sampler = unwrap(sampler);
        if (!(sampler instanceof ReferenceExpression reference)) {
            return false;
        }
        String name = reference.getIdentifier().getName();
        FunctionParameter parameter = parameter(sampler.getAncestor(FunctionDefinition.class), name);
        return parameter != null ? rawParameters.contains(parameter) : rawUniforms.contains(name);
    }

    private Expression pointDepth(Expression value, Expression read) {
        ASTNode parent = unwrapParent(value);
        FunctionDefinition function = read.getAncestor(FunctionDefinition.class);
        List<Expression> uses = new ArrayList<>();
        if (parent instanceof ExpressionInitializer && parent.getParent() instanceof DeclarationMember member) {
            uses.addAll(variableUses(member.getName().getName(), function, member));
        } else if (parent instanceof AssignmentExpression assignment && unwrap(assignment.getRight()) == value
                && unwrap(assignment.getLeft()) instanceof ReferenceExpression target) {
            uses.addAll(variableUses(target.getIdentifier().getName(), function, assignment));
        } else if (parent instanceof SubtractionAssignmentExpression assignment && unwrap(assignment.getRight()) == value
                && unwrap(assignment.getLeft()) instanceof ReferenceExpression target) {
            Expression before = valueBefore(target.getIdentifier().getName(), function, assignment);
            return before != null && isPointDepth(before, read) ? before : null;
        } else {
            uses.add(value);
        }
        String found = null;
        Expression depth = null;
        for (Expression use : uses) {
            Expression other = otherOperand(use);
            if (other == null || !isPointDepth(other, read)) {
                continue;
            }
            String printed = ASTPrinter.printSimple(other).trim();
            if (found != null && !found.equals(printed)) {
                return null;
            }
            found = printed;
            depth = other;
        }
        return depth;
    }

    private static Expression otherOperand(Expression use) {
        Expression operand = use;
        ASTNode parent = unwrapParent(operand);
        if (parent instanceof MemberAccessExpression access && COMPONENTS.contains(access.getMember().getName())) {
            operand = access;
            parent = unwrapParent(operand);
        }
        if (parent instanceof FunctionCallExpression call && call.getFunctionName() != null && call.getFunctionName().getName().equals("step")
                && call.getParameters().size() == 2) {
            Expression first = call.getParameters().get(0);
            Expression second = call.getParameters().get(1);
            return contains(first, operand) ? second : contains(second, operand) ? first : null;
        }
        if (parent instanceof LessThanExpression || parent instanceof LessThanEqualExpression || parent instanceof GreaterThanExpression
                || parent instanceof GreaterThanEqualExpression || parent instanceof SubtractionExpression) {
            BinaryExpression binary = (BinaryExpression) parent;
            return contains(binary.getLeft(), operand) ? binary.getRight() : binary.getLeft();
        }
        return null;
    }

    private boolean isPointDepth(Expression depth, Expression read) {
        List<ReferenceExpression> references = new ArrayList<>();
        boolean[] readsMap = {false};
        ASTWalker.walk(new ASTListener() {
            @Override
            public void enterEveryNode(InnerASTNode node) {
                if (node instanceof ReferenceExpression reference) {
                    references.add(reference);
                } else if (node instanceof FunctionCallExpression call && isRawRead(call)) {
                    readsMap[0] = true;
                }
            }
        }, depth);
        if (references.isEmpty() || readsMap[0]) {
            return false;
        }
        FunctionDefinition function = read.getAncestor(FunctionDefinition.class);
        for (ReferenceExpression reference : references) {
            String name = reference.getIdentifier().getName();
            if (isRawSampler(reference) || storesRead(name, function)) {
                return false;
            }
            if (!knownAt(name, function, read)) {
                return false;
            }
        }
        return true;
    }

    private boolean storesRead(String name, FunctionDefinition function) {
        if (function == null) {
            return false;
        }
        boolean initialized = root.nodeIndex.getStream(DeclarationMember.class)
                .filter(member -> member.getAncestor(FunctionDefinition.class) == function && member.getName().getName().equals(name))
                .anyMatch(member -> member.getInitializer() instanceof ExpressionInitializer initializer && readsMap(initializer.getExpression()));
        return initialized || writes(name, function).stream()
                .anyMatch(write -> write instanceof BinaryExpression binary && readsMap(binary.getRight()));
    }

    private boolean readsMap(Expression expression) {
        boolean[] found = {false};
        ASTWalker.walk(new ASTListener() {
            @Override
            public void enterEveryNode(InnerASTNode node) {
                if (node instanceof FunctionCallExpression call && isRawRead(call)) {
                    found[0] = true;
                }
            }
        }, expression);
        return found[0];
    }

    private boolean knownAt(String name, FunctionDefinition function, Expression read) {
        if (function == null || parameter(function, name) != null) {
            return true;
        }
        List<DeclarationMember> locals = root.nodeIndex.getStream(DeclarationMember.class)
                .filter(member -> member.getAncestor(FunctionDefinition.class) == function && member.getName().getName().equals(name))
                .toList();
        if (locals.isEmpty()) {
            return true;
        }
        int at = order.getOrDefault(read, -1);
        for (DeclarationMember local : locals) {
            ASTNode scope = local.getAncestor(node -> node instanceof CompoundStatement || node instanceof ForLoopStatement);
            if (order.getOrDefault(local, Integer.MAX_VALUE) < at && scope != null && read.hasAncestor(scope)) {
                return true;
            }
        }
        return false;
    }

    private List<Expression> variableUses(String name, FunctionDefinition function, ASTNode set) {
        if (function == null) {
            return List.of();
        }
        int from = order.getOrDefault(set, -1);
        ASTNode nextWrite = null;
        for (ASTNode write : writes(name, function)) {
            int at = order.getOrDefault(write, -1);
            if (write != set && at > from && (nextWrite == null || at < order.get(nextWrite))) {
                nextWrite = write;
            }
        }
        ASTNode until = nextWrite;
        return root.nodeIndex.getStream(ReferenceExpression.class)
                .filter(reference -> reference.getIdentifier().getName().equals(name) && reference.getAncestor(FunctionDefinition.class) == function)
                .filter(reference -> order.getOrDefault(reference, -1) > from)
                .filter(reference -> until == null || order.getOrDefault(reference, -1) < order.get(until)
                        || reference.hasAncestor(until) && !isTarget(reference))
                .filter(reference -> !isTarget(reference))
                .map(reference -> (Expression) reference)
                .toList();
    }

    private Expression valueBefore(String name, FunctionDefinition function, ASTNode at) {
        if (function == null) {
            return null;
        }
        int position = order.getOrDefault(at, -1);
        for (ASTNode write : writes(name, function)) {
            int writeAt = order.getOrDefault(write, -1);
            boolean sibling = write instanceof SubtractionAssignmentExpression other && readsMap(other.getRight());
            if (writeAt < position && !sibling) {
                return null;
            }
        }
        return root.nodeIndex.getStream(DeclarationMember.class)
                .filter(member -> member.getAncestor(FunctionDefinition.class) == function && member.getName().getName().equals(name))
                .filter(member -> order.getOrDefault(member, Integer.MAX_VALUE) < position)
                .filter(member -> member.getInitializer() instanceof ExpressionInitializer)
                .map(member -> ((ExpressionInitializer) member.getInitializer()).getExpression())
                .reduce((first, second) -> second)
                .orElse(null);
    }

    private List<ASTNode> writes(String name, FunctionDefinition function) {
        List<ASTNode> writes = new ArrayList<>();
        ASTWalker.walk(new ASTListener() {
            @Override
            public void enterEveryNode(InnerASTNode node) {
                Expression target = null;
                if (node instanceof BinaryExpression binary && node.getClass().getSimpleName().endsWith("AssignmentExpression")) {
                    target = binary.getLeft();
                } else if (node instanceof IncrementPrefixExpression || node instanceof IncrementPostfixExpression
                        || node instanceof DecrementPrefixExpression || node instanceof DecrementPostfixExpression) {
                    target = ((UnaryExpression) node).getOperand();
                }
                while (target instanceof MemberAccessExpression access) {
                    target = access.getOperand();
                }
                if (unwrap(target) instanceof ReferenceExpression reference && reference.getIdentifier().getName().equals(name)) {
                    writes.add(node);
                }
            }
        }, function);
        return writes;
    }

    private static boolean isTarget(ReferenceExpression reference) {
        ASTNode node = reference;
        while (node.getParent() instanceof GroupingExpression || node.getParent() instanceof MemberAccessExpression) {
            node = node.getParent();
        }
        return node.getParent() instanceof BinaryExpression binary && binary.getClass().getSimpleName().endsWith("AssignmentExpression")
                && binary.getLeft() == node;
    }

    private static void log(String what, Expression read, Expression depth) {
        ShadowShaderPatch.debugLog.accept(what + ": " + ASTPrinter.printSimple(read).trim()
                + (depth == null ? "" : "  ->  depth " + ASTPrinter.printSimple(depth).trim()));
    }

    private void replaceRead(FunctionCallExpression read, Expression depth) {
        String name = read.getFunctionName().getName();
        String wrapper = name.equals("texelFetch") ? "blockofsky_rawTexel" : name.equals("textureGather") ? "blockofsky_rawGather" : "blockofsky_rawUV";
        Expression coordinate = read.getParameters().get(1).cloneInto(root);
        rewrites.add(() -> {
            FunctionCallExpression call = (FunctionCallExpression) t.parseExpression(root, wrapper + "()");
            Expression asFloat = asFloat(depth);
            read.replaceBy(call);
            root.indexBuildSession(() -> {
                call.getParameters().add(coordinate);
                call.getParameters().add(asFloat);
                call.getParameters().add(read);
            });
        });
    }

    private Expression asFloat(Expression depth) {
        FunctionCallExpression cast = (FunctionCallExpression) t.parseExpression(root, "float()");
        root.indexBuildSession(() -> cast.getParameters().add(depth));
        return cast;
    }

    private static boolean isVectorConstructor(FunctionCallExpression call) {
        return call.getFunctionName() == null && call.getFunctionSpecifier() != null
                && ASTPrinter.printSimple(call.getFunctionSpecifier()).trim().matches("vec[234]");
    }

    private static boolean returnsFloat(FunctionDefinition function) {
        return function != null && ASTPrinter.printSimple(function.getFunctionPrototype().getReturnType()).trim().equals("float");
    }

    private static FunctionParameter parameter(FunctionDefinition function, String name) {
        if (function == null) {
            return null;
        }
        for (FunctionParameter parameter : function.getFunctionPrototype().getParameters()) {
            if (parameter.getName() != null && parameter.getName().getName().equals(name)) {
                return parameter;
            }
        }
        return null;
    }

    private static boolean isType(Object specifier, BuiltinType type) {
        return specifier instanceof BuiltinFixedTypeSpecifier fixed && fixed.type == type;
    }

    private static boolean contains(Expression outer, Expression inner) {
        return outer == inner || inner.hasAncestor(outer);
    }

    private static Expression unwrap(Expression expression) {
        while (expression instanceof GroupingExpression grouping) {
            expression = grouping.getOperand();
        }
        return expression;
    }

    private static ASTNode unwrapParent(ASTNode node) {
        ASTNode parent = node.getParent();
        while (parent instanceof GroupingExpression) {
            parent = parent.getParent();
        }
        return parent;
    }
}
