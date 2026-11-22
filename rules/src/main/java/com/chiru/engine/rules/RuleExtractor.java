package com.chiru.engine.rules;

import com.chiru.engine.core.PureLibraries;
import com.chiru.engine.model.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Two kinds of rule, both taken from structure only:
 *
 * <ul>
 *   <li><b>conditional</b>: one rule per arm (then/else of an if or ternary, a switch case) that changes a value.
 *       The rule lists the whole chain of enclosing conditions, the effects (assignments and returns in the arm),
 *       the constants and literals involved and what the conditions read.</li>
 *   <li><b>computation</b>: an unconditional definition (initialiser, assignment, return) that computes a value
 *       from operators, a named constant, or a literal.</li>
 * </ul>
 *
 * Loops and try blocks are not conditions here: they are skipped when walking up the enclosing branches.
 * The implicit "else" of an {@code if} without an else block is not an arm and gets no rule.
 */
final class RuleExtractor {

    private static final String EXTRACTOR = RulesStage.NAME;
    private static final Set<String> CONDITIONAL_KINDS = Set.of("if", "ternary", "switch", "switch-expression");

    private record Guard(EntityId branch, String arm) {}

    private static final class Rule {
        final EntityId id;
        final String kind;
        final List<Guard> conditions;
        final List<Entity> expressions = new ArrayList<>();
        final List<String> effects = new ArrayList<>();
        EntityId owner;                              // set for path-derived rules; otherwise found from the first expression
        String conditionText;                        // set for path-derived rules: the readable condition as a whole
        final Set<String> outcomes = new LinkedHashSet<>();
        final List<Guard> guards = new ArrayList<>();      // path-derived: branches the rule sits under, with the outcome taken

        Rule(EntityId id, String kind, List<Guard> conditions) {
            this.id = id;
            this.kind = kind;
            this.conditions = conditions;
        }
    }

    private final AnalysisModel model;
    private final Map<EntityId, Rule> rules = new LinkedHashMap<>();
    private int ternariesWithoutValue;

    private final ModelIndex index;
    private final List<String> pureLibraryPrefixes;
    /** Expressions already turned into rules from the paths of their unit; the arm-based pass skips them. */
    private final Set<EntityId> handled = new HashSet<>();
    private int pathRuleCount;

    RuleExtractor(AnalysisModel model, List<String> pureLibraryPrefixes) {
        this.model = model;
        this.index = new ModelIndex(model);
        this.pureLibraryPrefixes = pureLibraryPrefixes;
    }

    void extract() {
        pathRules();
        for (Entity e : model.entitiesOfType(EntityType.EXPRESSION)) {
            if (handled.contains(e.id()) || !valueChanging(e)) {
                continue;
            }
            List<Guard> conditions = conditionsOf(chainOfExpression(e));
            if (!conditions.isEmpty()) {
                conditionalRule(conditions, e, effectText(e));
            } else if (computes(e)) {
                Rule r = new Rule(EntityId.of(EntityType.BUSINESS_RULE, "calc:" + key(e.id())), "computation", List.of());
                r.expressions.add(e);
                r.effects.add(effectText(e));
                rules.putIfAbsent(r.id, r);
            }
        }
        for (Entity t : model.entitiesOfType(EntityType.BRANCH)) {
            if ("ternary".equals(t.attributes().get("kind"))) {
                ternaryRules(t);
            }
        }
        rules.values().forEach(this::write);
        summarise();
    }

    // ---------------------------------------------------------------- rules from paths

    private record Occurrence(Entity expression, Set<Integer> paths) {}

