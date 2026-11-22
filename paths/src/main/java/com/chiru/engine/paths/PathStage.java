package com.chiru.engine.paths;

import com.chiru.engine.core.AnalysisStage;
import com.chiru.engine.core.StageContext;
import com.chiru.engine.model.*;
import com.chiru.engine.paths.PathTree.Decision;
import com.chiru.engine.paths.PathTree.Item;
import com.chiru.engine.spoon.SpoonIds;
import com.chiru.engine.spoon.SpoonModelLoader;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;
import spoon.reflect.CtModel;
import spoon.reflect.code.CtBlock;
import spoon.reflect.code.CtLambda;
import spoon.reflect.declaration.*;
import spoon.reflect.visitor.filter.TypeFilter;

/**
 * Phase 10b, steps 2 and 3: the path model. For every unit of code it builds the tree of effects, exits and decisions from the
 * Spoon AST and flattens it into paths: the conditions that select a path, what it does in order, and how it ends.
 *
 * <p>A unit is a method or constructor body, a lambda, a field initialiser, or a static or instance initialiser block. Lambdas
 * and initialisers belong to their enclosing method, or to their class when there is none.
 *
 * <p>Writes one PATH_DECISION per decision point (so the model can be compared with the syntax census) and, for units that have
 * a splitting decision, one PATH per path up to the cap in {@link PathOptions}. A unit without a decision has exactly one path
 * and gets no PATH entities. Run after {@code source-spoon}.
 */
public final class PathStage implements AnalysisStage {

    public static final String NAME = "paths";

    private final PathOptions options;

    public PathStage() {
        this(PathOptions.defaults());
    }

    public PathStage(PathOptions options) {
        this.options = options;
    }

    @Override
    public String name() {
        return NAME;
    }

    /** One body of code to model: who owns it, how its entities are keyed, and how to build its tree. */
    private record Unit(EntityId owner, String key, String label, SourceLocation where, boolean method, Supplier<List<Item>> build,
                        PathBuilder builder) {}

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void run(StageContext ctx) {
        AnalysisModel model = ctx.model();
        Optional<SpoonModelLoader.Loaded> loaded = SpoonModelLoader.loadShared(ctx, NAME, false);
        if (loaded.isEmpty()) {
            return;
        }
        CtModel ctModel = loaded.get().model();
        Map<Path, String> displayPaths = loaded.get().displayPaths();

        List<Unit> units = new ArrayList<>();
        for (CtTypeMember member : (List<CtTypeMember>) (List) ctModel.getElements(new TypeFilter(CtMethod.class))) {
            addMethod(units, model, displayPaths, member);
        }
        for (CtTypeMember member : (List<CtTypeMember>) (List) ctModel.getElements(new TypeFilter(CtConstructor.class))) {
            addMethod(units, model, displayPaths, member);
        }
        for (CtLambda<?> lambda : (List<CtLambda<?>>) (List) ctModel.getElements(new TypeFilter(CtLambda.class))) {
            addLambda(units, model, displayPaths, lambda);
        }
        for (CtField<?> field : (List<CtField<?>>) (List) ctModel.getElements(new TypeFilter(CtField.class))) {
            addFieldInit(units, model, displayPaths, field);
        }
        for (CtAnonymousExecutable block : (List<CtAnonymousExecutable>) (List) ctModel.getElements(new TypeFilter(CtAnonymousExecutable.class))) {
            addInitBlock(units, model, displayPaths, block);
        }

        int methods = 0;
        int others = 0;
        int withPaths = 0;
        int paths = 0;
        int decisions = 0;
        int outcomes = 0;
        int orderWritten = 0;
        List<String> capped = new ArrayList<>();
        Map<String, Integer> opaque = new TreeMap<>();
        for (Unit unit : units) {
            List<Item> tree = unit.build().get();
            unit.builder().opaque.forEach((k, v) -> opaque.merge(k, v, Integer::sum));
            Map<String, Decision> all = PathTree.decisions(tree);
            PathFlattener flattener = new PathFlattener(options.maxPathsPerMethod());
            List<PathFlattener.Row> rows = flattener.run(tree);
            boolean splitting = all.values().stream().anyMatch(d -> !d.exprLevel());
            if (unit.owner().type() == EntityType.METHOD) {
                orderWritten += writeOrder(model, unit, PathOrder.compute(tree));
            }

            if (unit.method()) {
                methods++;
            } else {
                others++;
            }
            decisions += all.size();
            outcomes += all.values().stream().mapToInt(d -> d.arms().size()).sum();
            for (Decision d : all.values()) {
                writeDecision(model, unit, d, flattener.reached);
            }
            if (splitting) {
                withPaths++;
                paths += rows.size();
                writePaths(model, unit, rows);
            }
            if (unit.method()) {
                Map<String, String> attrs = new LinkedHashMap<>();
                attrs.put("decisions", String.valueOf(all.size()));
                attrs.put("paths", String.valueOf(splitting ? rows.size() : 1));
                if (!unit.builder().opaque.isEmpty()) {
                    attrs.put("opaqueStatements", String.valueOf(unit.builder().opaque.values().stream().mapToInt(Integer::intValue).sum()));
                }
                if (flattener.capped) {
                    attrs.put("pathsCapped", "true");
                }
                model.putEntity(new Entity(unit.owner(), model.entity(unit.owner()).orElseThrow().name(), attrs, List.of()));
            }
            if (flattener.capped) {
                capped.add(unit.method() ? ModelIndex.shortName(unit.owner()) : ModelIndex.shortName(unit.owner()) + " (" + unit.label() + ")");
            }
        }

        model.addWarning(new AnalysisWarning(NAME, AnalysisWarning.Severity.INFO, "path model: " + methods + " method(s) and "
                + others + " lambda(s), initialiser(s) and initialiser block(s), " + decisions + " decision point(s) with " + outcomes
                + " outcome(s), " + paths + " path(s) in " + withPaths + " unit(s) that branch. Loops are abstracted to zero or "
                + "at-least-one pass; a switch expression used as a call argument or operand is not split into paths. "
                + "Order of " + orderWritten + " expression(s) recorded for lineage", null));
        if (!capped.isEmpty()) {
            model.addWarning(new AnalysisWarning(NAME, AnalysisWarning.Severity.WARNING, capped.size() + " unit(s) have more than "
                    + options.maxPathsPerMethod() + " paths, so their list of paths is incomplete (their decisions are all "
                    + "present): " + String.join(", ", capped.subList(0, Math.min(5, capped.size()))), null));
        }
        if (!opaque.isEmpty()) {
            model.addWarning(new AnalysisWarning(NAME, AnalysisWarning.Severity.INFO,
                    "statements the path model does not model yet, kept as opaque effects: " + opaque, null));
        }
    }

