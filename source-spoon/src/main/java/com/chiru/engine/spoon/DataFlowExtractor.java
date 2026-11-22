package com.chiru.engine.spoon;

import com.chiru.engine.model.*;
import java.nio.file.Path;
import java.util.*;
import spoon.reflect.CtModel;
import spoon.reflect.code.*;
import spoon.reflect.cu.SourcePosition;
import spoon.reflect.declaration.*;
import spoon.reflect.reference.CtVariableReference;
import spoon.reflect.visitor.filter.TypeFilter;

/**
 * Phase 6a: variables, defining expressions and what each value is derived from.
 *
 * <ul>
 *   <li>VARIABLE: parameters, locals and catch variables.</li>
 *   <li>EXPRESSION: one per local initialiser, assignment (plain or compound) and return statement.</li>
 *   <li>{@code variable/field DERIVED_FROM expression} and {@code expression DERIVED_FROM operand}, where an
 *       operand is a variable, a field (constants included) or an invocation whose result is used.</li>
 *   <li>{@code expression CONTAINS branch} for a ternary written inside the expression.</li>
 *   <li>{@code expression GUARDED_BY branch} when the expression sits inside a branch (arm in
 *       {@code parentArm}), and {@code branch DERIVED_FROM operand} for what its condition reads.</li>
 * </ul>
 *
 * It is flow-insensitive: every definition of a variable is kept, none is chosen. An operand edge to an
 * invocation is as strong as the weakest call edge of that invocation; the value behind the call is not
 * followed (that is interprocedural lineage, a later phase). Reads that resolve to nothing in the analysed
 * sources (JDK constants such as {@code RoundingMode.HALF_UP}) are listed in {@code externalOperands}.
 */
final class DataFlowExtractor {

    private static final String EXTRACTOR = SpoonSourceStage.NAME;
    private static final int TEXT_LIMIT = 300;

    private final AnalysisModel model;
    private final Map<Path, String> displayPaths;
    private int outsideMember;
    private int unmappedTargets;
    private int unaryUpdates;
    private int unboundArguments;
    private SourceLocation firstUnmapped;

    DataFlowExtractor(AnalysisModel model, Map<Path, String> displayPaths) {
        this.model = model;
        this.displayPaths = displayPaths;
    }

    void extract(CtModel ctModel) {
        declareVariables(ctModel);
        for (CtLocalVariable<?> v : this.<CtLocalVariable<?>>all(ctModel, CtLocalVariable.class)) {
            if (!v.isImplicit() && v.getDefaultExpression() != null) {
                define(v, "init", v.getDefaultExpression(), variableId(v), v.getSimpleName(), false);
            }
        }
        for (CtAssignment<?, ?> a : this.<CtAssignment<?, ?>>all(ctModel, CtAssignment.class)) {
            if (a.isImplicit()) {
                continue;
            }
            EntityId target = null;
            if (a.getAssigned() instanceof CtVariableWrite<?> w) {
                target = resolve(w.getVariable());
            }
            if (target == null) {
                unmappedTargets++;
                if (firstUnmapped == null) {
                    firstUnmapped = SpoonIds.locationOf(a, displayPaths);
                }
            }
            boolean compound = a instanceof CtOperatorAssignment<?, ?>;
            define(a, compound ? "compound" : "assign", a.getAssignment(), target,
                    abbrev(a.getAssigned().toString()), compound);
        }
        for (CtReturn<?> r : this.<CtReturn<?>>all(ctModel, CtReturn.class)) {
            if (!r.isImplicit() && r.getReturnedExpression() != null) {
                define(r, "return", r.getReturnedExpression(), null, null, false);
            }
        }
        bindArguments(ctModel);
        bindResults();
        unaryUpdates = (int) this.<CtUnaryOperator<?>>all(ctModel, CtUnaryOperator.class).stream()
                .filter(u -> u.getOperand() instanceof CtVariableWrite<?>).count();
        conditionReads(ctModel);
        report();
    }

    // ---------------------------------------------------------------- variables

