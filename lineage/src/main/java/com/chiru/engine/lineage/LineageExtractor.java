package com.chiru.engine.lineage;

import com.chiru.engine.model.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Finds fixed-width output layouts and traces every field backwards over the DERIVED_FROM graph.
 *
 * <p><b>Output discovery.</b> A layout is a method whose returned string is a concatenation of parts, at least two
 * of them carrying a width, called by a method that also calls a file sink ({@code Files.write}, {@code *Writer.write},
 * {@code print*}). Parts become OUTPUT_FIELDs in order, named after the field their getter returns. Start and end
 * columns are the running sum of the widths, so they are only as good as the width heuristic (reported in the
 * model).
 *
 * <p><b>Tracing.</b> A depth-first walk from each part over {@code DERIVED_FROM}, with a cycle guard and the caps in
 * {@link LineageOptions}. Operands that only describe the object a call is made on, or that are covered by the
 * argument bindings of an analysed call, are not followed twice: the value goes through the callee instead. A path
 * ends at a terminal: a table column (VERIFIED), a constant (VERIFIED), an external call (PARTIAL), or a runtime
 * input or untraceable value (UNRESOLVED). Library calls on the configured pure list are transparent.
 */
final class LineageExtractor {

    private static final String NAME = LineageStage.NAME;
    private static final String COMMON = "(common)";

    private final AnalysisModel model;
    private final LineageOptions options;
    private final Map<EntityId, List<Relationship>> outgoing = new HashMap<>();
    private final Map<EntityId, List<Relationship>> incoming = new HashMap<>();
    private final Map<EntityId, Kind> kinds = new HashMap<>();
    private boolean pathCapReached;
    private boolean depthCapReached;
    private int cycles;

    LineageExtractor(AnalysisModel model, LineageOptions options) {
        this.model = model;
        this.options = options;
        for (Relationship r : model.relationships()) {
            outgoing.computeIfAbsent(r.from(), k -> new ArrayList<>()).add(r);
            incoming.computeIfAbsent(r.to(), k -> new ArrayList<>()).add(r);
        }
    }

    // ---------------------------------------------------------------- records

    private record Layout(EntityId writer, EntityId layoutMethod, Entity returnExpression, List<Entity> parts,
                          EntityId sinkInvocation, String sink) {}

    private record Terminal(String kind, EntityId entity, String text, TraceStatus status) {}

    private record Step(EntityId node, TraceStatus via) {}

    private record Found(List<Step> steps, Terminal terminal) {}

    private record Next(EntityId node, TraceStatus status) {}

    /** {@code stack}: the call context for the children (null keeps the current one). */
    private record Expansion(Terminal terminal, List<Next> next, boolean sqlCovered, List<EntityId> stack) {
        static Expansion end(Terminal t) { return new Expansion(t, List.of(), false, null); }
        static Expansion dropped() { return new Expansion(null, List.of(), false, null); }
    }

    private enum Kind { ANALYSED, CONSTRUCTION, PURE, RUNTIME, DB_READ, EXTERNAL }

    // ---------------------------------------------------------------- entry

    void extract() {
        List<Layout> layouts = discover();
        if (layouts.isEmpty()) {
            warn(AnalysisWarning.Severity.INFO, "no fixed-width output layout with a file writer was found; "
                    + "no output field was traced", null);
            return;
        }
        int fields = 0;
        for (Layout layout : layouts) {
            fields += writeLayout(layout);
        }
        if (pathCapReached) {
            warn(AnalysisWarning.Severity.WARNING, "path cap of " + options.maxPathsPerField()
                    + " reached for at least one field: its trace is incomplete", null);
        }
        if (depthCapReached) {
            warn(AnalysisWarning.Severity.WARNING, "depth cap of " + options.maxDepth()
                    + " reached for at least one path: that path was cut", null);
        }
        warn(AnalysisWarning.Severity.INFO, layouts.size() + " output file(s), " + fields + " field(s) traced; "
                + cycles + " cyclic path(s) cut. Library calls outside the pure list stay PARTIAL; values from "
                + "System/Scanner/Reader input are UNRESOLVED; instances are not tracked (field-based)", null);
    }

    // ---------------------------------------------------------------- output discovery