    /**
     * Rules from the path model. Each value-changing effect is looked up on every path of its unit: on all of them it is
     * unconditional (a computation); on some of them it happens when the conditions of those paths hold. Effects that happen on
     * exactly the same paths share one rule. This is flow-sensitive, so a statement after an early return is not shown as
     * unconditional, which the arm-based pass cannot tell.
     */
    private void pathRules() {
        Map<String, List<Entity>> byUnit = new LinkedHashMap<>();
        for (Entity p : model.entitiesOfType(EntityType.PATH)) {
            String v = key(p.id());
            byUnit.computeIfAbsent(v.substring(0, v.lastIndexOf('#')), k -> new ArrayList<>()).add(p);
        }
        byUnit.forEach(this::unitRules);
    }

    private static List<String> split(String s) {
        return s == null || s.isEmpty() ? List.of() : List.of(s.split(" ;; ", -1));
    }

    private void unitRules(String unitKey, List<Entity> unitPaths) {
        List<Entity> paths = new ArrayList<>(unitPaths);
        paths.sort(Comparator.comparingInt(p -> Integer.parseInt(p.attributes().get("number"))));
        EntityId owner = index.in(paths.get(0).id(), RelationType.CONTAINS).stream().map(Relationship::from).findFirst().orElse(null);
        if (owner == null) {
            return;
        }
        String ownerKey = ModelIndex.key(owner);
        Map<String, Occurrence> found = new LinkedHashMap<>();
        for (int i = 0; i < paths.size(); i++) {
            Map<String, String> a = paths.get(i).attributes();
            List<String> refs = new ArrayList<>(split(a.get("thenRefs")));
            if (a.containsKey("exitRef")) {
                refs.add(a.get("exitRef"));
            }
            for (String ref : refs) {
                if (ref.equals("-")) {
                    continue;
                }
                Optional<Entity> expression = model.entity(EntityId.of(EntityType.EXPRESSION, ownerKey + "@" + ref));
                if (expression.isPresent()) {
                    handled.add(expression.get().id());
                    found.computeIfAbsent(ref, k -> new Occurrence(expression.get(), new TreeSet<>())).paths().add(i);
                }
            }
        }
        Map<Set<Integer>, List<Entity>> groups = new LinkedHashMap<>();
        for (Occurrence o : found.values()) {
            Entity e = o.expression();
            boolean ternary = index.out(e.id(), RelationType.CONTAINS).stream().anyMatch(r -> r.to().type() == EntityType.BRANCH);
            if (!valueChanging(e) || ternary) {
                continue;                       // a ternary value is covered by its own arm rules
            }
            if (o.paths().size() == paths.size()) {
                if (computes(e)) {
                    Rule r = new Rule(EntityId.of(EntityType.BUSINESS_RULE, "calc:" + key(e.id())), "computation", List.of());
                    r.owner = owner;
                    r.expressions.add(e);
                    r.effects.add(effectText(e));
                    rules.putIfAbsent(r.id, r);
                }
            } else {
                groups.computeIfAbsent(o.paths(), k -> new ArrayList<>()).add(e);
            }
        }
        for (Map.Entry<Set<Integer>, List<Entity>> g : groups.entrySet()) {
            List<Entity> chosen = new ArrayList<>();
            for (int i : g.getKey()) {
                chosen.add(paths.get(i));
            }
            String numbers = chosen.stream().map(p -> p.attributes().get("number")).collect(Collectors.joining(","));
            EntityId id = EntityId.of(EntityType.BUSINESS_RULE, "cond:" + unitKey + "#" + numbers);
            Rule r = new Rule(id, "conditional", List.of());
            r.owner = owner;
            r.conditionText = pathCondition(chosen);
            g.getValue().forEach(e -> {
                r.expressions.add(e);
                r.effects.add(effectText(e));
            });
            Set<Guard> guards = new LinkedHashSet<>();
            for (Entity p : chosen) {
                for (String token : split(p.attributes().get("branches"))) {
                    int bar = token.lastIndexOf('|');
                    resolveBranch(unitKey, ownerKey, token.substring(0, bar)).ifPresent(b -> guards.add(new Guard(b, token.substring(bar + 1))));
                }
                for (String outcome : p.attributes().getOrDefault("outcomes", "").split(" ; ")) {
                    if (!outcome.isBlank()) {
                        r.outcomes.add(outcome);
                    }
                }
            }
            r.guards.addAll(guards);
            rules.putIfAbsent(r.id, r);
            pathRuleCount++;
        }
    }