    private void declareVariables(CtModel ctModel) {
        for (CtVariable<?> v : this.<CtVariable<?>>all(ctModel, CtVariable.class)) {
            String kind = v instanceof CtParameter<?> ? "parameter" : v instanceof CtCatchVariable<?> ? "catch"
                    : v instanceof CtLocalVariable<?> ? "local" : null;
            if (kind == null || v.isImplicit()) {
                continue;
            }
            CtTypeMember member = SpoonIds.enclosingMember(v);
            if (member == null) {
                outsideMember++;
                continue;
            }
            EntityId id = variableId(v);
            if (id == null) {
                continue;
            }
            Map<String, String> a = new LinkedHashMap<>();
            a.put("kind", kind);
            a.put("type", SpoonIds.typeName(v.getType()));
            if (v instanceof CtParameter<?>) {
                List<CtParameter<?>> declared = SpoonIds.parameters(member);
                for (int i = 0; i < declared.size(); i++) {
                    if (declared.get(i) == v) {
                        a.put("index", String.valueOf(i + 1));        // 1-based, as in SQL flowsTo "#argN"
                    }
                }
            }
            Evidence ev = evidence(v, member, kind + " variable declaration");
            model.putEntity(new Entity(id, v.getSimpleName(), a, List.of(ev)));
            link(SpoonIds.methodId(member), RelationType.CONTAINS, id, TraceStatus.VERIFIED, ev);
        }
    }

    /** {@code variable:<method key>#<name>@line:col}; the position keeps shadowed or reused names apart. */
    private static EntityId variableId(CtVariable<?> v) {
        CtTypeMember member = SpoonIds.enclosingMember(v);
        SourcePosition pos = v.getPosition();
        if (member == null || pos == null || !pos.isValidPosition()) {
            return null;
        }
        return EntityId.of(EntityType.VARIABLE,
                SpoonIds.methodKey(member) + "#" + v.getSimpleName() + "@" + pos.getLine() + ":" + pos.getColumn());
    }

    /** The VARIABLE or FIELD entity a reference points at, or null when it is not in the analysed sources. */
    private EntityId resolve(CtVariableReference<?> ref) {
        if (ref == null) {
            return null;
        }
        CtVariable<?> decl = ref.getDeclaration();
        EntityId id = null;
        if (decl instanceof CtField<?> f && f.getDeclaringType() != null) {
            id = EntityId.of(EntityType.FIELD, f.getDeclaringType().getQualifiedName() + "#" + f.getSimpleName());
        } else if (decl != null) {
            id = variableId(decl);
        }
        return id != null && model.entity(id).isPresent() ? id : null;
    }

    // ---------------------------------------------------------------- definitions

    private void define(CtElement statement, String kind, CtExpression<?> value, EntityId target,
                        String targetText, boolean targetIsOperand) {
        CtTypeMember member = SpoonIds.enclosingMember(statement);
        SourcePosition pos = statement.getPosition();
        if (member == null) {
            outsideMember++;
            return;
        }
        if (pos == null || !pos.isValidPosition()) {
            return;
        }
        EntityId methodId = SpoonIds.methodId(member);
        EntityId id = EntityId.of(EntityType.EXPRESSION, SpoonIds.methodKey(member) + "@" + pos.getLine() + ":"
                + pos.getColumn() + "-" + pos.getSourceEnd() + ":" + kind);
        Operands ops = collect(value, member);
        if (targetIsOperand && target != null) {
            ops.edges.putIfAbsent(target, TraceStatus.VERIFIED);
        }

        Map<String, String> a = new LinkedHashMap<>();
        a.put("kind", kind);
        a.put("text", abbrev(value.toString()));
        if (targetText != null) {
            a.put("target", targetText);
        }
        putIfNotEmpty(a, "operators", String.join(", ", ops.operators));
        putIfNotEmpty(a, "literals", String.join(", ", ops.literals));
        putIfNotEmpty(a, "externalOperands", String.join(", ", ops.external));
        SpoonBranches.Placement placement = SpoonBranches.placement(statement, methodId);
        if (placement.arm() != null) {
            a.put("parentArm", placement.arm());
        }
        Evidence ev = evidence(statement, member, kind + " expression");
        model.putEntity(new Entity(id, abbrev(value.toString()), a, List.of(ev)));
        link(methodId, RelationType.CONTAINS, id, TraceStatus.VERIFIED, ev);
        if (isStringConcatenation(value)) {
            List<CtExpression<?>> parts = new ArrayList<>();
            flatten(value, parts);
            if (parts.size() >= 2) {
                declareParts(id, parts, member, statement);
            }
        }
        for (CtConditional<?> c : this.<CtConditional<?>>within(value, CtConditional.class)) {
            EntityId ternary = c.isImplicit() ? null : SpoonBranches.branchId(c);
            if (ternary != null && model.entity(ternary).isPresent()) {
                link(id, RelationType.CONTAINS, ternary, TraceStatus.VERIFIED,
                        evidence(c, member, "conditional expression inside this expression"));
            }
        }
        if (placement.arm() != null && model.entity(placement.parent()).isPresent()) {
            link(id, RelationType.GUARDED_BY, placement.parent(), TraceStatus.VERIFIED,
                    evidence(statement, member, "inside " + placement.arm() + " arm"));
        }
        if (target != null) {
            link(target, RelationType.DERIVED_FROM, id, TraceStatus.VERIFIED,
                    evidence(statement, member, kind + " of " + targetText));
        }
        ops.edges.forEach((operand, status) -> link(id, RelationType.DERIVED_FROM, operand, status,
                evidence(statement, member, "operand of " + kind + " expression")));
    }

