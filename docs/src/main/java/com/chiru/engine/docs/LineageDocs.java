package com.chiru.engine.docs;

import com.chiru.engine.lineage.LineageQuery;
import com.chiru.engine.model.*;
import java.util.*;
import java.util.stream.Collectors;

/** 07 field mapping, 08 source-to-output traceability, 09 output file specification, 11 business scenarios. */
final class LineageDocs {

    private LineageDocs() {}

    // ---------------------------------------------------------------- 07

    static String fieldMapping(DocContext c) {
        StringBuilder sb = new StringBuilder(c.header("07 Field mapping", "For every output field: where it comes from and how."));
        sb.append("> One row per derivation path. When a field is derived differently in different scenarios, each scenario has its own "
                + "row(s). *Output format* gives only what the layout shows (width and position); type, precision and padding are "
                + "not derived and are marked so.\n\n");
        List<Entity> fields = c.fields();
        if (fields.isEmpty()) {
            return sb.append("_No output field was identified._\n").toString();
        }
        for (Entity f : fields) {
            sb.append(Md.h(2, f.name()));
            sb.append(Md.bullet("Output file", c.nameOf(c.attr(f, "file"))));
            sb.append(Md.bullet("Position", position(c, f)));
            sb.append(Md.bullet("Output format", "width " + Optional.ofNullable(c.attr(f, "width")).orElse("?")
                    + " (" + Optional.ofNullable(c.attr(f, "widthSource")).orElse("unknown source") + "); type, precision and padding: not derived"));
            sb.append(Md.bullet("Layout expression", Md.code(c.attr(f, "text")) + " at " + c.loc(f)));
            sb.append(Md.bullet("Trace status", Optional.ofNullable(c.attr(f, "traceStatus")).orElse("UNRESOLVED")));
            sb.append(Md.bullet("Derivation differs by scenario", c.attr(f, "derivationDiffersByScenario")));
            sb.append('\n');

            List<List<String>> rows = new ArrayList<>();
            for (Map.Entry<Entity, TraceStatus> tr : sortedTraces(c, f)) {
                Entity t = tr.getKey();
                rows.add(List.of(
                        Optional.ofNullable(c.attr(t, "scenario")).orElse(Md.NONE),
                        source(c, t),
                        sqlQuery(c, t),
                        methods(c, t),
                        Optional.ofNullable(c.attr(t, "variables")).orElse(Md.NONE),
                        c.ruleLabels(c.attr(t, "rules")),
                        conditions(c, t),
                        derivation(c, t),
                        tr.getValue().name(),
                        evidence(c, t)));
            }
            sb.append(Md.table(List.of("Scenario", "Source table.column", "SQL query", "Java methods", "Input variables", "Rules",
                    "Condition", "Derivation", "Status", "Evidence"), rows));
        }
        return sb.toString();
    }

    private static String position(DocContext c, Entity f) {
        return c.attr(f, "start") == null ? "unknown (" + c.attr(f, "positionNote") + ")"
                : c.attr(f, "start") + "-" + c.attr(f, "end") + " (width " + c.attr(f, "width") + ")";
    }

    private static List<Map.Entry<Entity, TraceStatus>> sortedTraces(DocContext c, Entity f) {
        List<Map.Entry<Entity, TraceStatus>> traces = new ArrayList<>(c.traces(f));
        traces.sort(Comparator.comparing((Map.Entry<Entity, TraceStatus> e) -> Optional.ofNullable(c.attr(e.getKey(), "scenario")).orElse(""))
                .thenComparing(e -> e.getKey().id().value()));
        return traces;
    }

    private static String source(DocContext c, Entity t) {
        String kind = c.attr(t, "terminalKind");
        if ("column".equals(kind)) {
            return c.attr(t, "terminal");
        }
        return "(" + kind + ") " + c.attr(t, "terminal");
    }