    /** The conditions all chosen paths share, then (when the paths differ) the alternatives, as "a AND (b OR c)". */
    private static String pathCondition(List<Entity> chosen) {
        List<List<String>> atoms = chosen.stream().map(p -> split(p.attributes().get("atoms"))).toList();
        List<String> common = new ArrayList<>(atoms.get(0));
        atoms.forEach(common::retainAll);
        Set<String> alternatives = new LinkedHashSet<>();
        boolean someUnconditional = false;
        for (List<String> a : atoms) {
            List<String> rest = new ArrayList<>(a);
            rest.removeAll(common);
            if (rest.isEmpty()) {
                someUnconditional = true;
            } else {
                alternatives.add(String.join(" AND ", rest));
            }
        }
        List<String> parts = new ArrayList<>(common);
        if (!someUnconditional && alternatives.size() > 1) {
            parts.add("(" + String.join(") OR (", alternatives) + ")");
        } else if (!someUnconditional && alternatives.size() == 1) {
            parts.add(alternatives.iterator().next());
        }
        return String.join(" AND ", parts);
    }

    private static final Map<String, List<String>> BRANCH_KINDS = Map.of("IF", List.of("if"), "TERNARY", List.of("ternary"),
            "SWITCH", List.of("switch"), "SWITCH_EXPR", List.of("switch-expression"));

    /** The BRANCH entity of a path decision (same position, same owner), if it is an if, ternary or switch. */
    private Optional<EntityId> resolveBranch(String unitKey, String ownerKey, String decisionId) {
        Optional<Entity> d = model.entity(EntityId.of(EntityType.PATH_DECISION, unitKey + "@" + decisionId));
        if (d.isEmpty()) {
            return Optional.empty();
        }
        Map<String, String> a = d.get().attributes();
        for (String kind : BRANCH_KINDS.getOrDefault(a.get("kind"), List.of())) {
            EntityId id = EntityId.of(EntityType.BRANCH, ownerKey + "@" + a.get("line") + ":" + a.get("col") + "-" + a.get("end") + ":" + kind);
            if (model.entity(id).isPresent()) {
                return Optional.of(id);
            }
        }
        return Optional.empty();
    }

    // ---------------------------------------------------------------- finding rules

    private void conditionalRule(List<Guard> conditions, Entity expression, String effect) {
        Guard inner = conditions.get(0);
        EntityId id = EntityId.of(EntityType.BUSINESS_RULE, "cond:" + key(inner.branch()) + "#" + inner.arm());
        Rule r = rules.computeIfAbsent(id, k -> new Rule(id, "conditional", conditions));
        if (!r.expressions.contains(expression)) {
            r.expressions.add(expression);
        }
        r.effects.add(effect);
    }

    /** A ternary gives one rule per arm; its effect is the value the arm produces for the owning expression. */
    private void ternaryRules(Entity ternary) {
        Entity owner = index.in(ternary.id(), RelationType.CONTAINS).stream()
                .map(r -> model.entity(r.from()).orElseThrow()).filter(e -> e.type() == EntityType.EXPRESSION)
                .findFirst().orElse(null);
        if (owner == null || !valueChanging(owner)) {
            ternariesWithoutValue++;
            return;
        }
        for (String arm : List.of("then", "else")) {
            List<Guard> chain = new ArrayList<>();
            chain.add(new Guard(ternary.id(), arm));
            chain.addAll(climb(ternary.id()));
            String value = ternary.attributes().get(arm + "Value");
            String effect = ("return".equals(owner.attributes().get("kind")) ? "return " : owner.attributes().get("target") + " = ")
                    + value + "   [in: " + owner.attributes().get("text") + "]";
            conditionalRule(conditionsOf(chain), owner, effect);
        }
    }

