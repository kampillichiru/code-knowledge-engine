package com.chiru.engine.validation;

import com.chiru.engine.model.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Coverage numbers with their denominators. A coverage figure only says how much of what was <em>discovered</em> was
 * handled; it says nothing about whether a derivation is correct, and it cannot see what the analysis never found.
 * Every metric therefore names what it is a share of.
 */
public final class Coverage {

    /** @param total the denominator, or null when the figure is a plain count */
    public record Metric(String group, String name, int count, Integer total, String basis) {
        public String percent() {
            return total == null || total == 0 ? "n/a" : Math.round(100.0 * count / total) + "%";
        }

        public String ratio() {
            return total == null ? String.valueOf(count) : count + " of " + total;
        }
    }

    private Coverage() {}

    public static List<Metric> compute(AnalysisModel model) {
        List<Metric> m = new ArrayList<>();
        List<Entity> classes = inSource(model, EntityType.CLASS);
        List<Entity> methods = inSource(model, EntityType.METHOD);
        m.add(new Metric("Code", "Classes discovered", classes.size(), null, "CLASS entities in the analysed sources"));
        m.add(new Metric("Code", "Methods discovered", methods.size(), null, "METHOD entities in the analysed sources"));
        long withBytecode = methods.stream().filter(e -> e.attributes().containsKey("reachable")).count();
        m.add(new Metric("Code", "Methods with bytecode reachability", (int) withBytecode, methods.size(),
                withBytecode == 0 ? "bytecode analysis did not run (no bytecode roots)" : "methods found in the compiled classes"));
        for (String r : List.of("verified", "possible", "no")) {
            long n = methods.stream().filter(e -> r.equals(e.attributes().get("reachable"))).count();
            if (withBytecode > 0) {
                m.add(new Metric("Code", "Reachable from an entry point: " + r, (int) n, (int) withBytecode,
                        "methods with bytecode reachability"));
            }
        }
        m.add(new Metric("Code", "Entry points", (int) methods.stream().filter(e -> e.attributes().containsKey("entryPoint")).count(),
                null, "methods named main or carrying an entry-point annotation"));

        List<Relationship> calls = model.relationships().stream().filter(r -> r.type() == RelationType.CALLS).toList();
        for (TraceStatus s : TraceStatus.values()) {
            m.add(new Metric("Calls", "Call edges " + s, (int) calls.stream().filter(r -> r.status() == s).count(),
                    calls.size(), "all CALLS relationships"));
        }

        List<Entity> sql = model.entitiesOfType(EntityType.SQL_STATEMENT);
        m.add(new Metric("SQL", "SQL statements discovered", sql.size(), null, "constants, mapper XML and .sql files"));
        m.add(new Metric("SQL", "SQL statements parsed", (int) sql.stream().filter(e -> "true".equals(e.attributes().get("parsed"))).count(),
                sql.size(), "discovered SQL statements"));
        m.add(new Metric("SQL", "SQL statements with dynamic parts", (int) sql.stream()
                .filter(e -> "true".equals(e.attributes().get("dynamic"))).count(), sql.size(), "discovered SQL statements"));
        m.add(new Metric("SQL", "Tables identified", model.entitiesOfType(EntityType.TABLE).size(), null, "TABLE entities"));
        List<Entity> columns = model.entitiesOfType(EntityType.COLUMN);
        m.add(new Metric("SQL", "Columns identified", columns.size(), null, "COLUMN entities"));
        m.add(new Metric("SQL", "Columns attributed to a table", (int) columns.stream()
                .filter(e -> !"true".equals(e.attributes().get("unresolved"))).count(), columns.size(), "COLUMN entities"));

        List<Entity> rules = model.entitiesOfType(EntityType.BUSINESS_RULE);
        m.add(new Metric("Rules", "Business rules extracted", rules.size(), null, "conditional and computation rules"));
        for (String s : List.of("VERIFIED", "PARTIAL", "UNRESOLVED")) {
            m.add(new Metric("Rules", "Rules " + s, (int) rules.stream().filter(e -> s.equals(e.attributes().get("traceStatus"))).count(),
                    rules.size(), "extracted rules"));
        }
        m.add(new Metric("Scenarios", "Scenarios identified", model.entitiesOfType(EntityType.SCENARIO).size(), null,
                "SCENARIO entities"));

        List<Entity> fields = model.entitiesOfType(EntityType.OUTPUT_FIELD);
        m.add(new Metric("Lineage", "Output files identified", model.entitiesOfType(EntityType.OUTPUT_FILE).size(), null,
                "fixed-width layouts with a file writer"));
        m.add(new Metric("Lineage", "Output fields identified", fields.size(), null, "OUTPUT_FIELD entities"));
        m.add(new Metric("Lineage", "Output fields with a trace status", (int) fields.stream()
                .filter(e -> e.attributes().containsKey("traceStatus")).count(), fields.size(), "output fields"));
        for (String s : List.of("VERIFIED", "PARTIAL", "UNRESOLVED")) {
            m.add(new Metric("Lineage", "Output fields " + s, (int) fields.stream()
                    .filter(e -> s.equals(e.attributes().get("traceStatus"))).count(), fields.size(), "output fields"));
        }
        m.add(new Metric("Lineage", "Traces written", model.entitiesOfType(EntityType.TRANSFORMATION).size(), null,
                "TRANSFORMATION entities (one per scenario and source)"));

        Map<AnalysisWarning.Severity, Long> bySeverity = new java.util.EnumMap<>(AnalysisWarning.Severity.class);
        model.warnings().forEach(w -> bySeverity.merge(w.severity(), 1L, Long::sum));
        for (AnalysisWarning.Severity s : AnalysisWarning.Severity.values()) {
            m.add(new Metric("Warnings", "Warnings " + s, bySeverity.getOrDefault(s, 0L).intValue(), model.warnings().size(),
                    "all analysis warnings"));
        }
        m.add(new Metric("Warnings", "SQL statements with unsupported constructs", (int) sql.stream()
                .filter(e -> e.attributes().containsKey("unsupported")).count(), sql.size(), "discovered SQL statements"));
        completeness(model, m);
        return m;
    }

