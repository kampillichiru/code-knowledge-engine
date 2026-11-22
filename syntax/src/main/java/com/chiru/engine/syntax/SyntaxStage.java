package com.chiru.engine.syntax;

import com.chiru.engine.core.AnalysisStage;
import com.chiru.engine.core.StageContext;
import com.chiru.engine.model.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Phase 10b, step 1: the syntax census. Parses every main-scope Java file with the JDK's own parser and writes one
 * SYNTAX_UNIT per method, constructor, initialiser or lambda and one DECISION per decision point with its outcomes.
 * It is independent of Spoon, so the validation stage can compare what the source contains with what the rest of the
 * engine modelled. Run right after discovery. Without a JDK (a bare JRE) it records a warning and does nothing.
 */
public final class SyntaxStage implements AnalysisStage {

    public static final String NAME = "syntax";
    private static final String EXTRACTOR = NAME;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void run(StageContext ctx) throws IOException {
        AnalysisModel model = ctx.model();
        Map<Path, String> files = new LinkedHashMap<>();
        model.entitiesOfType(EntityType.FILE).stream()
                .filter(f -> "JAVA".equals(f.attributes().get("kind")) && "main".equals(f.attributes().get("scope")))
                .sorted(Comparator.comparing(Entity::name))
                .forEach(f -> files.put(Path.of(f.attributes().get("absolutePath")), f.name()));
        if (files.isEmpty()) {
            warn(model, AnalysisWarning.Severity.INFO, "no main-scope Java file to count", null);
            return;
        }
        List<SyntaxCensus.FileResult> results;
        try {
            results = SyntaxCensus.analyse(files);
        } catch (IllegalStateException e) {
            warn(model, AnalysisWarning.Severity.WARNING, "syntax census skipped: " + e.getMessage(), null);
            return;
        }

        int units = 0, statements = 0, decisions = 0, outcomes = 0, code = 0, missing = 0;
        for (SyntaxCensus.FileResult r : results) {
            for (String error : r.parseErrors()) {
                warn(model, AnalysisWarning.Severity.WARNING, "parse error (the file's census may be incomplete): " + error,
                        new SourceLocation(r.file(), Math.max(1, firstLine(error)), Math.max(1, firstLine(error))));
            }
            for (SyntaxCensus.Unit u : r.units()) {
                write(model, u);
                units++;
                statements += u.statements().size();
                decisions += u.decisions().size();
                outcomes += u.outcomes();
            }
            code += r.codeLines();
            missing += r.unaccountedLines().size();
            Map<String, String> a = new LinkedHashMap<>();
            a.put("syntaxCodeLines", String.valueOf(r.codeLines()));
            a.put("syntaxUnaccounted", String.valueOf(r.unaccountedLines().size()));
            model.entity(EntityId.of(EntityType.FILE, fileKey(model, r.file()))).ifPresent(f ->
                    model.putEntity(new Entity(f.id(), f.name(), a, List.of())));
            if (!r.unaccountedLines().isEmpty()) {
                int first = r.unaccountedLines().get(0);
                warn(model, AnalysisWarning.Severity.WARNING, r.unaccountedLines().size() + " code line(s) are not accounted for "
                        + "by the syntax census (first: line " + first + ")", new SourceLocation(r.file(), first, first));
            }
        }
        warn(model, AnalysisWarning.Severity.INFO, "syntax census: " + files.size() + " file(s), " + units + " unit(s), "
                + statements + " statement(s), " + decisions + " decision point(s) with " + outcomes + " outcome(s); "
                + (missing == 0 ? "every one of " + code + " code lines is accounted for"
                : missing + " of " + code + " code lines are not accounted for"), null);
    }

    private static void write(AnalysisModel model, SyntaxCensus.Unit u) {
        EntityId id = EntityId.of(EntityType.SYNTAX_UNIT, u.key());
        Map<String, String> a = new LinkedHashMap<>();
        a.put("kind", u.kind());
        a.put("owner", u.owner());
        a.put("params", u.params());
        a.put("paramCount", String.valueOf(u.paramCount()));
        a.put("statements", String.valueOf(u.statements().size()));
        a.put("statementKinds", count(u.statements().stream().map(SyntaxCensus.Statement::kind)));
        a.put("decisions", String.valueOf(u.decisions().size()));
        a.put("outcomes", String.valueOf(u.outcomes()));
        a.put("outcomeKinds", u.decisions().stream().collect(Collectors.groupingBy(SyntaxCensus.Decision::kind, TreeMap::new,
                Collectors.summingInt(d -> d.outcomes().size()))).entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(",")));
        Evidence unitEv = evidence(u.file(), u.line(), u.endLine(), u.kind().toLowerCase(Locale.ROOT));
        model.putEntity(new Entity(id, u.name(), a, List.of(unitEv)));
        int seq = 0;
        for (SyntaxCensus.Decision d : u.decisions()) {
            // two operators of one condition start at the same position, so the sequence number keeps the IDs apart
            EntityId did = EntityId.of(EntityType.DECISION, u.key() + ":" + (++seq) + ":" + d.line() + ":" + d.kind());
            Map<String, String> da = new LinkedHashMap<>();
            da.put("kind", d.kind());
            da.put("unit", id.value());
            da.put("line", String.valueOf(d.line()));
            da.put("condition", d.condition());
            da.put("outcomes", String.join(" || ", d.outcomes()));
            da.put("outcomeCount", String.valueOf(d.outcomes().size()));
            Evidence ev = evidence(u.file(), d.line(), d.line(), d.kind().toLowerCase(Locale.ROOT) + " decision");
            model.putEntity(new Entity(did, d.kind() + " " + d.condition(), da, List.of(ev)));
            model.addRelationship(new Relationship(id, RelationType.CONTAINS, did, TraceStatus.VERIFIED, List.of(ev)));
        }
    }

    private static String count(java.util.stream.Stream<String> kinds) {
        return kinds.collect(Collectors.groupingBy(k -> k, TreeMap::new, Collectors.counting())).entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining(","));
    }

    private static Evidence evidence(String file, int start, int end, String note) {
        return new Evidence(new SourceLocation(file, Math.max(1, start), Math.max(Math.max(1, start), end)), EXTRACTOR,
                Certainty.EXTRACTED, note);
    }

    /** The FILE entity key whose display name is {@code display}: {@code file:<repo>/<rel>}. */
    private static String fileKey(AnalysisModel model, String display) {
        return model.entitiesOfType(EntityType.FILE).stream().filter(f -> f.name().equals(display)).findFirst()
                .map(f -> ModelIndex.key(f.id())).orElse(display);
    }

    private static int firstLine(String error) {
        try {
            return Integer.parseInt(error.substring(5, error.indexOf(':')));
        } catch (RuntimeException e) {
            return 1;
        }
    }

    private static void warn(AnalysisModel model, AnalysisWarning.Severity severity, String message, SourceLocation location) {
        model.addWarning(new AnalysisWarning(NAME, severity, message, location));
    }
}