    /** Definitions that produce a value: initialisers and assignments to a target, and returns. */
    private static boolean valueChanging(Entity e) {
        String kind = e.attributes().get("kind");
        return "return".equals(kind) || e.attributes().containsKey("target");
    }

    /** An unconditional definition worth reporting: operators, a named constant, or (not a return) a literal. */
    private boolean computes(Entity e) {
        boolean operators = e.attributes().containsKey("operators");
        boolean literal = e.attributes().containsKey("literals") && !"return".equals(e.attributes().get("kind"));
        boolean hasTernary = index.out(e.id(), RelationType.CONTAINS).stream()
                .anyMatch(r -> r.to().type() == EntityType.BRANCH);   // covered by the ternary's own arm rules
        return !hasTernary && (operators || literal || !constantsOf(List.of(e)).isEmpty());
    }

    // ---------------------------------------------------------------- enclosing conditions

    /** Innermost first: the branch the expression sits in (with its arm), then each branch containing that one. */
    private List<Guard> chainOfExpression(Entity expression) {
        List<Guard> chain = new ArrayList<>();
        for (Relationship g : index.out(expression.id(), RelationType.GUARDED_BY)) {
            chain.add(new Guard(g.to(), expression.attributes().get("parentArm")));
            chain.addAll(climb(g.to()));
        }
        return chain;
    }

    private List<Guard> climb(EntityId branch) {
        List<Guard> chain = new ArrayList<>();
        EntityId current = branch;
        Set<EntityId> seen = new HashSet<>();
        while (seen.add(current)) {
            Entity currentEntity = model.entity(current).orElseThrow();
            EntityId parent = index.in(current, RelationType.CONTAINS).stream().map(Relationship::from)
                    .filter(id -> id.type() == EntityType.BRANCH).findFirst().orElse(null);
            if (parent == null) {
                break;
            }
            chain.add(new Guard(parent, currentEntity.attributes().get("parentArm")));
            current = parent;
        }
        return chain;
    }

    /** Keeps if/ternary/switch arms; drops loops, try blocks and positions that are not an arm (the condition itself). */
    private List<Guard> conditionsOf(List<Guard> chain) {
        return chain.stream().filter(g -> g.arm() != null && !g.arm().equals("condition") && !g.arm().equals("selector"))
                .filter(g -> CONDITIONAL_KINDS.contains(model.entity(g.branch()).orElseThrow().attributes().get("kind")))
                .toList();
    }

    // ---------------------------------------------------------------- writing