    private static String sqlQuery(DocContext c, Entity t) {
        String terminal = c.attr(t, "terminalId");
        if (terminal == null || !"column".equals(c.attr(t, "terminalKind"))) {
            return Md.NONE;
        }
        EntityId column = new EntityId(terminal);
        Set<String> queries = new TreeSet<>();
        for (Relationship r : c.index.in(column, RelationType.DERIVED_FROM)) {
            if (r.from().type() != EntityType.PARAMETER) {
                continue;
            }
            for (Relationship owner : c.index.in(r.from(), RelationType.CONTAINS)) {
                if (owner.from().type() == EntityType.SQL_STATEMENT) {
                    Entity s = c.index.entity(owner.from());
                    queries.add(s.name() + " @" + c.loc(s));
                }
            }
        }
        return queries.isEmpty() ? Md.NONE : String.join("; ", queries);
    }

    private static String methods(DocContext c, Entity t) {
        String methods = c.attr(t, "methods");
        return methods == null ? Md.NONE : Arrays.stream(methods.split(", ")).map(c::method).distinct().collect(Collectors.joining(", "));
    }

    private static String conditions(DocContext c, Entity t) {
        String rules = c.attr(t, "rules");
        if (rules == null) {
            return Md.NONE;
        }
        return Arrays.stream(rules.split(", ")).map(id -> c.model.entity(new EntityId(id)).map(r -> c.attr(r, "conditions")).orElse(null))
                .filter(Objects::nonNull).distinct().collect(Collectors.joining(" ; "));
    }

    private static String derivation(DocContext c, Entity t) {
        String rules = c.attr(t, "rules");
        List<String> effects = new ArrayList<>();
        if (rules != null) {
            for (String id : rules.split(", ")) {
                c.model.entity(new EntityId(id)).map(r -> c.attr(r, "effects")).ifPresent(effects::add);
            }
        }
        String kind = c.attr(t, "terminalKind");
        String end = switch (kind == null ? "" : kind) {
            case "column" -> "read from column " + c.attr(t, "terminal");
            case "constant" -> "constant " + c.attr(t, "terminal");
            case "runtime input" -> "UNRESOLVED: value comes from " + c.attr(t, "terminal");
            default -> kind + " " + c.attr(t, "terminal");
        };
        return effects.isEmpty() ? end : String.join(" ; ", new LinkedHashSet<>(effects)) + " <- " + end;
    }

    private static String evidence(DocContext c, Entity t) {
        List<String> locs = t.evidence().stream().map(Evidence::location).filter(Objects::nonNull).map(SourceLocation::toString)
                .distinct().toList();
        if (locs.size() <= 3) {
            return String.join(", ", locs);
        }
        return locs.get(0) + ", " + locs.get(1) + " … " + locs.get(locs.size() - 1) + " (" + locs.size() + " locations, see doc 08)";
    }

    // ---------------------------------------------------------------- 08