    private List<Layout> discover() {
        List<Layout> layouts = new ArrayList<>();
        for (Entity expr : model.entitiesOfType(EntityType.EXPRESSION)) {
            if (!"return".equals(attr(expr, "kind"))) {
                continue;
            }
            List<Entity> parts = out(expr.id(), RelationType.CONTAINS).stream().map(r -> ent(r.to()))
                    .filter(e -> "part".equals(attr(e, "kind")))
                    .sorted(Comparator.comparingInt(e -> Integer.parseInt(attr(e, "index")))).toList();
            long widths = parts.stream().filter(p -> p.attributes().containsKey("width")).count();
            if (parts.size() < 2 || widths < 2) {
                continue;
            }
            EntityId layoutMethod = ownerMethod(expr.id());
            Layout found = null;
            for (Relationship call : in(layoutMethod, RelationType.CALLS)) {
                if (call.from().type() != EntityType.METHOD) {
                    continue;
                }
                Layout candidate = withSink(call.from(), layoutMethod, expr, parts);
                if (candidate != null) {
                    found = candidate;
                    break;
                }
            }
            if (found == null) {
                warn(AnalysisWarning.Severity.INFO, "method " + layoutMethod + " builds a fixed-width line but no "
                        + "caller that writes to a file was found; its fields were not traced", firstLocation(expr));
            } else {
                layouts.add(found);
            }
        }
        return layouts;
    }

    private Layout withSink(EntityId writer, EntityId layoutMethod, Entity expr, List<Entity> parts) {
        for (Relationship contains : out(writer, RelationType.CONTAINS)) {
            if (contains.to().type() != EntityType.INVOCATION) {
                continue;
            }
            for (Relationship call : out(contains.to(), RelationType.INVOKES)) {
                Entity target = ent(call.to());
                String owner = attr(target, "owner");
                if ("true".equals(attr(target, "external")) && owner != null && isSink(owner, target.name())) {
                    return new Layout(writer, layoutMethod, expr, parts, contains.to(), owner + "#" + target.name());
                }
            }
        }
        return null;
    }

    private static boolean isSink(String owner, String name) {
        if (owner.equals("java.nio.file.Files")) {
            return name.startsWith("write");
        }
        boolean stream = owner.endsWith("Writer") || owner.endsWith("PrintStream") || owner.endsWith("OutputStream");
        return stream && Set.of("write", "append", "print", "println", "newLine").contains(name);
    }

    // ---------------------------------------------------------------- one layout

    private int writeLayout(Layout layout) {
        String fileKey = key(layout.writer());
        EntityId fileId = EntityId.of(EntityType.OUTPUT_FILE, fileKey);
        Map<String, String> fileAttrs = new LinkedHashMap<>();
        fileAttrs.put("writerMethod", layout.writer().value());
        fileAttrs.put("layoutMethod", layout.layoutMethod().value());
        fileAttrs.put("sink", layout.sink());
        fileAttrs.put("fieldCount", String.valueOf(layout.parts().size()));
        fileAttrs.put("fixedWidth", "true");
        fileAttrs.put("note", "the output path is not traced; the layout is read from the concatenation in "
                + layout.layoutMethod());
        List<Evidence> fileEvidence = new ArrayList<>();
        evidenceOf(ent(layout.sinkInvocation()), "write call").ifPresent(fileEvidence::add);
        evidenceOf(layout.returnExpression(), "line layout").ifPresent(fileEvidence::add);
        model.putEntity(new Entity(fileId, ModelIndex.shortName(layout.writer()), fileAttrs, fileEvidence));
        model.addRelationship(new Relationship(layout.writer(), RelationType.PRODUCES, fileId, TraceStatus.VERIFIED,
                List.of(fileEvidence.get(0))));

        Set<String> usedNames = new HashSet<>();
        Integer position = 1;             // 1-based column; null once a width is unknown
        int traced = 0;
        for (Entity part : layout.parts()) {
            int index = Integer.parseInt(attr(part, "index"));
            String name = fieldName(part, index);
            if (!usedNames.add(name)) {
                name = name + "#" + index;
            }
            Integer width = part.attributes().containsKey("width") ? Integer.valueOf(attr(part, "width")) : null;
            EntityId fieldId = EntityId.of(EntityType.OUTPUT_FIELD, fileKey + "#" + name);

            Map<String, String> a = new LinkedHashMap<>();
            a.put("index", String.valueOf(index));
            a.put("file", fileId.value());
            a.put("layoutPart", part.id().value());
            a.put("text", attr(part, "text"));
            if (width != null) {
                a.put("width", String.valueOf(width));
                a.put("widthSource", attr(part, "widthSource"));
            }
            if (position != null && width != null) {
                a.put("start", String.valueOf(position));
                a.put("end", String.valueOf(position + width - 1));
                position += width;
            } else {
                position = null;
                a.put("positionNote", "position unknown: a width before or at this field could not be read");
            }
            Evidence ev = evidenceOf(part, "output field " + index).orElseThrow();
            model.putEntity(new Entity(fieldId, name, a, List.of(ev)));
            model.addRelationship(new Relationship(fileId, RelationType.CONTAINS, fieldId, TraceStatus.VERIFIED, List.of(ev)));
            model.addRelationship(new Relationship(fieldId, RelationType.DERIVED_FROM, part.id(), TraceStatus.VERIFIED, List.of(ev)));
            traceField(fieldId, name, part, layout);
            traced++;
        }
        return traced;
    }