    /** The syntax census as an independent denominator: what exists in the source versus what the engine captured. */
    private static void completeness(AnalysisModel model, List<Metric> m) {
        List<Entity> units = model.entitiesOfType(EntityType.SYNTAX_UNIT);
        if (units.isEmpty()) {
            return;
        }
        String census = "syntax census (JDK parser, independent of Spoon)";
        int statements = units.stream().mapToInt(u -> Integer.parseInt(u.attributes().getOrDefault("statements", "0"))).sum();
        int codeLines = 0;
        int unaccounted = 0;
        for (Entity f : model.entitiesOfType(EntityType.FILE)) {
            codeLines += Integer.parseInt(f.attributes().getOrDefault("syntaxCodeLines", "0"));
            unaccounted += Integer.parseInt(f.attributes().getOrDefault("syntaxUnaccounted", "0"));
        }
        m.add(new Metric("Completeness", "Units counted (methods, initialisers, lambdas)", units.size(), null, census));
        m.add(new Metric("Completeness", "Statements counted", statements, null, census));
        m.add(new Metric("Completeness", "Code lines accounted for by the census", codeLines - unaccounted, codeLines,
                "code lines in the main-scope Java files (comments and bare brackets excluded)"));
        OutcomeCoverage.Result r = OutcomeCoverage.compute(model);
        m.add(new Metric("Completeness", "Decision outcomes in the source", r.total(), null,
                census + ": every if, ternary, switch label, loop, catch, && and ||, assert, guard and boolean lambda"));
        m.add(new Metric("Completeness", "Outcomes captured as conditional rules", r.captured(), r.total(),
                "decision outcomes in the source; only if, ternary and switch arms can be captured today"));
        m.add(new Metric("Completeness", "Outcomes in the path model", r.pathModelled(), r.total(),
                "decision outcomes in the source; the Spoon-based path model must contain every outcome of the kinds it supports"));
        r.pathByKind().forEach((kind, c) -> m.add(new Metric("Completeness", "  in the path model, kind " + kind, c[1], c[0],
                OutcomeCoverage.pathSupported(kind) ? "outcomes of this kind in the source"
                        : "outcomes of this kind in the source; the path model does not support this kind yet")));
        r.byKind().forEach((kind, c) -> m.add(new Metric("Completeness", "  of kind " + kind, c[1], c[0],
                OutcomeCoverage.modelled(kind) ? "outcomes of this kind in the source"
                        : "outcomes of this kind in the source; the rules stage does not model this kind yet")));
    }

    private static List<Entity> inSource(AnalysisModel model, EntityType type) {
        return model.entitiesOfType(type).stream().filter(e -> !"true".equals(e.attributes().get("external"))).toList();
    }
}
