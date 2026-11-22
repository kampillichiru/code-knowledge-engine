package com.chiru.engine.scenarios;

import com.chiru.engine.model.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * How a scenario is found, in order of strength:
 * <ol>
 *   <li><b>Dispatch.</b> A conditional rule guarded by a {@code switch} whose effect calls a method on a field that is
 *       initialised with {@code new X(...)}. {@code X} is the handler and the case label is the trigger. VERIFIED when
 *       the field is {@code final} and never reassigned, PARTIAL otherwise.</li>
 *   <li><b>Lineage variant.</b> A class the lineage traces name as a scenario (it implements an analysed interface
 *       on the path to an output) but no dispatcher was recognised. Always PARTIAL, with the trigger marked unknown.</li>
 * </ol>
 * Methods of a scenario are the handler's own methods and everything they call inside the analysed sources.
 */
final class ScenarioExtractor {

    private static final String NAME = ScenarioStage.NAME;
    private static final Pattern NEW_TYPE = Pattern.compile("^new\\s+([\\w.$]+)\\s*\\(");
    private static final Pattern QUOTED = Pattern.compile("\"([^\"]+)\"");

    private final AnalysisModel model;
    private final ModelIndex index;

    private record Handler(EntityId classId, Entity dispatchRule, String trigger, EntityId dispatcher,
                           TraceStatus status, String note) {}

    ScenarioExtractor(AnalysisModel model) {
        this.model = model;
        this.index = new ModelIndex(model);
    }

    void extract() {
        Map<EntityId, Handler> handlers = new LinkedHashMap<>();
        for (Entity rule : model.entitiesOfType(EntityType.BUSINESS_RULE)) {
            dispatchHandler(rule).ifPresent(h -> handlers.putIfAbsent(h.classId(), h));
        }
        for (Entity t : model.entitiesOfType(EntityType.TRANSFORMATION)) {
            String variant = ModelIndex.attr(t, "scenario");
            if (variant == null || variant.startsWith("(")) {
                continue;
            }
            for (Entity cls : model.entitiesOfType(EntityType.CLASS)) {
                boolean match = cls.name().equals(variant) && !"true".equals(ModelIndex.attr(cls, "external"));
                if (match) {
                    handlers.putIfAbsent(cls.id(), new Handler(cls.id(), null, "unknown: no dispatcher was recognised",
                            null, TraceStatus.PARTIAL, "named by the lineage traces only"));
                }
            }
        }
        if (handlers.isEmpty()) {
            model.addWarning(new AnalysisWarning(NAME, AnalysisWarning.Severity.INFO,
                    "no business scenario was discovered (no dispatcher with handler classes, no lineage variants)", null));
            return;
        }
        List<Handler> ordered = new ArrayList<>(handlers.values());
        ordered.sort(Comparator.comparing(h -> h.classId().value()));
        for (Handler h : ordered) {
            write(h);
        }
        markDivergence();
        model.addWarning(new AnalysisWarning(NAME, AnalysisWarning.Severity.INFO, ordered.size() + " scenario(s) discovered: "
                + ordered.stream().map(h -> index.entity(h.classId()).name()).collect(Collectors.joining(", "))
                + ". A scenario is a handler class selected by a dispatcher; reflection and dependency-injection "
                + "dispatch are not recognised", null));
    }

    // ---------------------------------------------------------------- finding handlers

    private Optional<Handler> dispatchHandler(Entity rule) {
        if (!"conditional".equals(ModelIndex.attr(rule, "kind"))) {
            return Optional.empty();
        }
        boolean viaSwitch = index.out(rule.id(), RelationType.GUARDED_BY).stream().map(r -> index.entity(r.to()))
                .anyMatch(b -> ModelIndex.attr(b, "kind", "").startsWith("switch"));
        if (!viaSwitch) {
            return Optional.empty();
        }
        for (Relationship effect : index.out(rule.id(), RelationType.DERIVED_FROM)) {
            if (effect.to().type() != EntityType.EXPRESSION) {
                continue;
            }
            for (Relationship operand : index.out(effect.to(), RelationType.DERIVED_FROM)) {
                if (operand.to().type() != EntityType.INVOCATION) {
                    continue;
                }
                String receiver = ModelIndex.attr(index.entity(operand.to()), "receiver");
                Optional<Entity> field = receiver == null ? Optional.empty() : index.find(new EntityId(receiver))
                        .filter(e -> e.type() == EntityType.FIELD);
                Optional<EntityId> type = field.flatMap(this::constructedType);
                if (type.isPresent()) {
                    Entity f = field.get();
                    boolean fixed = ModelIndex.attr(f, "modifiers", "").contains("final")
                            && index.out(f.id(), RelationType.DERIVED_FROM).isEmpty();
                    return Optional.of(new Handler(type.get(), rule, ModelIndex.attr(rule, "conditions", ""),
                            new EntityId(ModelIndex.attr(rule, "method")), fixed ? TraceStatus.VERIFIED : TraceStatus.PARTIAL,
                            "field " + f.name() + (fixed ? " is final and only initialised with new "
                                    : " may be reassigned; initialised with new ") + ModelIndex.key(type.get())));
                }
            }
        }
        return Optional.empty();
    }