    /** The field a part's getter returns, when exactly one analysed getter feeds the part; otherwise {@code fieldN}. */
    private String fieldName(Entity part, int index) {
        Set<String> names = new LinkedHashSet<>();
        for (Relationship d : out(part.id(), RelationType.DERIVED_FROM)) {
            if (d.to().type() != EntityType.INVOCATION) {
                continue;
            }
            for (Relationship call : out(d.to(), RelationType.INVOKES)) {
                if ("true".equals(attr(ent(call.to()), "external"))) {
                    continue;
                }
                List<Relationship> returns = out(call.to(), RelationType.CONTAINS).stream()
                        .filter(r -> r.to().type() == EntityType.EXPRESSION
                                && "return".equals(attr(ent(r.to()), "kind"))).toList();
                if (returns.size() == 1) {
                    List<Relationship> operands = out(returns.get(0).to(), RelationType.DERIVED_FROM);
                    if (operands.size() == 1 && operands.get(0).to().type() == EntityType.FIELD) {
                        names.add(ent(operands.get(0).to()).name());
                    }
                }
            }
        }
        return names.size() == 1 ? names.iterator().next() : "field" + index;
    }

    // ---------------------------------------------------------------- tracing

    private void traceField(EntityId fieldId, String name, Entity part, Layout layout) {
        List<Found> found = new ArrayList<>();
        int[] layoutConstants = {0};
        walk(part.id(), TraceStatus.VERIFIED, new ArrayDeque<>(), new HashSet<>(), false, List.of(), layout, found,
                layoutConstants);
        found.sort(Comparator.comparingInt(f -> f.steps().size()));      // stable: the shortest path represents a group

        record Group(Found found, String variant, List<String> rules, TraceStatus status, int count) {}
        Map<String, Group> groups = new LinkedHashMap<>();
        for (Found f : found) {
            String variant = variantOf(f);
            List<String> rules = rulesOn(f);
            TraceStatus status = statusOf(f);
            String terminal = f.terminal().entity() != null ? f.terminal().entity().value() : f.terminal().text();
            String key = variant + "|" + f.terminal().kind() + "|" + terminal + "|" + rules + "|" + status;
            groups.merge(key, new Group(f, variant, rules, status, 1),
                    (old, x) -> new Group(old.found(), old.variant(), old.rules(), old.status(), old.count() + 1));
        }

        Set<String> variantsWithData = groups.values().stream().filter(g -> !g.found().terminal().kind().equals("constant"))
                .map(Group::variant).collect(Collectors.toCollection(LinkedHashSet::new));
        boolean anyData = !variantsWithData.isEmpty();
        List<Group> kept = new ArrayList<>();
        Set<String> constants = new LinkedHashSet<>();
        for (Group g : groups.values()) {
            if (g.found().terminal().kind().equals("constant")) {
                if (!(g.variant().equals(COMMON) && anyData)) {          // shared helper internals are not inputs
                    constants.add(g.variant() + ": " + g.found().terminal().text());
                }
                boolean redundant = variantsWithData.contains(g.variant()) || (g.variant().equals(COMMON) && anyData);
                if (redundant) {
                    continue;
                }
            }
            kept.add(g);
        }
        kept.sort(Comparator.comparing(Group::variant));

        List<TraceStatus> statuses = new ArrayList<>();
        Set<String> variants = new LinkedHashSet<>();
        Set<String> columns = new LinkedHashSet<>();
        List<String> gaps = new ArrayList<>();
        int n = 0;
        for (Group g : kept) {
            n++;
            Terminal t = g.found().terminal();
            statuses.add(g.status());
            variants.add(g.variant());
            if (t.kind().equals("column")) {
                columns.add(t.text());
            }
            if (g.status() != TraceStatus.VERIFIED) {
                gaps.add(g.variant() + ": " + t.kind() + " " + t.text() + " [" + g.status() + "]");
            }
            writeTransformation(fieldId, n, g.found(), g.variant(), g.rules(), g.status(), g.count());
        }

        TraceStatus overall = TraceStatus.ofChain(statuses);
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put("traceStatus", overall.name());
        extra.put("pathCount", String.valueOf(found.size()));
        extra.put("traceCount", String.valueOf(kept.size()));
        extra.put("scenarios", String.join(", ", variants));
        if (!columns.isEmpty()) {
            extra.put("columns", String.join(", ", columns));
        }
        if (!constants.isEmpty()) {
            extra.put("constants", String.join(" ; ", constants));
        }
        if (!gaps.isEmpty()) {
            extra.put("gaps", String.join(" ; ", gaps));
        }
        if (layoutConstants[0] > 0) {
            extra.put("layoutConstantsSkipped", String.valueOf(layoutConstants[0]));
        }
        model.putEntity(new Entity(fieldId, name, extra, List.of()));
    }

