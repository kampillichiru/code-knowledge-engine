package com.chiru.engine.sql;

import com.chiru.engine.model.*;
import com.chiru.engine.sql.analysis.SchemaCatalog;
import com.chiru.engine.sql.analysis.SqlAnalysis;
import com.chiru.engine.sql.analysis.SqlAnalysis.Access;
import com.chiru.engine.sql.analysis.SqlAnalysis.ColumnUse;
import com.chiru.engine.sql.analysis.SqlAnalysis.Param;
import com.chiru.engine.sql.analysis.SqlAnalysis.SelectItemInfo;
import com.chiru.engine.sql.analysis.SqlAnalysis.TableUse;
import com.chiru.engine.sql.collect.SchemaDef;
import com.chiru.engine.sql.collect.SqlOrigin;
import com.chiru.engine.sql.collect.SqlOrigin.Binding;
import com.chiru.engine.sql.collect.SqlOrigin.Consumer;
import com.chiru.engine.sql.collect.SqlOrigin.ResultRead;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Writes analysed SQL into the model. Edges are VERIFIED only when the SQL (and schema, where needed) establishes
 * them and the statement is not dynamic; anything approximate is PARTIAL and anything unattributable is UNRESOLVED.
 */
final class SqlModelWriter {

    static final String EXTRACTOR = SqlStage.NAME;
    private static final String UNRESOLVED_TABLE = "(unresolved)";
    private static final int EXAMPLES = 3;
    private static final int TEXT_LIMIT = 500;

    private final AnalysisModel model;
    private final SchemaCatalog catalog;

    private final Tally notInSchema = new Tally();
    private final Tally unresolvedColumns = new Tally();
    private final Tally unboundParams = new Tally();
    private final Tally badBindingIndex = new Tally();
    private final Tally unmatchedReads = new Tally();
    private final Tally unsupported = new Tally();
    private final Tally missingMethods = new Tally();
    private final Tally dynamicStatements = new Tally();
    private final Tally insertSelectUnmapped = new Tally();

    SqlModelWriter(AnalysisModel model, SchemaCatalog catalog) {
        this.model = model;
        this.catalog = catalog;
    }

    private static final class Tally {
        int count;
        final List<String> examples = new ArrayList<>();

        void add(String example) {
            count++;
            if (examples.size() < EXAMPLES) {
                examples.add(example);
            }
        }
    }

    // ------------------------------------------------------------------ schema

    void writeSchema(SchemaDef def) {
        String note = def.source().equals("ddl") ? "CREATE TABLE" : "ORM mapping";
        Evidence tableEv = new Evidence(def.location(), EXTRACTOR, Certainty.EXTRACTED,
                note + (def.inferred() ? " (table name taken from the class name; naming strategy not applied)" : ""));
        EntityId tableId = EntityId.of(EntityType.TABLE, def.table());
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("source", def.source());
        if (def.mappedClass() != null) {
            attrs.put("mappedClass", def.mappedClass());
        }
        if (def.inferred()) {
            attrs.put("nameInferred", "true");
        }
        model.putEntity(new Entity(tableId, def.table(), attrs, List.of(tableEv)));
        if (def.mappedClass() != null) {
            EntityId classId = EntityId.of(EntityType.CLASS, def.mappedClass());
            if (model.entity(classId).isPresent()) {
                link(classId, RelationType.DEPENDS_ON, tableId, def.inferred() ? TraceStatus.PARTIAL : TraceStatus.VERIFIED, tableEv);
            }
        }
        for (SchemaDef.Col col : def.columns()) {
            Evidence ev = new Evidence(col.location(), EXTRACTOR, Certainty.EXTRACTED,
                    note + (col.inferred() ? " (column name taken from the field name)" : ""));
            Map<String, String> ca = new LinkedHashMap<>();
            ca.put("table", def.table());
            if (!col.type().isBlank()) {
                ca.put("type", col.type());
            }
            if (!col.specs().isBlank()) {
                ca.put("constraints", col.specs());
            }
            if (col.inferred()) {
                ca.put("nameInferred", "true");
            }
            EntityId colId = EntityId.of(EntityType.COLUMN, def.table() + "." + col.name());
            model.putEntity(new Entity(colId, col.name(), ca, List.of(ev)));
            link(tableId, RelationType.CONTAINS, colId,
                    def.inferred() || col.inferred() ? TraceStatus.PARTIAL : TraceStatus.VERIFIED, ev);
        }
    }

    // ------------------------------------------------------------------ statements