    /** The analysed class a field is initialised with ({@code new com.x.Foo(...)}), if it is one. */
    private Optional<EntityId> constructedType(Entity field) {
        String init = ModelIndex.attr(field, "initializer");
        if (init == null) {
            return Optional.empty();
        }
        Matcher m = NEW_TYPE.matcher(init);
        if (!m.find()) {
            return Optional.empty();
        }
        EntityId id = EntityId.of(EntityType.CLASS, m.group(1));
        return index.find(id).filter(c -> !"true".equals(ModelIndex.attr(c, "external"))).map(Entity::id);
    }

    // ---------------------------------------------------------------- one scenario

    private void write(Handler h) {
        Entity cls = index.entity(h.classId());
        EntityId id = EntityId.of(EntityType.SCENARIO, ModelIndex.key(h.classId()));

        Set<EntityId> methods = reachableMethods(cls);
        List<Entity> rules = model.entitiesOfType(EntityType.BUSINESS_RULE).stream()
                .filter(r -> methods.contains(new EntityId(ModelIndex.attr(r, "method")))).toList();
        Set<EntityId> statements = new LinkedHashSet<>();
        for (EntityId m : methods) {
            index.out(m, RelationType.EXECUTES).forEach(r -> statements.add(r.to()));
        }
        Set<String> tables = new LinkedHashSet<>();
        for (EntityId s : statements) {
            for (RelationType t : List.of(RelationType.READS_TABLE, RelationType.INSERTS_INTO,
                    RelationType.UPDATES_TABLE, RelationType.DELETES_FROM)) {
                index.out(s, t).forEach(r -> tables.add(index.entity(r.to()).name() + " (" + t.name() + ")"));
            }
        }
        List<Entity> traces = model.entitiesOfType(EntityType.TRANSFORMATION).stream()
                .filter(t -> cls.name().equals(ModelIndex.attr(t, "scenario"))).toList();
        Map<EntityId, List<Entity>> fields = new LinkedHashMap<>();
        Set<String> columns = new LinkedHashSet<>();
        for (Entity t : traces) {
            index.in(t.id(), RelationType.DERIVED_FROM).stream().filter(r -> r.from().type() == EntityType.OUTPUT_FIELD)
                    .forEach(r -> fields.computeIfAbsent(r.from(), k -> new ArrayList<>()).add(t));
            if ("column".equals(ModelIndex.attr(t, "terminalKind"))) {
                columns.add(ModelIndex.attr(t, "terminalId", "").replaceFirst("^column:", ""));
            }
        }
        Set<String> files = fields.keySet().stream().map(f -> ModelIndex.attr(index.entity(f), "file", ""))
                .filter(s -> !s.isBlank()).collect(Collectors.toCollection(LinkedHashSet::new));

        Map<String, String> a = new LinkedHashMap<>();
        a.put("handlerClass", h.classId().value());
        a.put("trigger", h.trigger());
        Matcher label = QUOTED.matcher(h.trigger());
        if (label.find()) {
            a.put("label", label.group(1));
        }
        if (h.dispatcher() != null) {
            a.put("dispatcher", h.dispatcher().value());
        }
        a.put("dispatchNote", h.note());
        a.put("methods", methods.stream().map(ModelIndex::shortName).sorted().collect(Collectors.joining(", ")));
        a.put("methodCount", String.valueOf(methods.size()));
        if (!rules.isEmpty()) {
            a.put("rules", rules.stream().map(r -> r.id().value()).collect(Collectors.joining(", ")));
        }
        if (!statements.isEmpty()) {
            a.put("sqlStatements", statements.stream().map(EntityId::value).collect(Collectors.joining(", ")));
        }
        if (!tables.isEmpty()) {
            a.put("tables", String.join(", ", tables));
        }
        if (!columns.isEmpty()) {
            a.put("columnsRead", String.join(", ", columns));
        }
        a.put("outputFields", fields.keySet().stream().map(f -> index.entity(f).name()).collect(Collectors.joining(", ")));
        if (!files.isEmpty()) {
            a.put("outputFiles", String.join(", ", files));
        }
        Set<EntityId> scope = new LinkedHashSet<>(methods);
        String entries = entryPoints(h, scope, a);
        if (!entries.isEmpty()) {
            a.put("entryPoints", entries);
        }
        a.put("status", h.status().name());

        List<Evidence> evidence = new ArrayList<>();
        if (h.dispatchRule() != null) {
            h.dispatchRule().evidence().stream().findFirst().ifPresent(ev -> evidence.add(
                    new Evidence(ev.location(), NAME, Certainty.EXTRACTED, "trigger: " + h.trigger())));
        }
        cls.evidence().stream().findFirst().ifPresent(ev -> evidence.add(
                new Evidence(ev.location(), NAME, Certainty.EXTRACTED, "handler class")));
        model.putEntity(new Entity(id, cls.name(), a, evidence));

        for (EntityId m : methods) {
            part(m, id, TraceStatus.VERIFIED, evidence.get(evidence.size() - 1));
        }
        for (Entity r : rules) {
            part(r.id(), id, TraceStatus.VERIFIED, evidence.get(0));
        }
        if (h.dispatchRule() != null) {
            part(h.dispatchRule().id(), id, h.status(), evidence.get(0));
        }
        statements.forEach(s -> part(s, id, TraceStatus.VERIFIED, evidence.get(0)));
        for (Entity t : traces) {
            part(t.id(), id, TraceStatus.valueOf(ModelIndex.attr(t, "status", "PARTIAL")), evidence.get(0));
        }
        fields.forEach((f, ts) -> part(f, id, TraceStatus.ofChain(ts.stream()
                .map(t -> TraceStatus.valueOf(ModelIndex.attr(t, "status", "PARTIAL"))).toList()), evidence.get(0)));
    }