    private void walk(EntityId node, TraceStatus via, Deque<Step> path, Set<EntityId> onPath, boolean sqlCovered,
                      List<EntityId> stack, Layout layout, List<Found> out, int[] layoutConstants) {
        if (out.size() >= options.maxPathsPerField()) {
            pathCapReached = true;
            return;
        }
        if (path.size() >= options.maxDepth()) {
            depthCapReached = true;
            return;
        }
        if (!onPath.add(node)) {
            cycles++;
            return;
        }
        Step reader = path.peekLast();                          // the node that reads this one, when this one is a variable
        path.addLast(new Step(node, via));
        Expansion x = expand(node, sqlCovered, stack, reader == null ? null : reader.node());
        List<EntityId> childStack = x.stack() == null ? stack : x.stack();
        if (x.terminal() != null) {
            Terminal t = x.terminal();
            boolean layoutConstant = t.kind().equals("constant") && t.entity() != null
                    && layout.layoutMethod().equals(ownerMethodOrNull(t.entity()));
            if (layoutConstant) {
                layoutConstants[0]++;           // widths and pad characters describe the layout, not the data
            } else {
                out.add(new Found(List.copyOf(path), t));
            }
        }
        for (Next next : x.next()) {
            walk(next.node(), next.status(), path, onPath, sqlCovered || x.sqlCovered(), childStack, layout, out,
                    layoutConstants);
        }
        path.removeLast();
        onPath.remove(node);
    }