    void writeUnparsed(SqlOrigin o, String error) {
        EntityId sqlId = EntityId.of(EntityType.SQL_STATEMENT, o.key());
        Evidence ev = new Evidence(o.location(), EXTRACTOR, Certainty.EXTRACTED, "SQL text found but not parsed");
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("origin", o.kind().name());
        attrs.put("parsed", "false");
        attrs.put("error", error);
        attrs.put("text", abbrev(o.text(), TEXT_LIMIT));
        if (o.dynamic()) {
            attrs.put("dynamic", "true");
        }
        model.putEntity(new Entity(sqlId, "unparsed SQL", attrs, List.of(ev)));
        linkExecutors(o, sqlId, TraceStatus.PARTIAL);
        model.addWarning(new AnalysisWarning(EXTRACTOR, AnalysisWarning.Severity.WARNING,
                "SQL could not be parsed (" + error + "); no table or column facts were derived from it", o.location()));
    }

    void writeStatement(SqlOrigin o, SqlAnalysis a) {
        boolean dyn = o.dynamic() || a.dynamic();
        TraceStatus base = dyn ? TraceStatus.PARTIAL : TraceStatus.VERIFIED;
        EntityId sqlId = EntityId.of(EntityType.SQL_STATEMENT, o.key());
        Evidence ev = new Evidence(o.location(), EXTRACTOR, Certainty.EXTRACTED, dyn ? "dynamic SQL: facts are partial" : null);

        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("type", a.type());
        attrs.put("origin", o.kind().name());
        attrs.put("parsed", "true");
        attrs.put("text", abbrev(o.text(), TEXT_LIMIT));
        if (dyn) {
            attrs.put("dynamic", "true");
            dynamicStatements.add(o.location().toString());
        }
        put(attrs, "definedIn", o.java() == null || o.java().definedIn() == null ? null : o.java().definedIn().value());
        put(attrs, "subqueries", a.subqueries() > 0 ? String.valueOf(a.subqueries()) : null);
        put(attrs, "ctes", join(a.ctes()));
        put(attrs, "joins", String.join(" ; ", a.joins()));
        put(attrs, "predicates", String.join(" ; ", a.predicates()));
        put(attrs, "groupBy", join(a.groupBy()));
        put(attrs, "orderBy", join(a.orderBy()));
        put(attrs, "aggregates", join(a.aggregates()));
        put(attrs, "functions", join(a.functions()));
        put(attrs, "routines", join(a.routines()));
        put(attrs, "unsupported", String.join(" ; ", a.unsupported()));
        put(attrs, "notes", String.join(" ; ", o.notes()));
        String target = a.tables().stream().map(TableUse::name).distinct().collect(Collectors.joining(", "));
        model.putEntity(new Entity(sqlId, a.type() + (target.isEmpty() ? "" : " " + target), attrs, List.of(ev)));
        a.unsupported().forEach(u -> unsupported.add(o.location() + ": " + u));

        writeTables(sqlId, a, base, o, ev);
        writeColumns(sqlId, a, base, o, ev);
        linkExecutors(o, sqlId, base);
        writeParameters(sqlId, o, a, base, ev);
        writeInsertSelect(o, a, base, ev);
    }

    private void writeTables(EntityId sqlId, SqlAnalysis a, TraceStatus base, SqlOrigin o, Evidence ev) {
        Set<String> done = new HashSet<>();
        for (TableUse t : a.tables()) {
            if (!done.add(t.name() + "/" + t.access())) {
                continue;
            }
            EntityId tid = ensureTable(t.name(), ev);
            RelationType rt = switch (t.access()) {
                case READ -> RelationType.READS_TABLE;
                case INSERT -> RelationType.INSERTS_INTO;
                case UPDATE -> RelationType.UPDATES_TABLE;
                case DELETE -> RelationType.DELETES_FROM;
            };
            link(sqlId, rt, tid, base, withNote(ev, t.access() + (t.alias() == null ? "" : " as " + t.alias())));
        }
    }