    private void part(EntityId from, EntityId scenario, TraceStatus status, Evidence ev) {
        model.addRelationship(new Relationship(from, RelationType.PART_OF_SCENARIO, scenario, status, List.of(ev)));
    }

    /** The handler's own methods plus everything they call that is inside the analysed sources. */
    private Set<EntityId> reachableMethods(Entity cls) {
        Deque<EntityId> queue = new ArrayDeque<>();
        index.out(cls.id(), RelationType.CONTAINS).stream().map(Relationship::to)
                .filter(id -> id.type() == EntityType.METHOD).forEach(queue::add);
        Set<EntityId> seen = new LinkedHashSet<>();
        while (!queue.isEmpty()) {
            EntityId m = queue.poll();
            if (!seen.add(m)) {
                continue;
            }
            for (Relationship call : index.out(m, RelationType.CALLS)) {
                Optional<Entity> target = index.find(call.to());
                if (target.isPresent() && !"true".equals(ModelIndex.attr(target.get(), "external"))) {
                    queue.add(call.to());
                }
            }
        }
        return seen;
    }

    /** Entry points (main methods, listeners, ...) from which the dispatcher, or else the handler, can be reached. */
    private String entryPoints(Handler h, Set<EntityId> handlerMethods, Map<String, String> attrs) {
        Set<EntityId> targets = h.dispatcher() != null ? Set.of(h.dispatcher()) : handlerMethods;
        List<String> found = new ArrayList<>();
        for (Entity m : model.entitiesOfType(EntityType.METHOD)) {
            if (ModelIndex.attr(m, "entryPoint") == null) {
                continue;
            }
            TraceStatus best = reach(m.id(), targets);
            if (best != null) {
                found.add(ModelIndex.shortName(m.id()) + " [" + ModelIndex.attr(m, "entryPoint") + ", " + best + "]");
            }
        }
        return String.join(", ", found);
    }

    /** VERIFIED if some path uses only VERIFIED call edges, PARTIAL if only through PARTIAL ones, null if unreachable. */
    private TraceStatus reach(EntityId from, Set<EntityId> targets) {
        boolean verified = reachOver(from, targets, true);
        if (verified) {
            return TraceStatus.VERIFIED;
        }
        return reachOver(from, targets, false) ? TraceStatus.PARTIAL : null;
    }

    private boolean reachOver(EntityId from, Set<EntityId> targets, boolean verifiedOnly) {
        Deque<EntityId> queue = new ArrayDeque<>(List.of(from));
        Set<EntityId> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            EntityId cur = queue.poll();
            if (!seen.add(cur)) {
                continue;
            }
            if (targets.contains(cur)) {
                return true;
            }
            for (Relationship call : index.out(cur, RelationType.CALLS)) {
                if (!verifiedOnly || call.status() == TraceStatus.VERIFIED) {
                    queue.add(call.to());
                }
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- the same field derived differently

    /** Marks output fields whose derivation (terminal and rules) differs between scenarios, with a per-scenario summary. */
    private void markDivergence() {
        ModelIndex fresh = new ModelIndex(model);
        for (Entity field : model.entitiesOfType(EntityType.OUTPUT_FIELD)) {
            Map<String, Set<String>> bySc = new TreeMap<>();
            for (Relationship r : fresh.out(field.id(), RelationType.DERIVED_FROM)) {
                if (r.to().type() != EntityType.TRANSFORMATION) {
                    continue;
                }
                Entity t = fresh.entity(r.to());
                bySc.computeIfAbsent(ModelIndex.attr(t, "scenario", "(none)"), k -> new TreeSet<>())
                        .add(ModelIndex.attr(t, "terminalKind", "") + " " + ModelIndex.attr(t, "terminal", "")
                                + " via " + ModelIndex.attr(t, "rules", "no rule"));
            }
            if (bySc.size() < 2) {
                continue;
            }
            boolean differs = new HashSet<>(bySc.values()).size() > 1;
            Map<String, String> a = new LinkedHashMap<>();
            a.put("derivationDiffersByScenario", String.valueOf(differs));
            a.put("scenarioDerivations", bySc.entrySet().stream().map(e -> e.getKey() + ": " + String.join(" | ", e.getValue()))
                    .collect(Collectors.joining(" ;; ")));
            model.putEntity(new Entity(field.id(), field.name(), a, List.of()));
        }
    }
}
