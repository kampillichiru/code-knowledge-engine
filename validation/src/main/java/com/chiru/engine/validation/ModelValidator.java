package com.chiru.engine.validation;

import static com.chiru.engine.model.AnalysisWarning.Severity.ERROR;
import static com.chiru.engine.model.AnalysisWarning.Severity.INFO;
import static com.chiru.engine.model.AnalysisWarning.Severity.WARNING;

import com.chiru.engine.model.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Integrity checks over a finished model. ERROR findings mean the model contradicts its own rules (a claim without
 * evidence, a VERIFIED result built on a weaker one); WARNING and INFO findings are things a reader must know before
 * trusting the documents (unresolved fields, dynamic SQL, evidence that no longer matches the files on disk).
 *
 * <ul>
 *   <li>V001 output field without a trace status</li>
 *   <li>V002 business rule without evidence or trace status</li>
 *   <li>V003 table or column claim (SQL read/write edge) without evidence</li>
 *   <li>V004 call edge without evidence</li>
 *   <li>V005 any entity or relationship without provenance</li>
 *   <li>V006 VERIFIED claimed on top of a weaker link</li>
 *   <li>V007 output field PARTIAL or UNRESOLVED (a missing link is reported, never hidden)</li>
 *   <li>V008 dynamic or unparsed SQL; V009 unsupported SQL constructs</li>
 *   <li>V010 the same rule text in several methods</li>
 *   <li>V011 trace without a scenario (derivations would be merged)</li>
 *   <li>V012 evidence file missing or line range outside the file</li>
 *   <li>V013 a stage reported an ERROR</li>
 *   <li>V017 bytecode branches in a class with no decision in the source model (WARNING)</li>
 * </ul>
 */
public final class ModelValidator {

    private static final int EXAMPLES = 5;
    private static final Set<RelationType> DB_EDGES = EnumSet.of(RelationType.READS_TABLE, RelationType.READS_COLUMN,
            RelationType.INSERTS_INTO, RelationType.INSERTS_COLUMN, RelationType.UPDATES_TABLE,
            RelationType.UPDATES_COLUMN, RelationType.DELETES_FROM);

    private ModelValidator() {}

    /** @param sourceRoots used to find evidence files when the model has no FILE entity for them (may be empty) */
    public static List<Finding> validate(AnalysisModel model, List<Path> sourceRoots) {
        ModelIndex index = new ModelIndex(model);
        List<Finding> findings = new ArrayList<>();
        Collector c = new Collector(findings);

        for (Entity f : model.entitiesOfType(EntityType.OUTPUT_FIELD)) {
            if (f.attributes().get("traceStatus") == null) {
                c.add("V001", ERROR, "output field has no trace status", f.id().value());
            }
        }
        for (Entity r : model.entitiesOfType(EntityType.BUSINESS_RULE)) {
            if (r.evidence().isEmpty() || r.attributes().get("traceStatus") == null) {
                c.add("V002", ERROR, "business rule without evidence or trace status", r.id().value());
            }
        }
        for (Relationship r : model.relationships()) {
            if (DB_EDGES.contains(r.type()) && r.evidence().isEmpty()) {
                c.add("V003", ERROR, "database claim without SQL, ORM or schema evidence", r.key());
            }
            if ((r.type() == RelationType.CALLS || r.type() == RelationType.INVOKES) && r.evidence().isEmpty()) {
                c.add("V004", ERROR, "call relationship without evidence", r.key());
            }
        }
        model.findProvenanceGaps().forEach(g -> c.add("V005", ERROR, "fact without provenance", g));

        consistency(model, index, c);
        reportMissingLinks(model, c);
        sql(model, c);
        duplicateRules(model, c);
        for (Entity t : model.entitiesOfType(EntityType.TRANSFORMATION)) {
            if (t.attributes().get("scenario") == null) {
                c.add("V011", ERROR, "trace without a scenario label: derivations of different scenarios could be merged",
                        t.id().value());
            }
        }
        evidenceOnDisk(model, sourceRoots, c);
        completeness(model, c);
        for (AnalysisWarning w : model.warnings()) {
            if (w.severity() == ERROR) {
                c.add("V013", ERROR, "stage '" + w.stage() + "' reported an error", w.message());
            }
        }
        return c.finish();
    }

    // ---------------------------------------------------------------- V006