    private static boolean isStringConcatenation(CtExpression<?> e) {
        return e instanceof CtBinaryOperator<?> b && b.getKind() == BinaryOperatorKind.PLUS && b.getType() != null
                && "java.lang.String".equals(b.getType().getQualifiedName());
    }

    private static void flatten(CtExpression<?> e, List<CtExpression<?>> parts) {
        if (isStringConcatenation(e)) {
            CtBinaryOperator<?> b = (CtBinaryOperator<?>) e;
            flatten(b.getLeftHandOperand(), parts);
            flatten(b.getRightHandOperand(), parts);
        } else {
            parts.add(e);
        }
    }

    /**
     * The ordered operands of a top-level string concatenation, as EXPRESSION entities of kind {@code part}. A
     * fixed-width line is built this way, so order is the column order. {@code width} is the first integer literal
     * argument of the part's call (e.g. {@code padLeft(x, 10, '0')}), a heuristic that says where it came from.
     */
    private void declareParts(EntityId parent, List<CtExpression<?>> parts, CtTypeMember member, CtElement statement) {
        for (int i = 0; i < parts.size(); i++) {
            CtExpression<?> part = parts.get(i);
            int index = i + 1;
            EntityId id = EntityId.of(EntityType.EXPRESSION,
                    parent.value().substring(parent.value().indexOf(':') + 1) + ":part#" + index);
            Operands ops = collect(part, member);
            Map<String, String> a = new LinkedHashMap<>();
            a.put("kind", "part");
            a.put("index", String.valueOf(index));
            a.put("text", abbrev(part.toString()));
            a.put("parent", parent.value());
            if (part instanceof CtInvocation<?> call) {
                for (CtExpression<?> arg : call.getArguments()) {
                    if (arg instanceof CtLiteral<?> l && l.getValue() instanceof Integer width) {
                        a.put("width", String.valueOf(width));
                        a.put("widthSource", "first integer literal argument of " + call.getExecutable().getSimpleName());
                        break;
                    }
                }
            }
            putIfNotEmpty(a, "literals", String.join(", ", ops.literals));
            Evidence ev = evidence(part, statement, "part " + index + " of a string concatenation");
            model.putEntity(new Entity(id, abbrev(part.toString()), a, List.of(ev)));
            link(parent, RelationType.CONTAINS, id, TraceStatus.VERIFIED, ev);
            ops.edges.forEach((operand, status) -> link(id, RelationType.DERIVED_FROM, operand, status,
                    evidence(part, statement, "operand of part " + index)));
        }
    }

    private void conditionReads(CtModel ctModel) {
        for (CtElement branch : this.<CtElement>all(ctModel, CtElement.class)) {
            CtElement condition = SpoonBranches.branchKind(branch) == null || branch.isImplicit() ? null
                    : SpoonBranches.conditionOf(branch);
            EntityId branchId = condition == null ? null : SpoonBranches.branchId(branch);
            CtTypeMember member = condition == null ? null : SpoonIds.enclosingMember(branch);
            if (branchId == null || member == null || model.entity(branchId).isEmpty()) {
                continue;
            }
            collect(condition, member).edges.forEach((operand, status) -> link(branchId, RelationType.DERIVED_FROM,
                    operand, status, evidence(branch, member, "read in the condition")));
        }
    }

    // ---------------------------------------------------------------- interprocedural bindings (Phase 7a)