    // ---------------------------------------------------------------- collecting units

    private static void addMethod(List<Unit> units, AnalysisModel model, Map<Path, String> displayPaths, CtTypeMember member) {
        EntityId methodId = SpoonIds.methodId(member);
        CtBlock<?> body = member instanceof CtMethod<?> m ? m.getBody() : ((CtConstructor<?>) member).getBody();
        SourceLocation where = SpoonIds.locationOf(member, displayPaths);
        if (body == null || member.isImplicit() || where == null || model.entity(methodId).isEmpty()) {
            return;
        }
        PathBuilder builder = new PathBuilder();
        units.add(new Unit(methodId, ModelIndex.key(methodId), "", where, true, () -> builder.body(body), builder));
    }

    /** A lambda belongs to the nearest enclosing method or constructor, or to its class (a field initialiser, an init block). */
    private static void addLambda(List<Unit> units, AnalysisModel model, Map<Path, String> displayPaths, CtLambda<?> lambda) {
        SourceLocation where = SpoonIds.locationOf(lambda, displayPaths);
        EntityId owner = ownerOf(lambda);
        if (where == null || owner == null || model.entity(owner).isEmpty() || lambda.isImplicit()) {
            return;
        }
        PathBuilder builder = new PathBuilder();
        String key = ModelIndex.key(owner) + "~lambda@" + lambda.getPosition().getSourceStart();
        units.add(new Unit(owner, key, "lambda at line " + where.startLine(), where, false, () -> builder.lambdaBody(lambda), builder));
    }

    private static void addFieldInit(List<Unit> units, AnalysisModel model, Map<Path, String> displayPaths, CtField<?> field) {
        SourceLocation where = SpoonIds.locationOf(field, displayPaths);
        if (field.isImplicit() || field.getDefaultExpression() == null || field.getDeclaringType() == null || where == null) {
            return;
        }
        EntityId owner = EntityId.of(EntityType.CLASS, field.getDeclaringType().getQualifiedName());
        if (model.entity(owner).isEmpty()) {
            return;
        }
        PathBuilder builder = new PathBuilder();
        units.add(new Unit(owner, ModelIndex.key(owner) + "#" + field.getSimpleName() + "~init", field.getSimpleName() + " initialiser",
                where, false, () -> builder.fieldInit(field), builder));
    }

    private static void addInitBlock(List<Unit> units, AnalysisModel model, Map<Path, String> displayPaths, CtAnonymousExecutable block) {
        SourceLocation where = SpoonIds.locationOf(block, displayPaths);
        if (block.isImplicit() || block.getDeclaringType() == null || where == null) {
            return;
        }
        EntityId owner = EntityId.of(EntityType.CLASS, block.getDeclaringType().getQualifiedName());
        if (model.entity(owner).isEmpty()) {
            return;
        }
        String name = block.isStatic() ? "<static-init>" : "<init-block>";
        PathBuilder builder = new PathBuilder();
        units.add(new Unit(owner, ModelIndex.key(owner) + "#" + name + "@" + where.startLine(), (block.isStatic() ? "static initialiser block" : "instance initialiser block")
                + " at line " + where.startLine(), where, false, () -> builder.initBlock(block), builder));
    }

