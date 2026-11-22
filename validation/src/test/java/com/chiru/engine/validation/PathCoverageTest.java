package com.chiru.engine.validation;

import static org.junit.jupiter.api.Assertions.*;

import com.chiru.engine.model.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The census-versus-path-model identity: pairing, labels, and which gaps are defects. */
class PathCoverageTest {

    private static final String FILE = "x/A.java";

    private static Evidence ev(int line) {
        return Evidence.extracted("t", new SourceLocation(FILE, line, line));
    }

    private static void unit(AnalysisModel m, String key, String kind) {
        m.putEntity(new Entity(EntityId.of(EntityType.SYNTAX_UNIT, key), key, Map.of("kind", kind), List.of(ev(1))));
    }

    private static void census(AnalysisModel m, String unit, int seq, String kind, int line, String outcomes) {
        m.putEntity(new Entity(EntityId.of(EntityType.DECISION, unit + ":" + seq + ":" + line + ":" + kind), kind,
                Map.of("kind", kind, "outcomes", outcomes, "unit", "syntax_unit:" + unit), List.of(ev(line))));
    }

    private static void pathDecision(AnalysisModel m, String id, String kind, int line, int col, int end, String outcomes) {
        m.putEntity(new Entity(EntityId.of(EntityType.PATH_DECISION, id), kind,
                Map.of("kind", kind, "outcomes", outcomes, "col", String.valueOf(col), "end", String.valueOf(end)), List.of(ev(line))));
    }

    @Test
    void aSourceDecisionIsPairedWithThePathModelDecisionOnTheSameLineAndKind() {
        AnalysisModel m = new AnalysisModel();
        unit(m, "u", "METHOD");
        census(m, "u", 1, "IF", 10, "then || else");
        census(m, "u", 2, "LOOP", 20, "enter || exit");
        pathDecision(m, "p1", "IF", 10, 5, 90, "then || else");
        pathDecision(m, "p2", "LOOP", 20, 5, 70, "enter || exit");

        OutcomeCoverage.Result r = OutcomeCoverage.compute(m);
        assertEquals(4, r.total());
        assertEquals(4, r.pathModelled());
        assertEquals(List.of(), r.pathMissing());
        assertArrayEquals(new int[] {2, 2}, r.pathByKind().get("LOOP"));
    }

    @Test
    void anOutcomeTheModelLacksIsReportedAndOnlyThatOutcome() {
        AnalysisModel m = new AnalysisModel();
        unit(m, "u", "METHOD");
        census(m, "u", 1, "IF", 10, "then || else");
        pathDecision(m, "p1", "IF", 10, 5, 90, "then");
        OutcomeCoverage.Result r = OutcomeCoverage.compute(m);
        assertEquals(1, r.pathModelled());
        assertEquals(1, r.pathMissing().size());
        assertEquals("else", r.pathMissing().get(0).outcome().outcome());
    }

    @Test
    void aMissingOutcomeOfASupportedKindInAMethodIsADefect() {
        AnalysisModel m = new AnalysisModel();
        unit(m, "u", "METHOD");
        census(m, "u", 1, "IF", 10, "then || else");
        OutcomeCoverage.Result r = OutcomeCoverage.compute(m);
        assertEquals(2, r.pathMissing().size());
        assertTrue(r.pathMissing().stream().noneMatch(OutcomeCoverage.PathGap::expected), "this is a defect, not a known gap");

        Finding v016 = ModelValidator.validate(m, List.of()).stream().filter(f -> f.code().equals("V016")).findFirst().orElseThrow();
        assertEquals(AnalysisWarning.Severity.WARNING, v016.severity());
        assertTrue(v016.message().contains("missing from the path model"));
    }

    @Test
    void everyKindTheCensusCountsIsExpectedInThePathModelExceptAKindItDoesNotKnow() {
        AnalysisModel m = new AnalysisModel();
        unit(m, "lam", "LAMBDA");
        unit(m, "meth", "METHOD");
        census(m, "lam", 1, "IF", 10, "then || else");                  // inside a lambda: the path model covers lambdas now
        census(m, "meth", 1, "SWITCH_EXPR", 20, "A || B");
        census(m, "meth", 2, "PATTERN_GUARD", 30, "guard true || guard false");
        census(m, "meth", 3, "FUTURE_KIND", 40, "x");               // a kind added to the census later
        OutcomeCoverage.Result r = OutcomeCoverage.compute(m);
        assertEquals(7, r.pathMissing().size());
        assertEquals(6, r.pathMissing().stream().filter(g -> !g.expected()).count(), "these are defects now");
        assertTrue(r.pathMissing().stream().filter(OutcomeCoverage.PathGap::expected).allMatch(g -> g.reason().contains("not modelled yet")));
        List<Finding> v016 = ModelValidator.validate(m, List.of()).stream().filter(f -> f.code().equals("V016")).toList();
        assertTrue(v016.stream().filter(f -> f.message().contains("FUTURE_KIND")).allMatch(f -> f.severity() == AnalysisWarning.Severity.INFO));
        assertTrue(v016.stream().filter(f -> !f.message().contains("FUTURE_KIND")).allMatch(f -> f.severity() == AnalysisWarning.Severity.WARNING));
    }