    /**
     * One EXPRESSION (kind {@code argument}) per call or constructor argument, owned by the INVOCATION, with its
     * operands. Each analysed callee the call may reach gets {@code parameter DERIVED_FROM argument}, as strong as
     * the call edge: VERIFIED for a statically bound call, PARTIAL for every possible implementer of a dynamic one.
     */
    private void bindArguments(CtModel ctModel) {
        for (CtAbstractInvocation<?> inv : this.<CtAbstractInvocation<?>>all(ctModel, CtAbstractInvocation.class)) {
            CtTypeMember member = SpoonIds.enclosingMember(inv);
            SourcePosition pos = inv.getPosition();
            if (inv.isImplicit() || member == null || pos == null || !pos.isValidPosition()) {
                continue;
            }
            EntityId invId = SpoonIds.invocationId(inv, member);
            if (model.entity(invId).isEmpty()) {
                continue;
            }
            if (inv instanceof CtInvocation<?> call && call.getTarget() instanceof CtVariableRead<?> read) {
                EntityId receiver = resolve(read.getVariable());
                if (receiver != null) {         // lets a tracer tell the object a call is made on from the values passed in
                    model.putEntity(new Entity(invId, model.entity(invId).orElseThrow().name(),
                            Map.of("receiver", receiver.value()), List.of()));
                }
            }
            SpoonBranches.Placement placement = SpoonBranches.placement(inv, SpoonIds.methodId(member));
            List<? extends CtExpression<?>> args = inv.getArguments();
            for (int i = 0; i < args.size(); i++) {
                CtExpression<?> arg = args.get(i);
                if (arg.isImplicit()) {
                    continue;
                }
                int index = i + 1;
                EntityId argId = EntityId.of(EntityType.EXPRESSION,
                        invId.value().substring(invId.value().indexOf(':') + 1) + ":argument#" + index);
                Operands ops = collect(arg, member);
                Map<String, String> a = new LinkedHashMap<>();
                a.put("kind", "argument");
                a.put("index", String.valueOf(index));
                a.put("text", abbrev(arg.toString()));
                a.put("invocation", invId.value());
                putIfNotEmpty(a, "operators", String.join(", ", ops.operators));
                putIfNotEmpty(a, "literals", String.join(", ", ops.literals));
                putIfNotEmpty(a, "externalOperands", String.join(", ", ops.external));
                if (placement.arm() != null) {
                    a.put("parentArm", placement.arm());
                }
                Evidence ev = evidence(arg, inv, "argument " + index);
                model.putEntity(new Entity(argId, abbrev(arg.toString()), a, List.of(ev)));
                link(invId, RelationType.CONTAINS, argId, TraceStatus.VERIFIED, ev);
                if (placement.arm() != null && model.entity(placement.parent()).isPresent()) {
                    link(argId, RelationType.GUARDED_BY, placement.parent(), TraceStatus.VERIFIED,
                            evidence(arg, inv, "inside " + placement.arm() + " arm"));
                }
                ops.edges.forEach((operand, status) -> link(argId, RelationType.DERIVED_FROM, operand, status,
                        evidence(arg, inv, "operand of argument " + index)));

                for (Relationship call : model.outgoing(invId, RelationType.INVOKES)) {
                    Optional<EntityId> parameter = parameterOf(call.to(), index);
                    if (parameter.isPresent()) {
                        link(parameter.get(), RelationType.DERIVED_FROM, argId, call.status(),
                                evidence(arg, inv, "argument " + index + " flows into this parameter"));
                    } else if (hasParameters(call.to())) {
                        unboundArguments++;
                    }
                }
            }
        }
    }

    /** {@code call DERIVED_FROM return expression} of every analysed method the call may reach. */
    private void bindResults() {
        for (Entity inv : model.entitiesOfType(EntityType.INVOCATION)) {
            if (inv.evidence().isEmpty()) {
                continue;
            }
            for (Relationship call : model.outgoing(inv.id(), RelationType.INVOKES)) {
                for (Relationship member : model.outgoing(call.to(), RelationType.CONTAINS)) {
                    boolean isReturn = member.to().type() == EntityType.EXPRESSION && model.entity(member.to())
                            .map(e -> "return".equals(e.attributes().get("kind"))).orElse(false);
                    if (isReturn) {
                        link(inv.id(), RelationType.DERIVED_FROM, member.to(), call.status(), new Evidence(
                                inv.evidence().get(0).location(), EXTRACTOR, Certainty.EXTRACTED,
                                "the call's result is what the callee returns"));
                    }
                }
            }
        }
    }

    private Optional<EntityId> parameterOf(EntityId method, int index) {
        return model.outgoing(method, RelationType.CONTAINS).stream().map(Relationship::to)
                .filter(id -> id.type() == EntityType.VARIABLE)
                .filter(id -> model.entity(id).map(e -> "parameter".equals(e.attributes().get("kind"))
                        && String.valueOf(index).equals(e.attributes().get("index"))).orElse(false))
                .findFirst();
    }

    private boolean hasParameters(EntityId method) {
        return model.outgoing(method, RelationType.CONTAINS).stream().map(Relationship::to)
                .anyMatch(id -> id.type() == EntityType.VARIABLE);
    }

    // ---------------------------------------------------------------- operands