    private Expansion expand(EntityId node, boolean sqlCovered, List<EntityId> stack, EntityId reader) {
        Entity e = ent(node);
        switch (node.type()) {
            case EXPRESSION:
                return expandExpression(e, sqlCovered);
            case VARIABLE: {
                List<Relationship> outs = out(node, RelationType.DERIVED_FROM);
                outs = reachingDefinitions(outs, reader);
                // Having entered a callee through a call, a parameter takes the argument of that same call, not of
                // every call site; the call is then left. Without a matching call the value may come from any caller.
                EntityId method = ownerMethodOrNull(node);
                EntityId top = stack.isEmpty() ? null : stack.get(stack.size() - 1);
                boolean returnsToCall = "parameter".equals(attr(e, "kind")) && top != null && method != null
                        && out(top, RelationType.INVOKES).stream().anyMatch(r -> r.to().equals(method));
                if (returnsToCall) {
                    outs = outs.stream().filter(r -> r.to().type() == EntityType.EXPRESSION
                            && (!"argument".equals(attr(ent(r.to()), "kind"))
                            || top.value().equals(attr(ent(r.to()), "invocation")))).toList();
                }
                List<Next> next = outs.stream().filter(r -> r.to().type() == EntityType.EXPRESSION
                        || r.to().type() == EntityType.PARAMETER).map(r -> new Next(r.to(), r.status())).toList();
                if (next.isEmpty()) {
                    boolean entry = attr(ent(ownerMethod(node)), "entryPoint") != null;
                    return Expansion.end(new Terminal(entry ? "entry input" : "untraced", node,
                            e.name() + (entry ? " (entry-point parameter)" : " has no definition in the analysed sources"),
                            TraceStatus.UNRESOLVED));
                }
                boolean sql = outs.stream().anyMatch(r -> r.to().type() == EntityType.PARAMETER);
                return new Expansion(null, next, sql,
                        returnsToCall ? List.copyOf(stack.subList(0, stack.size() - 1)) : null);
            }
            case FIELD: {
                List<Next> next = out(node, RelationType.DERIVED_FROM).stream()
                        .filter(r -> r.to().type() == EntityType.EXPRESSION).map(r -> new Next(r.to(), r.status())).toList();
                if (!next.isEmpty()) {
                    return new Expansion(null, next, false, List.of());      // instance state: the call context is lost
                }
                String mods = attr(e, "modifiers") == null ? "" : attr(e, "modifiers");
                String init = attr(e, "initializer");
                if (mods.contains("static") && mods.contains("final") && init != null) {
                    return Expansion.end(new Terminal("constant", node, e.name() + " = " + init, TraceStatus.VERIFIED));
                }
                if (init != null) {
                    return Expansion.end(new Terminal("field initialiser", node, e.name() + " = " + init,
                            TraceStatus.PARTIAL));
                }
                return Expansion.end(new Terminal("untraced", node, "field " + e.name()
                        + " is never assigned in the analysed sources", TraceStatus.UNRESOLVED));
            }
            case INVOCATION:
                return expandInvocation(e, sqlCovered, stack);
            case PARAMETER: {
                List<Next> next = out(node, RelationType.DERIVED_FROM).stream()
                        .filter(r -> r.to().type() == EntityType.COLUMN).map(r -> new Next(r.to(), r.status())).toList();
                return next.isEmpty() ? Expansion.end(new Terminal("untraced", node,
                        "SQL result '" + attr(e, "label") + "' has no source column", TraceStatus.UNRESOLVED))
                        : new Expansion(null, next, false, null);
            }
            case COLUMN:
                return Expansion.end(new Terminal("column", node, ModelIndex.key(node), TraceStatus.VERIFIED));      // table.column
            default:
                return Expansion.end(new Terminal("untraced", node, e.name(), TraceStatus.UNRESOLVED));
        }
    }

    /**
     * Order-aware definitions: when the variable is read by an expression whose unit has a recorded order, only the definitions
     * that may have run before that expression count. A definition in another unit (a lambda, a callee), one with no recorded
     * order, or a reader with none, is kept: the filter only removes what provably cannot reach the read.
     */
    private List<Relationship> reachingDefinitions(List<Relationship> definitions, EntityId reader) {
        if (reader == null || reader.type() != EntityType.EXPRESSION) {
            return definitions;
        }
        Entity read = ent(reader);
        String unit = attr(read, "unit");
        if (unit == null) {
            return definitions;
        }
        Set<String> before = new HashSet<>(Arrays.asList(attr(read, "precededBy") == null ? new String[0] : attr(read, "precededBy").split(" ")));
        List<Relationship> kept = definitions.stream().filter(r -> {
            if (r.to().type() != EntityType.EXPRESSION) {
                return true;
            }
            Entity def = ent(r.to());
            if (!unit.equals(attr(def, "unit"))) {
                return true;
            }
            String id = key(r.to());
            return before.contains(id.substring(id.lastIndexOf('@') + 1));
        }).toList();
        return kept.stream().anyMatch(r -> r.to().type() == EntityType.EXPRESSION) ? kept : definitions;   // never end up with nothing
    }

    private Expansion expandExpression(Entity e, boolean sqlCovered) {
        List<Relationship> operands = out(e.id(), RelationType.DERIVED_FROM).stream()
                .filter(r -> r.to().type() == EntityType.VARIABLE || r.to().type() == EntityType.FIELD
                        || r.to().type() == EntityType.INVOCATION).toList();
        Set<EntityId> covered = covered(operands);
        List<Next> next = new ArrayList<>();
        boolean droppedReads = false;
        for (Relationship r : operands) {
            if (covered.contains(r.to())) {
                continue;
            }
            if (r.to().type() == EntityType.INVOCATION) {
                Kind kind = kind(r.to());
                if (kind == Kind.PURE || kind == Kind.CONSTRUCTION) {
                    continue;
                }
                if (kind == Kind.DB_READ && sqlCovered) {
                    droppedReads = true;        // the SQL binding on the parameter already gives the source column
                    continue;
                }
            }
            next.add(new Next(r.to(), r.status()));
        }
        if (next.isEmpty()) {
            return droppedReads ? Expansion.dropped()
                    : Expansion.end(new Terminal("constant", e.id(), attr(e, "text"), TraceStatus.VERIFIED));
        }
        return new Expansion(null, next, false, null);
    }

