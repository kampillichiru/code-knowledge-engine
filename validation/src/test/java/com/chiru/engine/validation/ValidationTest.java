package com.chiru.engine.validation;

import static org.junit.jupiter.api.Assertions.*;

import com.chiru.engine.core.AnalysisConfig;
import com.chiru.engine.core.Pipeline;
import com.chiru.engine.core.StageContext;
import com.chiru.engine.discovery.DiscoveryStage;
import com.chiru.engine.lineage.BindingsStage;
import com.chiru.engine.lineage.LineageStage;
import com.chiru.engine.model.*;
import com.chiru.engine.rules.RulesStage;
import com.chiru.engine.scenarios.ScenarioStage;
import com.chiru.engine.spoon.SpoonSourceStage;
import com.chiru.engine.sql.SqlStage;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ValidationTest {

    private static final Path SAMPLE = Path.of("..", "sample-app").toAbsolutePath().normalize();
    private static AnalysisModel sample;

    @BeforeAll
    static void analyseSample() {
        sample = new AnalysisModel();
        AnalysisConfig cfg = new AnalysisConfig(List.of(SAMPLE.toString()), "out", 21, List.of(), false);
        Pipeline.Result r = new Pipeline(List.of(new DiscoveryStage(), new SpoonSourceStage(), new SqlStage(),
                new RulesStage(), new BindingsStage(), new LineageStage(), new ScenarioStage()))
                .run(new StageContext(cfg, sample));
        assertTrue(r.succeeded(), r.toString());
    }

    private static Optional<Finding> find(List<Finding> findings, String code) {
        return findings.stream().filter(f -> f.code().equals(code)).findFirst();
    }

    @Test
    void cleanSampleHasNoErrorsAndReportsTheOneUnresolvedLink() {
        List<Finding> findings = ModelValidator.validate(sample, List.of(SAMPLE));
        assertTrue(findings.stream().noneMatch(f -> f.severity() == AnalysisWarning.Severity.ERROR),
                findings.stream().map(Finding::describe).toList().toString());
        Finding gap = find(findings, "V007").orElseThrow();
        assertTrue(gap.examples().contains("adjustment"), "the runtime-property field must be reported");
        assertTrue(gap.message().contains("runtime input"));
        assertTrue(find(findings, "V012").isEmpty(), "every evidence location exists in the sample files");
    }

    @Test
    void brokenModelIsCaught() {
        AnalysisModel m = new AnalysisModel();
        Evidence ok = Evidence.extracted("t", new SourceLocation("x/Present.java", 1, 1));
        EntityId field = EntityId.of(EntityType.OUTPUT_FIELD, "f");
        m.putEntity(new Entity(field, "f", Map.of("traceStatus", "VERIFIED"), List.of(ok)));
        m.putEntity(new Entity(EntityId.of(EntityType.OUTPUT_FIELD, "g"), "g", Map.of(), List.of(ok)));          // V001
        m.putEntity(new Entity(EntityId.of(EntityType.BUSINESS_RULE, "r"), "r", Map.of(), List.of()));            // V002
        EntityId t = EntityId.of(EntityType.TRANSFORMATION, "t");
        m.putEntity(new Entity(t, "t", Map.of("terminalKind", "runtime input", "status", "VERIFIED"), List.of(ok))); // V011, V006
        m.addRelationship(new Relationship(field, RelationType.DERIVED_FROM, t, TraceStatus.PARTIAL, List.of(ok)));  // V006
        EntityId a = EntityId.of(EntityType.METHOD, "A#a()");
        EntityId b = EntityId.of(EntityType.METHOD, "A#b()");
        m.putEntity(new Entity(a, "a", Map.of(), List.of(ok)));
        m.putEntity(new Entity(b, "b", Map.of(), List.of(ok)));
        m.addRelationship(new Relationship(a, RelationType.CALLS, b, TraceStatus.VERIFIED, List.of()));             // V004
        EntityId table = EntityId.of(EntityType.TABLE, "tbl");
        m.putEntity(new Entity(table, "tbl", Map.of(), List.of(ok)));
        m.addRelationship(new Relationship(a, RelationType.READS_TABLE, table, TraceStatus.VERIFIED, List.of()));   // V003

        List<Finding> findings = ModelValidator.validate(m, List.of());
        for (String code : List.of("V001", "V002", "V003", "V004", "V005", "V006", "V011")) {
            Finding f = find(findings, code).orElseThrow(() -> new AssertionError(code + " not reported"));
            assertEquals(AnalysisWarning.Severity.ERROR, f.severity(), code);
        }
        assertEquals(2, findings.stream().filter(f -> f.code().equals("V006")).count(),
                "both the field (VERIFIED over a PARTIAL trace) and the trace (VERIFIED at a runtime input) are caught");
    }

    @Test
    void evidenceThatDoesNotMatchTheFilesIsWarned() {
        AnalysisModel m = new AnalysisModel();
        Evidence missing = Evidence.extracted("t", new SourceLocation("sample-app/src/main/java/Nope.java", 1, 2));
        Evidence outside = Evidence.extracted("t", new SourceLocation(
                "sample-app/src/main/java/com/sample/shipping/Shipment.java", 9000, 9001));
        m.putEntity(new Entity(EntityId.of(EntityType.CLASS, "a.Nope"), "Nope", Map.of(), List.of(missing)));
        m.putEntity(new Entity(EntityId.of(EntityType.CLASS, "a.Shipment"), "Shipment", Map.of(), List.of(outside)));
        List<Finding> findings = ModelValidator.validate(m, List.of(SAMPLE));
        assertEquals(2, findings.stream().filter(f -> f.code().equals("V012")).count(), findings.toString());
        assertTrue(findings.stream().filter(f -> f.code().equals("V012"))
                .allMatch(f -> f.severity() == AnalysisWarning.Severity.WARNING));
    }

    @Test
    void identicalRuleTextInTwoMethodsIsHighlighted() {
        AnalysisModel m = new AnalysisModel();
        Evidence ev = Evidence.extracted("t", new SourceLocation("x/A.java", 1, 1));
        for (String method : List.of("A#one()", "B#two()")) {
            m.putEntity(new Entity(EntityId.of(EntityType.BUSINESS_RULE, "cond:" + method), "r",
                    Map.of("conditions", "x > 1", "effects", "y = 2", "method", "method:" + method, "traceStatus", "VERIFIED"),
                    List.of(ev)));
        }
        Finding f = find(ModelValidator.validate(m, List.of()), "V010").orElseThrow();
        assertEquals(AnalysisWarning.Severity.INFO, f.severity());
    }

    @Test
    void findingsAreGroupedWithAFewExamples() {
        AnalysisModel m = new AnalysisModel();
        for (int i = 0; i < 12; i++) {
            m.putEntity(new Entity(EntityId.of(EntityType.OUTPUT_FIELD, "f" + i), "f" + i, Map.of(),
                    List.of(Evidence.extracted("t", new SourceLocation("x/A.java", 1, 1)))));
        }
        Finding f = find(ModelValidator.validate(m, List.of()), "V001").orElseThrow();
        assertEquals(12, f.count());
        assertEquals(5, f.examples().size());
        assertTrue(f.describe().contains("12 occurrences"));
    }

    @Test
    void stageStoresFindingsAsWarningsOfTheValidationStage() {
        AnalysisModel m = new AnalysisModel();
        m.putEntity(new Entity(EntityId.of(EntityType.OUTPUT_FIELD, "f"), "f", Map.of(),
                List.of(Evidence.extracted("t", new SourceLocation("x/A.java", 1, 1)))));
        new ValidationStage().run(new StageContext(new AnalysisConfig(List.of(SAMPLE.toString()), "out", 21, List.of(), false), m));
        assertTrue(m.warnings().stream().anyMatch(w -> w.stage().equals("validation")
                && w.severity() == AnalysisWarning.Severity.ERROR && w.message().contains("V001")));
    }

    @Test
    void coverageStatesItsDenominators() {
        List<Coverage.Metric> metrics = Coverage.compute(sample);
        Coverage.Metric fields = metrics.stream().filter(x -> x.name().equals("Output fields with a trace status")).findFirst().orElseThrow();
        assertEquals("5 of 5", fields.ratio());
        assertEquals("100%", fields.percent());
        int traced = 0;
        for (String s : List.of("VERIFIED", "PARTIAL", "UNRESOLVED")) {
            Coverage.Metric x = metrics.stream().filter(y -> y.name().equals("Output fields " + s)).findFirst().orElseThrow();
            assertEquals(5, x.total());
            traced += x.count();
        }
        assertEquals(5, traced, "every field is in exactly one status");
        assertTrue(metrics.stream().filter(x -> x.total() != null).allMatch(x -> x.basis() != null && !x.basis().isBlank()),
                "a percentage always names what it is a share of");
        Coverage.Metric bytecode = metrics.stream().filter(x -> x.name().equals("Methods with bytecode reachability")).findFirst().orElseThrow();
        assertEquals(0, bytecode.count());
        assertTrue(bytecode.basis().contains("did not run"), "no bytecode roots were given, and the report says so");
    }
}