    private static final class Operands {
        final Map<EntityId, TraceStatus> edges = new LinkedHashMap<>();
        final Set<String> literals = new LinkedHashSet<>();
        final Set<String> external = new LinkedHashSet<>();
        final Set<String> operators = new LinkedHashSet<>();
    }

    private Operands collect(CtElement root, CtTypeMember member) {
        Operands ops = new Operands();
        for (CtVariableRead<?> r : this.<CtVariableRead<?>>within(root, CtVariableRead.class)) {
            EntityId id = resolve(r.getVariable());
            if (id != null) {
                ops.edges.putIfAbsent(id, TraceStatus.VERIFIED);
            } else {
                ops.external.add(abbrev(r.toString()));
            }
        }
        for (CtAbstractInvocation<?> inv : this.<CtAbstractInvocation<?>>within(root, CtAbstractInvocation.class)) {
            SourcePosition pos = inv.getPosition();
            if (inv.isImplicit() || pos == null || !pos.isValidPosition()) {
                continue;
            }
            EntityId invId = SpoonIds.invocationId(inv, member);
            if (model.entity(invId).isPresent()) {
                ops.edges.merge(invId, invocationStatus(invId), TraceStatus::weakest);
            }
        }
        for (CtLiteral<?> l : this.<CtLiteral<?>>within(root, CtLiteral.class)) {
            ops.literals.add(String.valueOf(l.getValue()));
        }
        for (CtBinaryOperator<?> b : this.<CtBinaryOperator<?>>within(root, CtBinaryOperator.class)) {
            boolean concatenation = b.getType() != null && "java.lang.String".equals(b.getType().getQualifiedName());
            if (!concatenation) {          // string building is not a computation
                ops.operators.add(b.getKind().name());
            }
        }
        for (CtUnaryOperator<?> u : this.<CtUnaryOperator<?>>within(root, CtUnaryOperator.class)) {
            ops.operators.add(u.getKind().name());
        }
        return ops;
    }

    private TraceStatus invocationStatus(EntityId invId) {
        return model.outgoing(invId, RelationType.INVOKES).stream().map(Relationship::status)
                .reduce(TraceStatus::weakest).orElse(TraceStatus.PARTIAL);
    }

    // ---------------------------------------------------------------- helpers

    private void report() {
        if (unmappedTargets > 0) {
            model.addWarning(new AnalysisWarning(EXTRACTOR, AnalysisWarning.Severity.INFO, unmappedTargets
                    + " assignment(s) write to a target that is not a tracked variable or field (array element, "
                    + "field of a library type); their expressions are kept without a target", firstUnmapped));
        }
        if (unaryUpdates > 0) {
            model.addWarning(new AnalysisWarning(EXTRACTOR, AnalysisWarning.Severity.INFO, unaryUpdates
                    + " increment/decrement update(s) (++/--) are not modelled as definitions yet", null));
        }
        if (unboundArguments > 0) {
            model.addWarning(new AnalysisWarning(EXTRACTOR, AnalysisWarning.Severity.INFO, unboundArguments
                    + " argument(s) could not be matched to a parameter of an analysed callee (varargs); "
                    + "their values are not bound", null));
        }
        if (outsideMember > 0) {
            model.addWarning(new AnalysisWarning(EXTRACTOR, AnalysisWarning.Severity.INFO, outsideMember
                    + " variable(s)/definition(s) sit outside any method or constructor (field initialisers, "
                    + "static blocks) and are not modelled yet", null));
        }
    }

    private void link(EntityId from, RelationType type, EntityId to, TraceStatus status, Evidence ev) {
        model.addRelationship(new Relationship(from, type, to, status, List.of(ev)));
    }

    private Evidence evidence(CtElement el, CtElement fallback, String note) {
        SourceLocation loc = SpoonIds.locationOf(el, displayPaths);
        if (loc == null) {
            loc = SpoonIds.locationOf(fallback, displayPaths);
        }
        return new Evidence(loc, EXTRACTOR, Certainty.EXTRACTED, note);
    }

    private static void putIfNotEmpty(Map<String, String> m, String k, String v) {
        if (v != null && !v.isBlank()) {
            m.put(k, v);
        }
    }

    private static String abbrev(String s) {
        String flat = s.replaceAll("\\s+", " ").trim();
        return flat.length() <= TEXT_LIMIT ? flat : flat.substring(0, TEXT_LIMIT) + "...";
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <E> List<E> all(CtModel ctModel, Class<?> type) {
        return new ArrayList<>((List) ctModel.getElements(new TypeFilter(type)));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <E> List<E> within(CtElement root, Class<?> type) {
        return new ArrayList<>((List) root.getElements(new TypeFilter(type)));
    }
}