    /** A VERIFIED result must not rest on a weaker link; the model already enforces it, this checks the claim. */
    private static void consistency(AnalysisModel model, ModelIndex index, Collector c) {
        for (Entity f : model.entitiesOfType(EntityType.OUTPUT_FIELD)) {
            if (!"VERIFIED".equals(f.attributes().get("traceStatus"))) {
                continue;
            }
            for (Relationship r : index.out(f.id(), RelationType.DERIVED_FROM)) {
                if (r.to().type() == EntityType.TRANSFORMATION && r.status() != TraceStatus.VERIFIED) {
                    c.add("V006", ERROR, "output field is VERIFIED but one of its traces is " + r.status(), f.id().value());
                }
            }
        }
        for (Entity t : model.entitiesOfType(EntityType.TRANSFORMATION)) {
            boolean unresolvedEnd = Set.of("runtime input", "untraced", "entry input").contains(t.attributes().get("terminalKind"));
            if (unresolvedEnd && "VERIFIED".equals(t.attributes().get("status"))) {
                c.add("V006", ERROR, "trace is VERIFIED but ends at '" + t.attributes().get("terminalKind") + "'", t.id().value());
            }
        }
        for (Entity r : model.entitiesOfType(EntityType.BUSINESS_RULE)) {
            if (!"VERIFIED".equals(r.attributes().get("traceStatus"))) {
                continue;
            }
            for (Relationship e : index.out(r.id(), RelationType.GUARDED_BY)) {
                if (e.status() != TraceStatus.VERIFIED) {
                    c.add("V006", ERROR, "rule is VERIFIED but a condition is " + e.status(), r.id().value());
                }
            }
        }
    }

    // ---------------------------------------------------------------- V007

    private static void reportMissingLinks(AnalysisModel model, Collector c) {
        for (Entity f : model.entitiesOfType(EntityType.OUTPUT_FIELD)) {
            String status = f.attributes().get("traceStatus");
            if ("UNRESOLVED".equals(status) || "PARTIAL".equals(status)) {
                String gaps = f.attributes().getOrDefault("gaps", "no detail");
                c.add("V007", "UNRESOLVED".equals(status) ? WARNING : INFO,
                        "output field '" + f.name() + "' is " + status + " (missing link: " + gaps + ")", f.name());
            }
        }
    }

    // ---------------------------------------------------------------- V008, V009

    private static void sql(AnalysisModel model, Collector c) {
        for (Entity s : model.entitiesOfType(EntityType.SQL_STATEMENT)) {
            if ("true".equals(s.attributes().get("dynamic"))) {
                c.add("V008", INFO, "dynamic SQL: parts are not constant, everything derived from it is PARTIAL", s.name());
            }
            if ("false".equals(s.attributes().get("parsed"))) {
                c.add("V008", WARNING, "SQL could not be parsed", s.name());
            }
            if (s.attributes().containsKey("unsupported")) {
                c.add("V009", INFO, "SQL uses constructs the analyser does not model: " + s.attributes().get("unsupported"), s.name());
            }
        }
    }

    // ---------------------------------------------------------------- V010

    private static void duplicateRules(AnalysisModel model, Collector c) {
        Map<String, List<String>> byText = new LinkedHashMap<>();
        for (Entity r : model.entitiesOfType(EntityType.BUSINESS_RULE)) {
            String conditions = r.attributes().get("conditions");
            if (conditions == null) {
                continue;
            }
            byText.computeIfAbsent(conditions + " => " + r.attributes().get("effects"), k -> new ArrayList<>())
                    .add(r.attributes().get("method"));
        }
        byText.forEach((text, methods) -> {
            if (new HashSet<>(methods).size() > 1) {
                c.add("V010", INFO, "the same rule appears in " + new HashSet<>(methods).size() + " methods (copy or "
                        + "conflict?) - review", text);
            }
        });
    }

    // ---------------------------------------------------------------- V012

    private static void evidenceOnDisk(AnalysisModel model, List<Path> roots, Collector c) {
        Map<String, Path> known = new HashMap<>();
        for (Entity f : model.entitiesOfType(EntityType.FILE)) {
            String abs = f.attributes().get("absolutePath");
            if (abs != null) {
                known.put(f.name().replace('\\', '/'), Path.of(abs));
            }
        }
        Map<String, Integer> lineCounts = new HashMap<>();
        Set<SourceLocation> checked = new HashSet<>();
        List<Evidence> all = new ArrayList<>();
        model.entities().forEach(e -> all.addAll(e.evidence()));
        model.relationships().forEach(r -> all.addAll(r.evidence()));
        for (Evidence ev : all) {
            SourceLocation loc = ev.location();
            if (loc == null || !checked.add(loc)) {
                continue;
            }
            Path file = resolve(loc.file(), known, roots);
            if (file == null) {
                c.add("V012", WARNING, "evidence points to a file that was not found", loc.toString());
                continue;
            }
            int lines = lineCounts.computeIfAbsent(file.toString(), k -> countLines(file));
            if (lines > 0 && loc.endLine() > lines) {
                c.add("V012", WARNING, "evidence line range is outside the file (" + lines + " lines)", loc.toString());
            }
        }
    }

