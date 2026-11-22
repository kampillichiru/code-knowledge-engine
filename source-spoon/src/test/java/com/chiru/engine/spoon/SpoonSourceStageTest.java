package com.chiru.engine.spoon;

import static org.junit.jupiter.api.Assertions.*;

import com.chiru.engine.core.AnalysisConfig;
import com.chiru.engine.core.Pipeline;
import com.chiru.engine.core.StageContext;
import com.chiru.engine.discovery.DiscoveryStage;
import com.chiru.engine.model.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SpoonSourceStageTest {

    private static final Path SAMPLE = Path.of("..", "sample-app").toAbsolutePath().normalize();
    private static final String PKG = "com.sample.shipping.";
    private static final String SRC = "sample-app/src/main/java/com/sample/shipping/";
    private static AnalysisModel model;

    @BeforeAll
    static void analyseSample() {
        model = new AnalysisModel();
        AnalysisConfig cfg = new AnalysisConfig(List.of(SAMPLE.toString()), "out", 21, List.of(), false);
        Pipeline.Result r = new Pipeline(List.of(new DiscoveryStage(), new SpoonSourceStage()))
                .run(new StageContext(cfg, model));
        assertTrue(r.succeeded(), r.toString());
    }

    private static EntityId cls(String simple) { return EntityId.of(EntityType.CLASS, PKG + simple); }
    private static EntityId method(String sig) { return EntityId.of(EntityType.METHOD, PKG + sig); }

    private static Optional<Relationship> edge(EntityId from, RelationType t, EntityId to) {
        return model.outgoing(from, t).stream().filter(r -> r.to().equals(to)).findFirst();
    }

    private static int lineOf(String file, String text) throws Exception {
        List<String> lines = Files.readAllLines(SAMPLE.resolve("src/main/java/com/sample/shipping/" + file));
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(text)) return i + 1;
        }
        throw new AssertionError(text + " not found in " + file);
    }

    @Test
    void extractsAllTypesWithKinds() {
        long inSource = model.entitiesOfType(EntityType.CLASS).stream()
                .filter(e -> !"true".equals(e.attributes().get("external"))).count();
        assertEquals(12, inSource);
        assertEquals("interface", model.entity(cls("ScenarioHandler")).orElseThrow().attributes().get("kind"));
        assertEquals("class", model.entity(cls("Shipment")).orElseThrow().attributes().get("kind"));
        assertTrue(edge(cls("DispatchService"), RelationType.IMPLEMENTS, cls("ScenarioHandler")).isPresent());
        assertTrue(model.entity(EntityId.of(EntityType.PACKAGE, "com.sample.shipping")).isPresent());
    }

    @Test
    void evidenceMatchesRealSourceLines() throws Exception {
        Entity c = model.entity(cls("FeeCalculator")).orElseThrow();
        SourceLocation loc = c.evidence().get(0).location();
        assertEquals(SRC + "FeeCalculator.java", loc.file());
        assertEquals(lineOf("FeeCalculator.java", "public class FeeCalculator"), loc.startLine());

        Entity m = model.entity(method("FeeCalculator#feeFor(java.math.BigDecimal,java.math.BigDecimal)")).orElseThrow();
        assertEquals(lineOf("FeeCalculator.java", "public BigDecimal feeFor"), m.evidence().get(0).location().startLine());
    }

    @Test
    void detectsEntryPointAndConstructorsAndFields() {
        Entity main = model.entity(method("ShippingJob#main(java.lang.String[])")).orElseThrow();
        assertEquals("main", main.attributes().get("entryPoint"));
        assertTrue(model.entity(method("Shipment#<init>(long,java.lang.String,java.math.BigDecimal,java.math.BigDecimal,java.time.LocalDate)")).isPresent());
        Entity cap = model.entity(EntityId.of(EntityType.FIELD, PKG + "FeeCalculator#FEE_CAP")).orElseThrow();
        assertEquals("new java.math.BigDecimal(\"25.00\")", cap.attributes().get("initializer"));
        assertEquals("private static final", cap.attributes().get("modifiers"));
    }

    @Test
    void staticallyBoundCallIsVerified() {
        Relationship r = edge(method("DispatchService#handle(com.sample.shipping.Shipment)"), RelationType.CALLS,
                method("FeeCalculator#feeFor(java.math.BigDecimal,java.math.BigDecimal)")).orElseThrow();
        assertEquals(TraceStatus.VERIFIED, r.status());
        assertFalse(r.evidence().isEmpty());
    }

    @Test
    void interfaceCallIsPartialWithPossibleTargets() {
        EntityId route = method("ScenarioRouter#route(com.sample.shipping.Shipment)");
        Relationship viaInterface = edge(route, RelationType.CALLS,
                method("ScenarioHandler#handle(com.sample.shipping.Shipment)")).orElseThrow();
        assertEquals(TraceStatus.PARTIAL, viaInterface.status());
        for (String impl : List.of("DispatchService", "StorageService", "ArchiveService")) {
            Relationship r = edge(route, RelationType.CALLS,
                    method(impl + "#handle(com.sample.shipping.Shipment)")).orElseThrow();
            assertEquals(TraceStatus.PARTIAL, r.status(), impl);
        }
    }

    @Test
    void externalCallsAreMarkedAndNotVerified() {
        EntityId getProperty = EntityId.of(EntityType.METHOD, "java.lang.System#getProperty(java.lang.String,java.lang.String)");
        Entity ext = model.entity(getProperty).orElseThrow();
        assertEquals("true", ext.attributes().get("external"));
        Relationship r = edge(method("ExternalRateClient#fetchAdjustment(long)"), RelationType.CALLS, getProperty).orElseThrow();
        assertEquals(TraceStatus.PARTIAL, r.status());
    }

    @Test
    void switchBranchRecordsCases() throws Exception {
        EntityId route = method("ScenarioRouter#route(com.sample.shipping.Shipment)");
        List<Entity> switches = model.outgoing(route, RelationType.CONTAINS).stream()
                .map(r -> model.entity(r.to()).orElseThrow())
                .filter(e -> e.type() == EntityType.BRANCH).toList();
        assertEquals(1, switches.size());
        Entity sw = switches.get(0);
        assertEquals("switch", sw.attributes().get("kind"));
        assertEquals("\"DISPATCH\", \"STORAGE\", \"ARCHIVE\", default", sw.attributes().get("cases"));
        assertEquals(lineOf("ScenarioRouter.java", "switch (shipment.getStatus())"),
                sw.evidence().get(0).location().startLine());
    }

    @Test
    void nestedBranchesKeepParentAndArm() {
        EntityId handle = method("StorageService#handle(com.sample.shipping.Shipment)");
        List<Entity> outer = model.outgoing(handle, RelationType.CONTAINS).stream()
                .map(r -> model.entity(r.to()).orElseThrow()).filter(e -> e.type() == EntityType.BRANCH).toList();
        assertEquals(1, outer.size());
        Entity overdue = outer.get(0);
        assertTrue(overdue.attributes().get("condition").contains("isBefore"));

        List<Entity> inner = model.outgoing(overdue.id(), RelationType.CONTAINS).stream()
                .map(r -> model.entity(r.to()).orElseThrow()).toList();
        assertEquals(1, inner.size());
        assertEquals("then", inner.get(0).attributes().get("parentArm"));
        assertTrue(inner.get(0).attributes().get("condition").contains("compareTo"));
    }

    @Test
    void everyFactHasEvidenceAndNothingIsUnresolvedInSample() {
        assertTrue(model.findProvenanceGaps().isEmpty(), model.findProvenanceGaps().toString());
        assertTrue(model.relationships().stream().noneMatch(r -> r.status() == TraceStatus.UNRESOLVED));
        assertTrue(model.warnings().stream().noneMatch(w -> w.severity() != AnalysisWarning.Severity.INFO),
                model.warnings().toString());
    }

    @Test
    void unresolvableCallsAreFlaggedNotHidden(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("src/main/java/p"));
        Files.writeString(dir.resolve("src/main/java/p/A.java"), """
                package p;
                public class A {
                    void run(Missing m) { m.go(); }
                }
                """);
        AnalysisModel m = new AnalysisModel();
        AnalysisConfig cfg = new AnalysisConfig(List.of(dir.toString()), "out", 21, List.of(), false);
        assertTrue(new Pipeline(List.of(new DiscoveryStage(), new SpoonSourceStage()))
                .run(new StageContext(cfg, m)).succeeded());

        Entity run = m.entitiesOfType(EntityType.METHOD).stream()
                .filter(e -> e.id().value().startsWith("method:p.A#run(")).findFirst().orElseThrow();
        List<Relationship> calls = m.outgoing(run.id(), RelationType.CALLS);
        assertEquals(1, calls.size());
        assertNotEquals(TraceStatus.VERIFIED, calls.get(0).status());
    }
}
