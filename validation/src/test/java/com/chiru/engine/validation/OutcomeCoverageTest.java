package com.chiru.engine.validation;

import static org.junit.jupiter.api.Assertions.*;

import com.chiru.engine.model.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OutcomeCoverageTest {

    private static final String FILE = "x/A.java";

    private static Evidence ev(int line) {
        return Evidence.extracted("t", new SourceLocation(FILE, line, line));
    }

    private static void decision(AnalysisModel m, String kind, int line, String outcomes) {
        m.putEntity(new Entity(EntityId.of(EntityType.DECISION, "u:" + line + ":" + kind), kind,
                Map.of("kind", kind, "outcomes", outcomes), List.of(ev(line))));
    }

    private static void rule(AnalysisModel m, String id, String guards) {
        m.putEntity(new Entity(EntityId.of(EntityType.BUSINESS_RULE, id), id,
                Map.of("kind", "conditional", "guards", guards), List.of(ev(1))));
    }

    @Test
    void anOutcomeIsCapturedOnlyWhenARuleSitsUnderThatArm() {
        AnalysisModel m = new AnalysisModel();
        decision(m, "IF", 10, "then || else");
        decision(m, "SWITCH", 20, "1 || 2 || default (implicit)");
        decision(m, "LOOP", 30, "enter || exit");
        rule(m, "r1", "branch:m@10:5-80:if#then");
        rule(m, "r2", "branch:m@20:5-90:switch#1 | 2");

        OutcomeCoverage.Result r = OutcomeCoverage.compute(m);
        assertTrue(r.censusAvailable());
        assertEquals(7, r.total());
        assertEquals(3, r.captured());
        assertArrayEquals(new int[] {2, 1}, r.byKind().get("IF"));
        assertArrayEquals(new int[] {3, 2}, r.byKind().get("SWITCH"), "a combined arm '1 | 2' is two source outcomes");
        assertArrayEquals(new int[] {2, 0}, r.byKind().get("LOOP"), "loops are not modelled by the rules stage");
        assertEquals(List.of("x/A.java:10 IF else", "x/A.java:20 SWITCH default (implicit)", "x/A.java:30 LOOP enter",
                "x/A.java:30 LOOP exit"), r.missing().stream().map(OutcomeCoverage.Outcome::toString).toList());
    }

    @Test
    void anOuterConditionIsCreditedThroughTheGuardChain() {
        AnalysisModel m = new AnalysisModel();
        decision(m, "IF", 10, "then || else");
        decision(m, "IF", 12, "then || else");
        rule(m, "nested", "branch:m@12:9-60:if#else ;; branch:m@10:5-80:if#then");

        OutcomeCoverage.Result r = OutcomeCoverage.compute(m);
        assertEquals(2, r.captured(), "the outer then-arm has no effect of its own but the rule under it proves it is modelled");
        assertEquals(List.of("x/A.java:10 IF else", "x/A.java:12 IF then"), r.missing().stream().map(OutcomeCoverage.Outcome::toString).toList());
    }

    @Test
    void manyRulesUnderOneArmCreditItOnce() {
        AnalysisModel m = new AnalysisModel();
        decision(m, "IF", 10, "then || else");
        rule(m, "a", "branch:m@10:5-80:if#then");
        rule(m, "b", "branch:m@10:5-80:if#then");
        assertEquals(1, OutcomeCoverage.compute(m).captured(), "the outcome exists once in the source");
    }

    @Test
    void enumQualifiersAreIgnoredWhenComparingLabels() {
        AnalysisModel m = new AnalysisModel();
        decision(m, "SWITCH_EXPR", 5, "OPEN || ARCHIVED");
        rule(m, "e", "branch:m@5:5-50:switch-expression#Status.OPEN");
        assertEquals("OPEN", OutcomeCoverage.normalise("Status.OPEN"));
        assertEquals("\"a.b\"", OutcomeCoverage.normalise("\"a.b\""), "string literals keep their dots");
        assertEquals("1.5", OutcomeCoverage.normalise("1.5"));
        OutcomeCoverage.Result r = OutcomeCoverage.compute(m);
        assertEquals(1, r.captured());
        assertEquals("ARCHIVED", r.missing().get(0).outcome());
    }

    @Test
    void sameLineDecisionsAreCountedByMultiplicity() {
        AnalysisModel m = new AnalysisModel();
        m.putEntity(new Entity(EntityId.of(EntityType.DECISION, "u:1"), "a", Map.of("kind", "TERNARY", "outcomes", "then || else"), List.of(ev(7))));
        m.putEntity(new Entity(EntityId.of(EntityType.DECISION, "u:2"), "b", Map.of("kind", "TERNARY", "outcomes", "then || else"), List.of(ev(7))));
        rule(m, "t", "branch:m@7:5-20:ternary#then ;; branch:m@7:30-40:ternary#else");
        // one rule credits each distinct arm once, so two ternaries on one line cannot both be credited by it
        assertEquals(2, OutcomeCoverage.compute(m).captured());
        assertEquals(4, OutcomeCoverage.compute(m).total());
    }

    @Test
    void withoutACensusThereIsNothingToCompare() {
        OutcomeCoverage.Result r = OutcomeCoverage.compute(new AnalysisModel());
        assertFalse(r.censusAvailable());
        assertEquals(0, r.total());
        assertTrue(Coverage.compute(new AnalysisModel()).stream().noneMatch(x -> x.group().equals("Completeness")));
    }

    @Test
    void validatorReportsMissingOutcomesGroupedByKindAndStaysInfo() {
        AnalysisModel m = new AnalysisModel();
        decision(m, "IF", 10, "then || else");
        decision(m, "LOOP", 30, "enter || exit");
        rule(m, "r1", "branch:m@10:5-80:if#then");
        List<Finding> findings = ModelValidator.validate(m, List.of());
        List<Finding> v014 = findings.stream().filter(f -> f.code().equals("V014")).toList();
        assertEquals(2, v014.size());
        assertTrue(v014.stream().allMatch(f -> f.severity() == AnalysisWarning.Severity.INFO));
        assertTrue(v014.stream().anyMatch(f -> f.message().contains("IF") && f.message().contains("arms the rules stage missed")));
        assertTrue(v014.stream().anyMatch(f -> f.message().contains("LOOP") && f.message().contains("not modelled yet") && f.count() == 2));
    }

    @Test
    void unaccountedLinesAreAWarning() {
        AnalysisModel m = new AnalysisModel();
        m.putEntity(new Entity(EntityId.of(EntityType.FILE, "r/A.java"), "r/A.java",
                Map.of("kind", "JAVA", "syntaxCodeLines", "50", "syntaxUnaccounted", "3"), List.of(ev(1))));
        Finding f = ModelValidator.validate(m, List.of()).stream().filter(x -> x.code().equals("V015")).findFirst().orElseThrow();
        assertEquals(AnalysisWarning.Severity.WARNING, f.severity());
    }

    @Test
    void coverageReportsCensusNumbersWithTheirDenominators() {
        AnalysisModel m = new AnalysisModel();
        m.putEntity(new Entity(EntityId.of(EntityType.SYNTAX_UNIT, "u"), "u", Map.of("statements", "4"), List.of(ev(1))));
        m.putEntity(new Entity(EntityId.of(EntityType.FILE, "r/A.java"), "r/A.java",
                Map.of("syntaxCodeLines", "40", "syntaxUnaccounted", "0"), List.of(ev(1))));
        decision(m, "IF", 10, "then || else");
        rule(m, "r1", "branch:m@10:5-80:if#then");
        List<Coverage.Metric> metrics = Coverage.compute(m);
        Coverage.Metric captured = metrics.stream().filter(x -> x.name().equals("Outcomes captured as conditional rules")).findFirst().orElseThrow();
        assertEquals("1 of 2", captured.ratio());
        assertEquals("50%", captured.percent());
        Coverage.Metric lines = metrics.stream().filter(x -> x.name().startsWith("Code lines accounted")).findFirst().orElseThrow();
        assertEquals("40 of 40", lines.ratio());
        assertTrue(metrics.stream().filter(x -> x.group().equals("Completeness") && x.total() != null).allMatch(x -> !x.basis().isBlank()));
    }
}