    private void writeColumns(EntityId sqlId, SqlAnalysis a, TraceStatus base, SqlOrigin o, Evidence ev) {
        Set<String> done = new HashSet<>();
        for (ColumnUse c : a.columns()) {
            if (!done.add(c.table() + "." + c.column() + "/" + c.access() + "/" + c.role() + "/" + c.note())) {
                continue;
            }
            EntityId cid = ensureColumn(c.table(), c.column(), ev);
            RelationType rt = switch (c.access()) {
                case READ -> RelationType.READS_COLUMN;
                case INSERT -> RelationType.INSERTS_COLUMN;
                case UPDATE -> RelationType.UPDATES_COLUMN;
                case DELETE -> RelationType.READS_COLUMN;
            };
            TraceStatus st = !c.resolved() ? TraceStatus.UNRESOLVED
                    : c.note() != null || base != TraceStatus.VERIFIED ? TraceStatus.PARTIAL : TraceStatus.VERIFIED;
            if (!c.resolved()) {
                unresolvedColumns.add(o.location() + " '" + c.column() + "': " + c.note());
            }
            link(sqlId, rt, cid, st, withNote(ev, c.role() + (c.note() == null ? "" : ": " + c.note())));
        }
    }

    /** Methods that run this SQL; unrecognised consumers are not claimed as executors. */
    private void linkExecutors(SqlOrigin o, EntityId sqlId, TraceStatus base) {
        if (o.java() == null) {
            return;
        }
        for (Consumer c : o.java().consumers()) {
            if (c.method() == null || c.api().equals("unknown")) {
                continue;
            }
            if (model.entity(c.method()).isEmpty()) {
                missingMethods.add(c.method().value());
                continue;
            }
            link(c.method(), RelationType.EXECUTES, sqlId, base,
                    new Evidence(c.location(), EXTRACTOR, Certainty.EXTRACTED, "executed via " + c.api()));
        }
    }

    // ------------------------------------------------------------------ parameters and output columns

    private void writeParameters(EntityId sqlId, SqlOrigin o, SqlAnalysis a, TraceStatus base, Evidence sqlEv) {
        List<Consumer> consumers = o.java() == null ? List.of() : o.java().consumers().stream()
                .filter(c -> !c.api().equals("unknown")).toList();
        if (consumers.isEmpty()) {
            writeParametersFor(sqlId, o, a, base, sqlEv, null);
        } else {
            consumers.forEach(c -> writeParametersFor(sqlId, o, a, base, sqlEv, c));
        }
    }

    private void writeParametersFor(EntityId sqlId, SqlOrigin o, SqlAnalysis a, TraceStatus base, Evidence sqlEv, Consumer c) {
        String suffix = c == null || c.method() == null ? "" : "@" + c.method().value().substring(c.method().value().indexOf(':') + 1);
        int maxIndex = a.params().stream().mapToInt(Param::index).max().orElse(0);

        for (Param p : a.params()) {
            EntityId pid = EntityId.of(EntityType.PARAMETER, o.key() + suffix + "#" + p.index());
            Map<String, String> attrs = new LinkedHashMap<>();
            attrs.put("direction", "IN");
            attrs.put("index", String.valueOf(p.index()));
            put(attrs, "name", p.name());
            put(attrs, "column", p.column());
            put(attrs, "operator", p.operator());
            attrs.put("role", p.role());
            attrs.put("direct", String.valueOf(p.direct()));
            List<Evidence> evidence = new ArrayList<>(List.of(withNote(sqlEv, "parameter " + p.index() + " in " + p.role())));

            if (o.kind() == SqlOrigin.Kind.XML && o.paramNames().size() == a.params().size()) {
                attrs.put("javaParameter", o.paramNames().get(p.index() - 1));
            }
            if (c != null) {
                bindingNotes(c, p, attrs, evidence, o);
            }
            model.putEntity(new Entity(pid, "param " + p.index(), attrs, evidence));
            link(sqlId, RelationType.CONTAINS, pid, TraceStatus.VERIFIED, evidence.get(0));

            if (p.column() != null) {
                int dot = p.column().lastIndexOf('.');
                EntityId cid = ensureColumn(p.column().substring(0, dot), p.column().substring(dot + 1), sqlEv);
                TraceStatus st = base == TraceStatus.VERIFIED && p.direct() ? TraceStatus.VERIFIED : TraceStatus.PARTIAL;
                link(pid, RelationType.BINDS_PARAMETER, cid, st, withNote(sqlEv,
                        p.column() + " " + p.operator() + " ?" + (p.direct() ? "" : " (inside an expression)")));
            }
        }
        if (c != null) {
            for (Binding b : c.bindings()) {
                if (b.index() < 1 || b.index() > maxIndex) {
                    badBindingIndex.add(b.location() + " " + b.setter() + "(" + (b.index() < 1 ? "non-constant index" : b.index()) + ")");
                }
            }
        }
        writeOutputs(sqlId, o, a, base, sqlEv, c, suffix);
    }