    private static Path resolve(String file, Map<String, Path> known, List<Path> roots) {
        Path p = known.get(file);
        if (p != null && Files.isRegularFile(p)) {
            return p;
        }
        for (Path root : roots) {
            for (Path base : new Path[] {root, root.toAbsolutePath().getParent()}) {
                if (base != null && Files.exists(base.resolve(file))) {      // a module root is a directory
                    return base.resolve(file);
                }
            }
        }
        return null;
    }

    private static int countLines(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            int lines = 0;
            for (byte b : bytes) {
                if (b == '\n') {
                    lines++;
                }
            }
            return bytes.length > 0 && bytes[bytes.length - 1] != '\n' ? lines + 1 : lines;
        } catch (IOException e) {
            return 0;
        }
    }

    // ---------------------------------------------------------------- V014, V015

    /**
     * Completeness against the syntax census, an independent count of what the source contains. Only runs when the
     * census did (a model without SYNTAX_UNIT entities has nothing to compare with).
     */
    private static void completeness(AnalysisModel model, Collector c) {
        for (Entity f : model.entitiesOfType(EntityType.FILE)) {
            int unaccounted = Integer.parseInt(f.attributes().getOrDefault("syntaxUnaccounted", "0"));
            if (unaccounted > 0) {
                c.add("V015", WARNING, "code lines that the syntax census does not account for", f.name() + " (" + unaccounted + " lines)");
            }
        }
        bytecodeBranches(model, c);
        OutcomeCoverage.Result r = OutcomeCoverage.compute(model);
        for (OutcomeCoverage.Outcome o : r.missing()) {
            c.add("V014", INFO, o.kind() + " decision outcomes in the source are not captured as conditional rules"
                    + (OutcomeCoverage.modelled(o.kind()) ? " (arms the rules stage missed)" : " (this kind is not modelled yet)"),
                    o.toString());
        }
        for (OutcomeCoverage.PathGap g : r.pathMissing()) {
            c.add("V016", g.expected() ? INFO : WARNING, g.outcome().kind() + " decision outcomes in the source are missing from the path model: "
                    + g.reason(), g.outcome().toString());
        }
    }

    /** V017: the compiler produced branches in a class for which the source analysis found no decision at all. */
    private static void bytecodeBranches(AnalysisModel model, Collector c) {
        if (model.entitiesOfType(EntityType.PATH_DECISION).isEmpty()) {
            return;                                   // the path stage did not run: nothing to compare with
        }
        Map<String, Integer> decisions = new HashMap<>();
        for (Entity d : model.entitiesOfType(EntityType.PATH_DECISION)) {
            if (!"TRY".equals(d.attributes().get("kind"))) {              // a try compiles to jump tables, not branches
                String owner = d.attributes().getOrDefault("method", "");
                int hash = owner.indexOf('#');
                if (owner.startsWith("method:") && hash > 0) {
                    decisions.merge(owner.substring(7, hash), 1, Integer::sum);
                }
            }
        }
        for (Entity cls : model.entitiesOfType(EntityType.CLASS)) {
            String branches = cls.attributes().get("bytecodeBranches");
            if (branches != null && Integer.parseInt(branches) > 0 && !decisions.containsKey(ModelIndex.key(cls.id()))) {
                c.add("V017", WARNING, "the compiler produced branches in a class where the source analysis found no decision", cls.name() + " (" + branches + " branch(es) in bytecode)");
            }
        }
    }

    //   V016 is reported with the V014 loop above
    // ---------------------------------------------------------------- grouping

    private static final class Collector {
        private final List<Finding> sink;
        private final Map<String, Group> groups = new LinkedHashMap<>();

        Collector(List<Finding> sink) {
            this.sink = sink;
        }

        void add(String code, AnalysisWarning.Severity severity, String message, String example) {
            Group g = groups.computeIfAbsent(code + "|" + severity + "|" + message, k -> new Group(code, severity, message));
            g.count++;
            if (g.examples.size() < EXAMPLES) {
                g.examples.add(example);
            }
        }

        List<Finding> finish() {
            groups.values().forEach(g -> sink.add(new Finding(g.code, g.severity, g.message, g.count, g.examples)));
            sink.sort(Comparator.comparing((Finding f) -> f.severity().ordinal()).reversed().thenComparing(Finding::code));
            return sink;
        }
    }

    private static final class Group {
        final String code;
        final AnalysisWarning.Severity severity;
        final String message;
        final List<String> examples = new ArrayList<>();
        int count;

        Group(String code, AnalysisWarning.Severity severity, String message) {
            this.code = code;
            this.severity = severity;
            this.message = message;
        }
    }
}