    private void write(Rule r) {
        Entity first = r.expressions.get(0);
        EntityId methodId = r.owner != null ? r.owner : index.in(first.id(), RelationType.CONTAINS).stream().map(Relationship::from)
                .filter(id -> id.type() == EntityType.METHOD).findFirst().orElseThrow();
        List<Guard> guardList = r.conditions.isEmpty() ? r.guards : r.conditions;
        Set<EntityId> branches = new LinkedHashSet<>();
        guardList.forEach(g -> branches.add(g.branch()));
        List<Entity> sources = new ArrayList<>(r.expressions);
        branches.forEach(b -> sources.add(model.entity(b).orElseThrow()));

        List<String> conditionTexts = new ArrayList<>();
        if (r.conditionText != null) {
            if (!r.conditionText.isBlank()) {
                conditionTexts.add(r.conditionText);
            }
        } else {
            for (int i = r.conditions.size() - 1; i >= 0; i--) {      // outermost first
                conditionTexts.add(conditionText(r.conditions.get(i)));
            }
        }
        Set<String> reads = new LinkedHashSet<>();
        Set<String> literals = new LinkedHashSet<>();
        for (Entity src : sources) {
            if (src.type() == EntityType.BRANCH) {
                index.out(src.id(), RelationType.DERIVED_FROM)
                        .forEach(d -> reads.add(model.entity(d.to()).orElseThrow().name()));
            } else if (src.attributes().containsKey("literals")) {
                literals.add(src.attributes().get("literals"));
            }
        }

        Map<String, String> a = new LinkedHashMap<>();
        a.put("kind", r.kind);
        a.put("method", methodId.value());
        if (!conditionTexts.isEmpty()) {
            a.put("conditions", String.join(" AND ", conditionTexts));
        }
        if (!r.outcomes.isEmpty()) {
            a.put("outcomes", String.join(" ; ", r.outcomes));      // every decision outcome on the paths this rule covers
        }
        if (!guardList.isEmpty()) {          // every guarding branch with the arm this rule sits in, innermost first
            a.put("guards", guardList.stream().map(g -> g.branch().value() + "#" + g.arm()).collect(Collectors.joining(" ;; ")));
        }
        a.put("effects", String.join(" ; ", new LinkedHashSet<>(r.effects)));
        List<String> constants = constantsOf(sources);
        if (!constants.isEmpty()) {
            a.put("constants", String.join(", ", constants));
        }
        if (!reads.isEmpty()) {
            a.put("reads", String.join(", ", reads));
        }
        if (!literals.isEmpty()) {
            a.put("literals", String.join(", ", literals));
        }

        List<Evidence> evidence = new ArrayList<>();
        sources.forEach(s -> s.evidence().stream().findFirst().ifPresent(ev -> evidence.add(
                new Evidence(ev.location(), EXTRACTOR, Certainty.EXTRACTED,
                        s.type() == EntityType.BRANCH ? "condition of the rule" : "effect of the rule"))));

        List<TraceStatus> statuses = new ArrayList<>();
        Entity ruleEntity = new Entity(r.id, ruleName(r), a, evidence);
        // the entity is written first so the edges below can reference it
        model.putEntity(ruleEntity);
        for (EntityId b : branches) {
            TraceStatus s = conditionStatus(b);
            statuses.add(s);
            link(r.id, RelationType.GUARDED_BY, b, s, model.entity(b).orElseThrow(), "rule is guarded by this condition");
        }
        for (Entity e : r.expressions) {
            TraceStatus s = valueStatus(e.id());
            statuses.add(s);
            link(r.id, RelationType.DERIVED_FROM, e.id(), s, e, "effect of the rule");
        }
        link(methodId, RelationType.APPLIES_RULE, r.id, TraceStatus.VERIFIED, first, "method applies the rule");
        TraceStatus overall = TraceStatus.ofChain(statuses.isEmpty() ? List.of(TraceStatus.VERIFIED) : statuses);
        model.putEntity(new Entity(r.id, ruleEntity.name(), Map.of("traceStatus", overall.name()), List.of()));
    }

    /** A condition whose reads are all traced is VERIFIED; one that reads nothing traceable is PARTIAL. */
    private TraceStatus conditionStatus(EntityId branch) {
        List<Relationship> reads = index.out(branch, RelationType.DERIVED_FROM);
        return reads.isEmpty() ? TraceStatus.PARTIAL
                : reads.stream().map(this::edgeStatus).reduce(TraceStatus::weakest).orElseThrow();
    }

    private TraceStatus valueStatus(EntityId expression) {
        return index.out(expression, RelationType.DERIVED_FROM).stream().map(this::edgeStatus)
                .reduce(TraceStatus::weakest).orElse(TraceStatus.VERIFIED);
    }

    /**
     * An edge to a call is only as strong as the call, except that a call into a pure library (java.math, String,
     * java.time, ...) only transforms its inputs, so it does not weaken the rule. The same list the lineage tracer uses.
     */
    private TraceStatus edgeStatus(Relationship edge) {
        if (edge.status() == TraceStatus.VERIFIED || edge.to().type() != EntityType.INVOCATION) {
            return edge.status();
        }
        List<Relationship> calls = index.out(edge.to(), RelationType.INVOKES);
        boolean allPure = !calls.isEmpty() && calls.stream().allMatch(c -> model.entity(c.to())
                .map(t -> "true".equals(t.attributes().get("external"))
                        && PureLibraries.isPure(t.attributes().get("owner"), pureLibraryPrefixes)).orElse(false));
        return allPure ? TraceStatus.VERIFIED : edge.status();
    }