    static String traceability(DocContext c) {
        StringBuilder sb = new StringBuilder(c.header("08 Source-to-output traceability",
                "Every output field traced backwards, step by step; and every source traced forwards."));
        sb.append("> Each trace runs from the output field back to where the value comes from: a database column (VERIFIED), a constant "
                + "(VERIFIED), a library call outside the analysed code (PARTIAL), or a runtime input / untraceable value (UNRESOLVED). "
                + "Calls on the configured pure library list (`java.math`, `String`, `Math`, `java.time`, boxed types) are treated as "
                + "transformations. The analysis is field-based: all instances of a class share their field's sources.\n\n");
        List<Entity> fields = c.fields();
        if (fields.isEmpty()) {
            sb.append("_No output field was identified._\n\n");
        }
        for (Entity f : fields) {
            sb.append(Md.h(2, f.name() + " — " + Optional.ofNullable(c.attr(f, "traceStatus")).orElse("UNRESOLVED")));
            sb.append(Md.bullet("Position", position(c, f)));
            sb.append(Md.bullet("Scenarios", c.attr(f, "scenarios")));
            sb.append(Md.bullet("Columns it depends on", c.attr(f, "columns")));
            sb.append(Md.bullet("Constants", c.attr(f, "constants")));
            sb.append(Md.bullet("Gaps (missing links)", c.attr(f, "gaps") == null ? "none" : c.attr(f, "gaps")));
            if (c.attr(f, "scenarioDerivations") != null) {
                sb.append(Md.bullet("Derived differently per scenario", c.attr(f, "derivationDiffersByScenario")));
            }
            sb.append('\n');
            int n = 0;
            for (Map.Entry<Entity, TraceStatus> tr : sortedTraces(c, f)) {
                Entity t = tr.getKey();
                n++;
                sb.append(Md.h(3, "Trace " + n + ": " + c.attr(t, "scenario") + " → " + c.attr(t, "terminalKind") + " "
                        + Md.code(c.attr(t, "terminal")) + " [" + tr.getValue() + "]"));
                sb.append(Md.bullet("Rules applied", c.ruleLabels(c.attr(t, "rules"))));
                sb.append(Md.bullet("Equivalent paths", c.attr(t, "pathCount")));
                sb.append("\nFrom the output back to the source:\n\n");
                String steps = c.attr(t, "steps");
                int i = 0;
                for (String step : steps == null ? new String[0] : steps.split(" <- ")) {
                    sb.append(++i).append(". ").append(Md.code(step)).append('\n');
                }
                sb.append('\n');
            }
        }

        sb.append(Md.h(2, "Forward tracing: from a column to the outputs it feeds"));
        sb.append("Every output field that depends on a column, with the scenario and status of the path. "
                + "Use `trace --column <table.column>` for one column.\n\n");
        List<List<String>> forward = new ArrayList<>();
        for (Entity col : c.of(EntityType.COLUMN)) {
            for (LineageQuery.Impact i : LineageQuery.forward(c.model, ModelIndex.key(col.id()))) {
                forward.add(List.of(ModelIndex.key(col.id()), i.field().name(), i.trace().scenario(), i.trace().status().name(),
                        c.ruleLabels(i.trace().rules())));
            }
        }
        forward.sort(Comparator.comparing((List<String> r) -> r.get(0)).thenComparing(r -> r.get(1)).thenComparing(r -> r.get(2)));
        sb.append(Md.table(List.of("Source column", "Output field", "Scenario", "Status", "Rules"), forward));

        sb.append(Md.h(2, "Sources that are not columns"));
        List<List<String>> others = new ArrayList<>();
        for (Entity f : fields) {
            for (Map.Entry<Entity, TraceStatus> tr : sortedTraces(c, f)) {
                Entity t = tr.getKey();
                if (!"column".equals(c.attr(t, "terminalKind"))) {
                    others.add(List.of(c.attr(t, "terminalKind"), c.attr(t, "terminal"), f.name(), c.attr(t, "scenario"), tr.getValue().name()));
                }
            }
        }
        sb.append(Md.table(List.of("Kind", "Source", "Output field", "Scenario", "Status"), others));
        return sb.toString();
    }

    // ---------------------------------------------------------------- 09