    private Expansion expandInvocation(Entity inv, boolean sqlCovered, List<EntityId> stack) {
        Kind kind = kind(inv.id());
        String text = attr(inv, "text");
        switch (kind) {
            case ANALYSED: {
                List<EntityId> inside = new ArrayList<>(stack);
                inside.add(inv.id());
                return new Expansion(null, out(inv.id(), RelationType.DERIVED_FROM).stream()
                        .map(r -> new Next(r.to(), r.status())).toList(), false, List.copyOf(inside));
            }
            case RUNTIME:
                return Expansion.end(new Terminal("runtime input", inv.id(), text, TraceStatus.UNRESOLVED));
            case DB_READ:
                return sqlCovered ? Expansion.dropped()
                        : Expansion.end(new Terminal("database read", inv.id(), text, TraceStatus.PARTIAL));
            case EXTERNAL:
                return Expansion.end(new Terminal("external call", inv.id(), text, TraceStatus.PARTIAL));
            default:
                return Expansion.dropped();     // pure library call or object construction: nothing to add
        }
    }

    /**
     * Operands already reached through an analysed call's own bindings: the object it is called on and everything
     * inside its arguments. A call result is followed through the callee, so these are not followed a second time.
     */
    private Set<EntityId> covered(List<Relationship> operands) {
        Set<EntityId> covered = new HashSet<>();
        for (Relationship r : operands) {
            if (r.to().type() != EntityType.INVOCATION) {
                continue;
            }
            Kind kind = kind(r.to());
            boolean analysed = kind == Kind.ANALYSED || kind == Kind.CONSTRUCTION && analysedCallee(r.to());
            if (!analysed && kind != Kind.DB_READ) {
                continue;
            }
            String receiver = attr(ent(r.to()), "receiver");
            if (receiver != null) {
                covered.add(new EntityId(receiver));
            }
            if (analysed) {
                for (Relationship c : out(r.to(), RelationType.CONTAINS)) {
                    if (c.to().type() == EntityType.EXPRESSION && "argument".equals(attr(ent(c.to()), "kind"))) {
                        out(c.to(), RelationType.DERIVED_FROM).forEach(d -> covered.add(d.to()));
                    }
                }
            }
        }
        return covered;
    }

    private boolean analysedCallee(EntityId inv) {
        return out(inv, RelationType.INVOKES).stream().anyMatch(r -> !"true".equals(attr(ent(r.to()), "external")));
    }

    private Kind kind(EntityId inv) {
        return kinds.computeIfAbsent(inv, id -> {
            if (!out(id, RelationType.DERIVED_FROM).isEmpty()) {
                return Kind.ANALYSED;
            }
            List<Entity> targets = out(id, RelationType.INVOKES).stream().map(r -> ent(r.to())).toList();
            List<Entity> external = targets.stream().filter(t -> "true".equals(attr(t, "external"))).toList();
            if (targets.isEmpty()) {
                return Kind.EXTERNAL;
            }
            if (external.isEmpty()) {
                return Kind.CONSTRUCTION;          // analysed callee that returns nothing (constructor, void method)
            }
            Entity t = external.get(0);
            String owner = attr(t, "owner") == null ? "" : attr(t, "owner");
            String name = t.name();
            if (isRuntimeInput(owner, name)) {
                return Kind.RUNTIME;
            }
            if (owner.startsWith("java.sql.ResultSet") && name.startsWith("get")) {
                return Kind.DB_READ;
            }
            return options.pureLibraryPrefixes().stream().anyMatch(owner::startsWith) ? Kind.PURE : Kind.EXTERNAL;
        });
    }

    private static boolean isRuntimeInput(String owner, String name) {
        return (owner.equals("java.lang.System") && Set.of("getProperty", "getenv", "getProperties").contains(name))
                || owner.equals("java.util.Scanner") || owner.equals("java.io.Console")
                || (owner.equals("java.io.BufferedReader") && Set.of("readLine", "read").contains(name));
    }

    // ---------------------------------------------------------------- results of one path

    private TraceStatus statusOf(Found f) {
        if (f.terminal().status() == TraceStatus.UNRESOLVED) {
            return TraceStatus.UNRESOLVED;
        }
        List<TraceStatus> chain = new ArrayList<>();
        f.steps().stream().skip(1).forEach(s -> chain.add(s.via()));
        chain.add(f.terminal().status());
        return TraceStatus.ofChain(chain);
    }