    private static EntityId ownerOf(CtElement element) {
        for (CtElement p = element.isParentInitialized() ? element.getParent() : null; p != null;
             p = p.isParentInitialized() ? p.getParent() : null) {
            if (p instanceof CtMethod<?> || p instanceof CtConstructor<?>) {
                return SpoonIds.methodId((CtTypeMember) p);
            }
            if (p instanceof CtType<?> type && !(p instanceof CtTypeParameter)) {
                return EntityId.of(EntityType.CLASS, type.getQualifiedName());
            }
        }
        return null;
    }

    /** Largest number of data-flow expressions in a unit for which the order is recorded (it is quadratic in that number). */
    private static final int ORDER_LIMIT = 150;

    /**
     * Records on each EXPRESSION of the unit the unit it belongs to and the expressions that may run before it, for the lineage
     * tracer. Skipped for a unit with more than the limit (the tracer then keeps every definition, as before).
     */
    private static int writeOrder(AnalysisModel model, Unit unit, Map<String, Set<String>> order) {
        if (order.size() > ORDER_LIMIT) {
            return 0;
        }
        int written = 0;
        String methodKey = ModelIndex.key(unit.owner());
        for (Map.Entry<String, Set<String>> e : order.entrySet()) {
            EntityId id = EntityId.of(EntityType.EXPRESSION, methodKey + "@" + e.getKey());
            Optional<Entity> expression = model.entity(id);
            if (expression.isPresent()) {
                Map<String, String> a = new LinkedHashMap<>();
                a.put("unit", unit.key());
                a.put("precededBy", String.join(" ", e.getValue()));
                model.putEntity(new Entity(id, expression.get().name(), a, List.of()));
                written++;
            }
        }
        return written;
    }

    // ---------------------------------------------------------------- writing

    private static void writeDecision(AnalysisModel model, Unit unit, Decision d, Set<String> reached) {
        EntityId id = EntityId.of(EntityType.PATH_DECISION, unit.key() + "@" + d.id());
        int line = d.line() > 0 ? d.line() : unit.where().startLine();
        Map<String, String> a = new LinkedHashMap<>();
        a.put("kind", d.kind());
        a.put("method", unit.owner().value());
        if (!unit.method()) {
            a.put("unit", unit.label());
        }
        a.put("line", String.valueOf(line));
        a.put("col", String.valueOf(d.col()));
        a.put("end", String.valueOf(d.end()));
        a.put("condition", d.condition());
        a.put("outcomes", String.join(" || ", d.arms().stream().map(PathTree.Arm::outcome).toList()));
        a.put("exprLevel", String.valueOf(d.exprLevel()));
        long seen = d.arms().stream().filter(arm -> reached.contains(d.id() + "|" + arm.outcome())).count();
        a.put("reachable", String.valueOf(seen == d.arms().size()));
        Evidence ev = new Evidence(new SourceLocation(unit.where().file(), line, line), NAME, Certainty.EXTRACTED,
                d.kind().toLowerCase(Locale.ROOT) + " decision");
        model.putEntity(new Entity(id, d.kind() + " " + d.condition(), a, List.of(ev)));
        model.addRelationship(new Relationship(unit.owner(), RelationType.CONTAINS, id, TraceStatus.VERIFIED, List.of(ev)));
    }

    private static void writePaths(AnalysisModel model, Unit unit, List<PathFlattener.Row> rows) {
        SourceLocation method = unit.where();
        int n = 0;
        for (PathFlattener.Row r : rows) {
            n++;
            EntityId id = EntityId.of(EntityType.PATH, unit.key() + "#" + n);
            Map<String, String> a = new LinkedHashMap<>();
            a.put("method", unit.owner().value());
            if (!unit.method()) {
                a.put("unit", unit.label());
            }
            a.put("number", String.valueOf(n));
            a.put("when", r.when().isEmpty() ? "always" : String.join(" AND ", r.when()));
            a.put("atoms", String.join(" ;; ", r.when()));                       // the conditions, one by one
            a.put("thenList", String.join(" ;; ", r.effects()));                  // the effects, one by one
            a.put("then", r.effects().isEmpty() ? "nothing" : String.join(" ; ", r.effects()));
            a.put("exit", r.exit());
            if (r.value() != null) {
                a.put("value", r.value());
            }
            a.put("outcomes", String.join(" ; ", r.outcomes()));
            a.put("branches", String.join(" ;; ", r.branches()));                      // decisionId|outcome, per outcome taken
            a.put("thenRefs", String.join(" ;; ", r.refs().stream().map(x -> x == null ? "-" : x).toList()));
            a.put("thenLines", String.join(",", r.lines().stream().map(String::valueOf).toList()));
            if (r.exitRef() != null) {
                a.put("exitRef", r.exitRef());
            }
            a.put("exitLine", String.valueOf(r.line()));
            int line = r.line() > 0 ? r.line() : method.startLine();
            int clamped = Math.max(method.startLine(), Math.min(line, method.endLine()));
            Evidence ev = new Evidence(new SourceLocation(method.file(), clamped, clamped), NAME, Certainty.EXTRACTED, "path " + n);
            model.putEntity(new Entity(id, "path " + n, a, List.of(ev)));
            model.addRelationship(new Relationship(unit.owner(), RelationType.CONTAINS, id, TraceStatus.VERIFIED, List.of(ev)));
        }
    }
}