    static String outputSpecification(DocContext c) {
        StringBuilder sb = new StringBuilder(c.header("09 Output file specification", "The layout of each output file, read from the code."));
        sb.append("> Positions are the running sum of the widths found in the layout code. A width is the first integer literal "
                + "argument of the call that builds the field (e.g. `padLeft(x, 10, '0')`); this is a heuristic and is stated per field. "
                + "Check the positions against the format specification if you have one. Type, precision, padding and defaults are not "
                + "derived by the engine.\n\n");
        List<Entity> files = c.of(EntityType.OUTPUT_FILE);
        if (files.isEmpty()) {
            return sb.append("_No fixed-width output file was identified. Only layouts built by string concatenation and written "
                    + "through a file writer are detected._\n").toString();
        }
        for (Entity file : files) {
            sb.append(Md.h(2, file.name()));
            sb.append(Md.bullet("Writer method", Md.code(c.attr(file, "writerMethod"))));
            sb.append(Md.bullet("Layout method", Md.code(c.attr(file, "layoutMethod"))));
            sb.append(Md.bullet("Write call", Md.code(c.attr(file, "sink"))));
            sb.append(Md.bullet("Note", c.attr(file, "note")));
            sb.append(Md.bullet("Evidence", file.evidence().stream().map(e -> c.loc(e.location()) + " (" + e.note() + ")").collect(Collectors.joining("; "))));
            sb.append('\n');
            List<List<String>> rows = new ArrayList<>();
            int total = 0;
            for (Entity f : c.fields()) {
                if (!file.id().value().equals(c.attr(f, "file"))) {
                    continue;
                }
                total += c.attr(f, "width") == null ? 0 : Integer.parseInt(c.attr(f, "width"));
                rows.add(List.of(c.attr(f, "index"), f.name(), Optional.ofNullable(c.attr(f, "start")).orElse("?"),
                        Optional.ofNullable(c.attr(f, "end")).orElse("?"), Optional.ofNullable(c.attr(f, "width")).orElse("?"),
                        Optional.ofNullable(c.attr(f, "widthSource")).orElse(Md.NONE), "not derived",
                        Optional.ofNullable(c.attr(f, "traceStatus")).orElse("UNRESOLVED"), c.loc(f)));
            }
            sb.append(Md.table(List.of("#", "Field", "Start", "End", "Width", "Width taken from", "Type / precision", "Trace status", "Evidence"), rows));
            sb.append("Record length by the sum of widths: **").append(total).append("**.\n\n");
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- 11

    static String scenarios(DocContext c) {
        StringBuilder sb = new StringBuilder(c.header("11 Business scenario analysis",
                "Each scenario discovered in the code, with what it touches and how it derives the outputs."));
        sb.append("> Scenarios are discovered, not configured: a handler class selected by a dispatching `switch`, or a class named "
                + "by the lineage traces. The trigger is the case label as written in the code.\n\n");
        List<Entity> scenarios = new ArrayList<>(c.of(EntityType.SCENARIO));
        scenarios.sort(Comparator.comparing(Entity::name));
        if (scenarios.isEmpty()) {
            return sb.append("_No scenario was discovered._\n").toString();
        }
        for (Entity s : scenarios) {
            sb.append(Md.h(2, s.name() + (c.attr(s, "label") == null ? "" : " (" + c.attr(s, "label") + ")")));
            sb.append(Md.bullet("Trigger", Md.code(c.attr(s, "trigger"))));
            sb.append(Md.bullet("Dispatcher", c.method(c.attr(s, "dispatcher"))));
            sb.append(Md.bullet("How the handler was found", c.attr(s, "dispatchNote")));
            sb.append(Md.bullet("Entry points", c.attr(s, "entryPoints")));
            sb.append(Md.bullet("Status", c.attr(s, "status")));
            sb.append(Md.bullet("Methods involved (" + c.attr(s, "methodCount") + ")", c.attr(s, "methods")));
            sb.append(Md.bullet("Business rules", c.ruleLabels(c.attr(s, "rules"))));
            sb.append(Md.bullet("SQL statements executed", c.attr(s, "sqlStatements") == null ? "none directly (see columns read through lineage)" : c.attr(s, "sqlStatements")));
            sb.append(Md.bullet("Tables accessed by its SQL", c.attr(s, "tables")));
            sb.append(Md.bullet("Columns read (through lineage)", c.attr(s, "columnsRead")));
            sb.append(Md.bullet("Output fields affected", c.attr(s, "outputFields")));
            sb.append(Md.bullet("Output destination", c.attr(s, "outputFiles") == null ? null
                    : Arrays.stream(c.attr(s, "outputFiles").split(", ")).map(id -> Md.code(c.name(new EntityId(id)))).collect(Collectors.joining(", "))));
            sb.append(Md.bullet("Evidence", s.evidence().stream().map(e -> c.loc(e.location()) + " (" + e.note() + ")").collect(Collectors.joining("; "))));
            sb.append('\n');
        }
        sb.append(Md.h(2, "Fields derived differently across scenarios"));
        List<List<String>> diff = new ArrayList<>();
        for (Entity f : c.fields()) {
            if ("true".equals(c.attr(f, "derivationDiffersByScenario"))) {
                for (String part : c.attr(f, "scenarioDerivations").split(" ;; ")) {
                    int colon = part.indexOf(": ");
                    diff.add(List.of(f.name(), part.substring(0, colon), part.substring(colon + 2)));
                }
            }
        }
        sb.append(Md.table(List.of("Output field", "Scenario", "Derivation (terminal via rules)"), diff));
        return sb.toString();
    }
}