    /** The scenario handler (a class implementing an analysed interface) nearest the output on the path. */
    private String variantOf(Found f) {
        for (Step s : f.steps()) {
            EntityId method = s.node().type() == EntityType.COLUMN || s.node().type() == EntityType.PARAMETER
                    ? null : ownerMethodOrNull(s.node());
            if (method == null) {
                continue;
            }
            Optional<Entity> cls = in(method, RelationType.CONTAINS).stream().map(r -> ent(r.from()))
                    .filter(e -> e.type() == EntityType.CLASS).findFirst();
            if (cls.isPresent() && implementsMethodOfInterface(cls.get(), ent(method))) {
                return cls.get().name();
            }
        }
        return COMMON;
    }

    private boolean implementsMethodOfInterface(Entity cls, Entity method) {
        for (Relationship impl : out(cls.id(), RelationType.IMPLEMENTS)) {
            for (Relationship m : out(impl.to(), RelationType.CONTAINS)) {
                if (m.to().type() == EntityType.METHOD && method.name().equals(ent(m.to()).name())) {
                    return true;
                }
            }
        }
        return false;
    }

    private List<String> rulesOn(Found f) {
        return f.steps().stream().filter(s -> s.node().type() == EntityType.EXPRESSION)
                .flatMap(s -> Stream.concat(Stream.of(s.node()), enclosingStatement(s.node()).stream()))
                .flatMap(id -> in(id, RelationType.DERIVED_FROM).stream())
                .map(Relationship::from).filter(id -> id.type() == EntityType.BUSINESS_RULE)
                .map(EntityId::value).distinct().sorted().toList();
    }

    private Map<String, List<Entity>> statementsByMethod;

    /**
     * An argument or a part of a string has no rule of its own: the rule sits on the statement it belongs to (the return or the
     * assignment the call is part of). That statement is the smallest one of the same method whose lines contain it.
     */
    private Optional<EntityId> enclosingStatement(EntityId id) {
        Entity e = ent(id);
        String kind = attr(e, "kind");
        if (!"argument".equals(kind) && !"part".equals(kind) || e.evidence().isEmpty() || e.evidence().get(0).location() == null) {
            return Optional.empty();
        }
        if (statementsByMethod == null) {
            statementsByMethod = new HashMap<>();
            for (Entity s : model.entitiesOfType(EntityType.EXPRESSION)) {
                String k = attr(s, "kind");
                if (("return".equals(k) || "init".equals(k) || "assign".equals(k) || "compound".equals(k)) && !s.evidence().isEmpty()
                        && s.evidence().get(0).location() != null) {
                    String key = key(s.id());
                    statementsByMethod.computeIfAbsent(key.substring(0, key.lastIndexOf('@')), x -> new ArrayList<>()).add(s);
                }
            }
        }
        String own = key(id);
        SourceLocation at = e.evidence().get(0).location();
        return statementsByMethod.getOrDefault(own.substring(0, own.lastIndexOf('@')), List.of()).stream()
                .filter(s -> s.evidence().get(0).location().file().equals(at.file())
                        && s.evidence().get(0).location().startLine() <= at.startLine() && s.evidence().get(0).location().endLine() >= at.endLine())
                .min(Comparator.comparingInt(s -> s.evidence().get(0).location().endLine() - s.evidence().get(0).location().startLine()))
                .map(Entity::id);
    }