    private String conditionText(Guard g) {
        Map<String, String> b = model.entity(g.branch()).orElseThrow().attributes();
        String cond = b.get("condition");
        String kind = b.get("kind");
        if (kind.startsWith("switch")) {
            if ("default".equals(g.arm())) {
                return cond + " matches no other case";
            }
            return g.arm().contains(" | ") ? cond + " is one of " + g.arm() : cond + " == " + g.arm();
        }
        return "else".equals(g.arm()) ? "not (" + cond + ")" : cond;
    }

    private String ruleName(Rule r) {
        String text = r.conditionText != null && !r.conditionText.isBlank() ? r.conditionText
                : r.conditions.isEmpty() ? r.effects.get(0) : conditionText(r.conditions.get(0));
        return r.kind + ": " + (text.length() > 80 ? text.substring(0, 80) + "..." : text);
    }

    /** {@code NAME = initializer} for every static final field the sources read. */
    private List<String> constantsOf(List<Entity> sources) {
        Set<String> constants = new LinkedHashSet<>();
        for (Entity s : sources) {
            for (Relationship d : index.out(s.id(), RelationType.DERIVED_FROM)) {
                Entity operand = model.entity(d.to()).orElseThrow();
                String mods = operand.attributes().getOrDefault("modifiers", "");
                if (operand.type() == EntityType.FIELD && mods.contains("static") && mods.contains("final")) {
                    constants.add(operand.name() + " = " + operand.attributes().getOrDefault("initializer", "?"));
                }
            }
        }
        return List.copyOf(constants);
    }

    private static String effectText(Entity e) {
        String kind = e.attributes().get("kind");
        String text = e.attributes().get("text");
        return switch (kind) {
            case "return" -> "return " + text;
            case "compound" -> e.attributes().get("target") + " (compound) " + text;
            default -> e.attributes().get("target") + " = " + text;
        };
    }

    private void link(EntityId from, RelationType type, EntityId to, TraceStatus status, Entity evidenceFrom, String note) {
        Evidence ev = evidenceFrom.evidence().isEmpty() ? new Evidence(null, EXTRACTOR, Certainty.EXTRACTED, note)
                : new Evidence(evidenceFrom.evidence().get(0).location(), EXTRACTOR, Certainty.EXTRACTED, note);
        model.addRelationship(new Relationship(from, type, to, status, List.of(ev)));
    }

    private static String key(EntityId id) {
        return id.value().substring(id.value().indexOf(':') + 1);
    }

    private void summarise() {
        long conditional = rules.values().stream().filter(r -> r.kind.equals("conditional")).count();
        if (pathRuleCount > 0) {
            model.addWarning(new AnalysisWarning(EXTRACTOR, AnalysisWarning.Severity.INFO, pathRuleCount
                    + " conditional rule(s) come from the path model (flow-sensitive); methods without paths use the arm-based pass", null));
        }
        model.addWarning(new AnalysisWarning(EXTRACTOR, AnalysisWarning.Severity.INFO, rules.size() + " rule(s): "
                + conditional + " conditional, " + (rules.size() - conditional) + " computation. Arms without a "
                + "value-changing effect, implicit else paths, loops and switch-expression yields are not rules", null));
        if (ternariesWithoutValue > 0) {
            model.addWarning(new AnalysisWarning(EXTRACTOR, AnalysisWarning.Severity.INFO, ternariesWithoutValue
                    + " conditional expression(s) are not part of an assignment or return and were skipped", null));
        }
    }
}