    private void bindingNotes(Consumer c, Param p, Map<String, String> attrs, List<Evidence> evidence, SqlOrigin o) {
        if (!c.bindingsDerived()) {
            attrs.put("javaBinding", "not derived: " + c.note());
            return;
        }
        List<Binding> matches = c.bindings().stream().filter(b -> b.index() == p.index()).toList();
        if (matches.isEmpty()) {
            attrs.put("javaBinding", "not found");
            unboundParams.add(o.location() + " parameter " + p.index());
            return;
        }
        attrs.put("javaExpression", matches.stream().map(Binding::expression).collect(Collectors.joining(" | ")));
        attrs.put("javaSetter", matches.stream().map(Binding::setter).distinct().collect(Collectors.joining(", ")));
        put(attrs, "javaVariables", matches.stream().flatMap(b -> b.variables().stream()).distinct().collect(Collectors.joining(", ")));
        for (Binding b : matches) {
            evidence.add(new Evidence(b.location(), EXTRACTOR, Certainty.EXTRACTED, b.setter() + "(" + b.index() + ", ...)"));
        }
    }

    private void writeOutputs(EntityId sqlId, SqlOrigin o, SqlAnalysis a, TraceStatus base, Evidence sqlEv,
                              Consumer c, String suffix) {
        List<SelectItemInfo> items = a.selectItems();
        Set<String> usedLabels = new HashSet<>();
        Set<ResultRead> matched = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int i = 0; i < items.size(); i++) {
            SelectItemInfo item = items.get(i);
            String label = usedLabels.add(item.label()) ? item.label() : item.label() + "#" + (i + 1);
            EntityId pid = EntityId.of(EntityType.PARAMETER, o.key() + suffix + "@out:" + label);
            Map<String, String> attrs = new LinkedHashMap<>();
            attrs.put("direction", "OUT");
            attrs.put("label", item.label());
            attrs.put("position", String.valueOf(i + 1));
            attrs.put("expression", item.expression());
            put(attrs, "note", item.note());
            List<Evidence> evidence = new ArrayList<>(List.of(withNote(sqlEv, "select item " + (i + 1))));

            if (c != null && c.bindingsDerived()) {
                final int position = i + 1;
                List<ResultRead> reads = c.reads().stream()
                        .filter(r -> (r.label() != null && r.label().equals(item.label())) || r.index() == position).toList();
                matched.addAll(reads);
                if (!reads.isEmpty()) {
                    attrs.put("javaGetter", reads.stream().map(ResultRead::getter).distinct().collect(Collectors.joining(", ")));
                    attrs.put("javaExpression", reads.stream().map(ResultRead::expression).collect(Collectors.joining(" | ")));
                    put(attrs, "flowsTo", reads.stream().map(ResultRead::flowsTo).filter(Objects::nonNull)
                            .collect(Collectors.joining(" | ")));
                    reads.forEach(r -> evidence.add(new Evidence(r.location(), EXTRACTOR, Certainty.EXTRACTED,
                            r.getter() + "(" + (r.label() != null ? "\"" + r.label() + "\"" : r.index()) + ")")));
                }
            }
            model.putEntity(new Entity(pid, "out " + item.label(), attrs, evidence));
            link(sqlId, RelationType.CONTAINS, pid, TraceStatus.VERIFIED, evidence.get(0));

            for (String source : item.sources()) {
                int dot = source.lastIndexOf('.');
                EntityId cid = ensureColumn(source.substring(0, dot), source.substring(dot + 1), sqlEv);
                TraceStatus st = base == TraceStatus.VERIFIED && item.note() == null ? TraceStatus.VERIFIED : TraceStatus.PARTIAL;
                link(pid, RelationType.DERIVED_FROM, cid, st, withNote(sqlEv, "select item '" + item.label() + "' = " + item.expression()));
            }
        }
        if (c != null && c.bindingsDerived()) {
            for (ResultRead r : c.reads()) {
                if (!matched.contains(r)) {
                    unmatchedReads.add(r.location() + " " + r.getter() + "(" + (r.label() != null ? r.label() : r.index()) + ")");
                }
            }
        }
    }

    /** INSERT ... SELECT: each target column is derived from the source columns of the select item at its position. */
    private void writeInsertSelect(SqlOrigin o, SqlAnalysis a, TraceStatus base, Evidence ev) {
        if (a.insertColumns().isEmpty() || a.selectItems().isEmpty()) {
            return;
        }
        boolean star = a.selectItems().stream().anyMatch(SelectItemInfo::star);
        if (star || a.insertColumns().size() != a.selectItems().size()) {
            insertSelectUnmapped.add(o.location().toString());
            return;
        }
        for (int i = 0; i < a.insertColumns().size(); i++) {
            String target = a.insertColumns().get(i);
            int dot = target.lastIndexOf('.');
            EntityId tid = ensureColumn(target.substring(0, dot), target.substring(dot + 1), ev);
            for (String source : a.selectItems().get(i).sources()) {
                int sdot = source.lastIndexOf('.');
                EntityId sid = ensureColumn(source.substring(0, sdot), source.substring(sdot + 1), ev);
                link(tid, RelationType.DERIVED_FROM, sid, base, withNote(ev, "INSERT ... SELECT position " + (i + 1)));
            }
        }
    }

    // ------------------------------------------------------------------ entities

    private EntityId ensureTable(String name, Evidence ev) {
        EntityId id = EntityId.of(EntityType.TABLE, name);
        if (model.entity(id).isEmpty()) {
            model.putEntity(new Entity(id, name, Map.of("source", "sql-reference"), List.of(withNote(ev, "referenced by SQL"))));
        }
        return id;
    }

    private EntityId ensureColumn(String table, String column, Evidence ev) {
        String owner = table == null ? UNRESOLVED_TABLE : table;
        EntityId id = EntityId.of(EntityType.COLUMN, owner + "." + column);
        if (model.entity(id).isPresent()) {
            return id;
        }
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("table", owner);
        if (table == null) {
            attrs.put("unresolved", "true");
        }
        boolean declared = table != null && catalog.hasColumn(table, column);
        if (table != null && !column.equals("*") && catalog.knowsTable(table) && !declared) {
            attrs.put("notInSchema", "true");
            notInSchema.add(table + "." + column + " at " + ev.location());
        }
        model.putEntity(new Entity(id, column, attrs, List.of(withNote(ev, "referenced by SQL"))));
        if (table != null && !column.equals("*")) {
            EntityId tid = ensureTable(table, ev);
            link(tid, RelationType.CONTAINS, id, TraceStatus.PARTIAL,
                    withNote(ev, "column referenced in SQL; not declared in a known schema"));
        }
        return id;
    }

    // ------------------------------------------------------------------ summary

    void summarize() {
        tally(notInSchema, AnalysisWarning.Severity.WARNING, " column reference(s) are not declared in the known schema");
        tally(unresolvedColumns, AnalysisWarning.Severity.WARNING,
                " column reference(s) could not be attributed to a table (qualify them or supply DDL)");
        tally(unsupported, AnalysisWarning.Severity.WARNING, " SQL construct(s) were not analysed");
        tally(unboundParams, AnalysisWarning.Severity.WARNING,
                " statement parameter(s) have no setXxx call with a constant index in the same method");
        tally(badBindingIndex, AnalysisWarning.Severity.WARNING,
                " setXxx call(s) use a non-constant or out-of-range index and were not matched to a parameter");
        tally(unmatchedReads, AnalysisWarning.Severity.WARNING,
                " ResultSet read(s) do not match any select item of the statement");
        tally(insertSelectUnmapped, AnalysisWarning.Severity.INFO,
                " INSERT ... SELECT statement(s) have a star or a column-count mismatch: column mapping not derived");
        tally(missingMethods, AnalysisWarning.Severity.INFO,
                " executing method(s) are not in the model (run the source stage first); EXECUTES edges were not written");
        tally(dynamicStatements, AnalysisWarning.Severity.INFO,
                " dynamic SQL statement(s): everything derived from them is PARTIAL");
    }

    private void tally(Tally t, AnalysisWarning.Severity severity, String message) {
        if (t.count > 0) {
            model.addWarning(new AnalysisWarning(EXTRACTOR, severity,
                    t.count + message + " (e.g. " + String.join("; ", t.examples) + ")", null));
        }
    }

    // ------------------------------------------------------------------ small helpers

    private void link(EntityId from, RelationType type, EntityId to, TraceStatus status, Evidence ev) {
        model.addRelationship(new Relationship(from, type, to, status, List.of(ev)));
    }

    private static Evidence withNote(Evidence ev, String note) {
        return new Evidence(ev.location(), ev.extractor(), ev.certainty(), note);
    }

    private static void put(Map<String, String> m, String k, String v) {
        if (v != null && !v.isBlank()) {
            m.put(k, v);
        }
    }

    private static String join(List<String> l) {
        return String.join(", ", l);
    }

    private static String abbrev(String s, int limit) {
        return s.length() <= limit ? s : s.substring(0, limit) + "...";
    }
}