    private void writeTransformation(EntityId fieldId, int n, Found f, String variant, List<String> rules,
                                     TraceStatus status, int count) {
        EntityId id = EntityId.of(EntityType.TRANSFORMATION, key(fieldId) + "#" + n);
        Terminal t = f.terminal();
        Map<String, String> a = new LinkedHashMap<>();
        a.put("scenario", variant);
        a.put("status", status.name());
        a.put("terminalKind", t.kind());
        a.put("terminal", t.text());
        if (t.entity() != null) {
            a.put("terminalId", t.entity().value());
        }
        a.put("pathCount", String.valueOf(count));
        if (!rules.isEmpty()) {
            a.put("rules", String.join(", ", rules));
        }
        a.put("steps", f.steps().stream().map(s -> describe(ent(s.node()))).collect(Collectors.joining(" <- ")));
        a.put("stepIds", f.steps().stream().map(s -> s.node().value()).collect(Collectors.joining(" ;; ")));      // output first, source last
        String methods = f.steps().stream().map(s -> ownerMethodOrNull(s.node())).filter(Objects::nonNull)
                .map(EntityId::value).distinct().collect(Collectors.joining(", "));
        if (!methods.isEmpty()) {
            a.put("methods", methods);
        }
        String variables = f.steps().stream().filter(s -> s.node().type() == EntityType.VARIABLE)
                .map(s -> ent(s.node()).name()).distinct().collect(Collectors.joining(", "));
        if (!variables.isEmpty()) {
            a.put("variables", variables);
        }
        List<Evidence> evidence = new ArrayList<>();
        for (Step s : f.steps()) {
            evidenceOf(ent(s.node()), describe(ent(s.node()))).filter(ev -> !evidence.contains(ev)).ifPresent(evidence::add);
        }
        if (evidence.isEmpty()) {
            evidenceOf(ent(fieldId), "trace of the output field").ifPresent(evidence::add);
        }
        model.putEntity(new Entity(id, variant + ": " + t.kind() + " " + t.text(), a, evidence));
        model.addRelationship(new Relationship(fieldId, RelationType.DERIVED_FROM, id, status, List.of(evidence.get(0))));
        if (t.entity() != null) {
            model.addRelationship(new Relationship(id, RelationType.DERIVED_FROM, t.entity(), t.status(),
                    List.of(evidence.get(evidence.size() - 1))));
        }
        for (String rule : rules) {
            model.addRelationship(new Relationship(id, RelationType.APPLIES_RULE, new EntityId(rule),
                    TraceStatus.VERIFIED, List.of(evidence.get(0))));
        }
    }

    private String describe(Entity e) {
        String where = e.evidence().isEmpty() || e.evidence().get(0).location() == null ? ""
                : " @" + e.evidence().get(0).location().file() + ":" + e.evidence().get(0).location().startLine();
        return switch (e.type()) {
            case EXPRESSION -> attr(e, "kind") + " " + attr(e, "text") + where;
            case VARIABLE -> attr(e, "kind") + " variable " + e.name() + where;
            case FIELD -> "field " + e.name() + where;
            case INVOCATION -> "call " + attr(e, "text") + where;
            case PARAMETER -> "sql result " + attr(e, "label") + where;
            case COLUMN -> "column " + e.name();
            default -> e.name();
        };
    }

    // ---------------------------------------------------------------- helpers

    private EntityId ownerMethod(EntityId id) {
        EntityId owner = ownerMethodOrNull(id);
        if (owner == null) {
            throw new IllegalStateException("no owning method for " + id);
        }
        return owner;
    }

    /** The method that (transitively) contains {@code id}: arguments and parts sit inside expressions and calls. */
    private EntityId ownerMethodOrNull(EntityId id) {
        Deque<EntityId> queue = new ArrayDeque<>(List.of(id));
        Set<EntityId> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            EntityId cur = queue.poll();
            if (!seen.add(cur)) {
                continue;
            }
            if (cur.type() == EntityType.METHOD) {
                return cur;
            }
            in(cur, RelationType.CONTAINS).stream().map(Relationship::from)
                    .filter(f -> f.type() == EntityType.METHOD || f.type() == EntityType.EXPRESSION
                            || f.type() == EntityType.INVOCATION).forEach(queue::add);
        }
        return null;
    }

    private List<Relationship> out(EntityId id, RelationType type) {
        return outgoing.getOrDefault(id, List.of()).stream().filter(r -> r.type() == type).toList();
    }

    private List<Relationship> in(EntityId id, RelationType type) {
        return incoming.getOrDefault(id, List.of()).stream().filter(r -> r.type() == type).toList();
    }

    private Entity ent(EntityId id) {
        return model.entity(id).orElseThrow(() -> new IllegalStateException("unknown entity " + id));
    }

    private static String attr(Entity e, String key) {
        return e.attributes().get(key);
    }

    private static String key(EntityId id) {
        return id.value().substring(id.value().indexOf(':') + 1);
    }

    private Optional<Evidence> evidenceOf(Entity e, String note) {
        return e.evidence().stream().findFirst()
                .map(ev -> new Evidence(ev.location(), NAME, Certainty.EXTRACTED, note));
    }

    private SourceLocation firstLocation(Entity e) {
        return e.evidence().isEmpty() ? null : e.evidence().get(0).location();
    }

    private void warn(AnalysisWarning.Severity severity, String message, SourceLocation location) {
        model.addWarning(new AnalysisWarning(NAME, severity, message, location));
    }
}