    @Test
    void twoDecisionsOnOneLineArePairedInSourceOrder() {
        AnalysisModel m = new AnalysisModel();
        unit(m, "u", "METHOD");
        census(m, "u", 1, "SHORT_CIRCUIT", 7, "evaluate right || short-circuit");
        census(m, "u", 2, "SHORT_CIRCUIT", 7, "evaluate right || short-circuit");
        // two operators that start at the same column: the longer (outer) one is first, as the census sees it
        pathDecision(m, "inner", "SHORT_CIRCUIT", 7, 12, 30, "evaluate right || short-circuit");
        pathDecision(m, "outer", "SHORT_CIRCUIT", 7, 12, 45, "evaluate right || short-circuit");
        OutcomeCoverage.Result r = OutcomeCoverage.compute(m);
        assertEquals(4, r.pathModelled());

        AnalysisModel one = new AnalysisModel();
        unit(one, "u", "METHOD");
        census(one, "u", 1, "SHORT_CIRCUIT", 7, "evaluate right || short-circuit");
        census(one, "u", 2, "SHORT_CIRCUIT", 7, "evaluate right || short-circuit");
        pathDecision(one, "only", "SHORT_CIRCUIT", 7, 12, 30, "evaluate right || short-circuit");
        assertEquals(2, OutcomeCoverage.compute(one).pathModelled(), "a second source decision with no partner is missing");
    }

    @Test
    void catchTypesAreComparedBySimpleNamesAndCaseLabelsWithoutQualifiers() {
        assertEquals("catch NumberFormatException", OutcomeCoverage.label("TRY", "catch java.lang.NumberFormatException"));
        assertEquals("catch A | B", OutcomeCoverage.label("TRY", "catch x.A | y.B"));
        assertEquals("OPEN", OutcomeCoverage.label("SWITCH", "Status.OPEN"));
        assertEquals("then", OutcomeCoverage.label("IF", "then"));

        AnalysisModel m = new AnalysisModel();
        unit(m, "u", "METHOD");
        census(m, "u", 1, "TRY", 5, "normal || catch java.io.IOException");
        pathDecision(m, "t", "TRY", 5, 5, 99, "normal || catch IOException");
        assertEquals(2, OutcomeCoverage.compute(m).pathModelled());
    }

    @Test
    void coverageReportsThePathModelPerKindWithADenominator() {
        AnalysisModel m = new AnalysisModel();
        unit(m, "u", "METHOD");
        m.putEntity(new Entity(EntityId.of(EntityType.SYNTAX_UNIT, "u2"), "u2", Map.of("statements", "3"), List.of(ev(1))));
        m.putEntity(new Entity(EntityId.of(EntityType.FILE, "r/A.java"), "r/A.java",
                Map.of("syntaxCodeLines", "10", "syntaxUnaccounted", "0"), List.of(ev(1))));
        census(m, "u", 1, "IF", 10, "then || else");
        census(m, "u", 2, "FUTURE_KIND", 20, "A || B");
        pathDecision(m, "p1", "IF", 10, 5, 90, "then || else");
        List<Coverage.Metric> metrics = Coverage.compute(m);
        Coverage.Metric all = metrics.stream().filter(x -> x.name().equals("Outcomes in the path model")).findFirst().orElseThrow();
        assertEquals("2 of 4", all.ratio());
        assertEquals("50%", all.percent());
        Coverage.Metric future = metrics.stream().filter(x -> x.name().equals("  in the path model, kind FUTURE_KIND")).findFirst().orElseThrow();
        assertEquals("0 of 2", future.ratio());
        assertTrue(future.basis().contains("does not support this kind yet"));
    }

    @Test
    void noPathModelMeansEveryOutcomeIsMissingButNothingCrashes() {
        AnalysisModel m = new AnalysisModel();
        unit(m, "u", "METHOD");
        census(m, "u", 1, "IF", 10, "then || else");
        OutcomeCoverage.Result r = OutcomeCoverage.compute(m);
        assertEquals(0, r.pathModelled());
        assertEquals(2, r.total());
    }
}
