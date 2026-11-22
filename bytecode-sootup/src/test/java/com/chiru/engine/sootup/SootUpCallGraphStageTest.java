package com.chiru.engine.sootup;

import static org.junit.jupiter.api.Assertions.*;

import com.chiru.engine.core.AnalysisConfig;
import com.chiru.engine.core.CallGraphMermaid;
import com.chiru.engine.core.Pipeline;
import com.chiru.engine.core.StageContext;
import com.chiru.engine.discovery.DiscoveryStage;
import com.chiru.engine.model.*;
import com.chiru.engine.spoon.SpoonSourceStage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SootUpCallGraphStageTest {

    private static final Path SAMPLE = Path.of("..", "sample-app").toAbsolutePath().normalize();
    private static final String PKG = "com.sample.shipping.";
    private static final String SRC = "sample-app/src/main/java/com/sample/shipping/";
    private static AnalysisModel model;

    @BeforeAll
    static void analyse() {
        model = new AnalysisModel();
        AnalysisConfig cfg = new AnalysisConfig(List.of(SAMPLE.toString()), "out", 21, List.of(), false)
                .withBytecodeRoots(List.of(SAMPLE.resolve("target/classes").toString()));
        Pipeline.Result r = new Pipeline(List.of(new DiscoveryStage(), new SpoonSourceStage(),
                new SootUpCallGraphStage())).run(new StageContext(cfg, model));
        assertTrue(r.succeeded(), r.toString());
    }

    private static EntityId method(String sig) { return EntityId.of(EntityType.METHOD, PKG + sig); }

    private static Optional<Relationship> edge(EntityId from, EntityId to) {
        return model.outgoing(from, RelationType.CALLS).stream().filter(r -> r.to().equals(to)).findFirst();
    }

    private static boolean has(Relationship r, String extractor) {
        return r.evidence().stream().anyMatch(e -> e.extractor().equals(extractor));
    }

    private static int lineOf(String file, String text) throws Exception {
        List<String> lines = Files.readAllLines(SAMPLE.resolve("src/main/java/com/sample/shipping/" + file));
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(text)) return i + 1;
        }
        throw new AssertionError(text + " not found in " + file);
    }

    @Test
    void edgeSeenByBothAnalysesCarriesBothEvidenceAndStaysVerified() {
        Relationship r = edge(method("DispatchService#handle(com.sample.shipping.Shipment)"),
                method("FeeCalculator#feeFor(java.math.BigDecimal,java.math.BigDecimal)")).orElseThrow();
        assertTrue(has(r, "source-spoon"));
        assertTrue(has(r, "bytecode-sootup"));
        assertEquals(TraceStatus.VERIFIED, r.status());
    }

    @Test
    void bytecodeEvidencePointsAtTheRealSourceLine() throws Exception {
        Relationship r = edge(method("DispatchService#handle(com.sample.shipping.Shipment)"),
                method("FeeCalculator#feeFor(java.math.BigDecimal,java.math.BigDecimal)")).orElseThrow();
        Evidence bytecode = r.evidence().stream().filter(e -> e.extractor().equals("bytecode-sootup"))
                .findFirst().orElseThrow();
        assertEquals(SRC + "DispatchService.java", bytecode.location().file());
        assertEquals(lineOf("DispatchService.java", "feeCalculator.feeFor("), bytecode.location().startLine());
    }

    @Test
    void interfaceDispatchFansOutToImplementationsAsPartial() {
        EntityId route = method("ScenarioRouter#route(com.sample.shipping.Shipment)");
        for (String impl : List.of("DispatchService", "StorageService", "ArchiveService")) {
            Relationship r = edge(route, method(impl + "#handle(com.sample.shipping.Shipment)")).orElseThrow();
            assertEquals(TraceStatus.PARTIAL, r.status(), impl);
            assertTrue(has(r, "bytecode-sootup"), impl);
        }
    }

    @Test
    void staticInitializersAreVisibleOnlyInBytecode() {
        EntityId clinit = method("FeeCalculator#<clinit>()");
        Entity e = model.entity(clinit).orElseThrow();
        assertEquals("true", e.attributes().get("bytecodeOnly"));
        EntityId bigDecimalCtor = EntityId.of(EntityType.METHOD, "java.math.BigDecimal#<init>(java.lang.String)");
        Relationship r = edge(clinit, bigDecimalCtor).orElseThrow();
        assertEquals(TraceStatus.PARTIAL, r.status());
    }

    @Test
    void reachabilityDistinguishesVerifiedPossibleAndUnreachable() {
        assertEquals("verified", reach("ShippingJob#main(java.lang.String[])"));
        assertEquals("verified", reach("ScenarioRouter#route(com.sample.shipping.Shipment)"));
        assertEquals("verified", reach("ManifestWriter#format(com.sample.shipping.ManifestRecord)"));
        // only reachable through an interface call, so only possible
        assertEquals("possible", reach("DispatchService#handle(com.sample.shipping.Shipment)"));
        assertEquals("possible", reach("FeeCalculator#feeFor(java.math.BigDecimal,java.math.BigDecimal)"));
        assertEquals("no", reach("ArchiveService#legacyReason(com.sample.shipping.Shipment)"));
    }

    private static String reach(String sig) {
        return model.entity(method(sig)).orElseThrow().attributes().get("reachable");
    }

    @Test
    void crossCheckReportsWithoutRaisingWarningsForTheSample() {
        List<AnalysisWarning> mine = model.warnings().stream().filter(w -> w.stage().equals("bytecode-sootup")).toList();
        assertTrue(mine.stream().anyMatch(w -> w.message().startsWith("Call-graph cross-check")), mine.toString());
        assertTrue(mine.stream().noneMatch(w -> w.severity() != AnalysisWarning.Severity.INFO), mine.toString());
    }

    @Test
    void everyFactStillHasEvidence() {
        assertTrue(model.findProvenanceGaps().isEmpty(), model.findProvenanceGaps().toString());
    }

    @Test
    void mermaidShowsVerifiedAndPartialEdgesFromTheEntryPoint() {
        String d = CallGraphMermaid.render(model, method("ShippingJob#main(java.lang.String[])"), 3, false);
        assertTrue(d.contains("ScenarioRouter.route"), d);
        assertTrue(d.contains("DispatchService.handle"), d);
        assertTrue(d.contains(" --> "), d);
        assertTrue(d.contains(" -.-> "), d);
    }

    @Test
    void noBytecodeRootsIsAnExplicitSkip() {
        AnalysisModel m = new AnalysisModel();
        AnalysisConfig cfg = new AnalysisConfig(List.of("."), "out", 21, List.of(), false);
        new SootUpCallGraphStage().run(new StageContext(cfg, m));
        assertEquals(1, m.warnings().size());
        assertTrue(m.warnings().get(0).message().contains("skipped"));
    }

    @Test
    void missingBytecodeRootFails(@TempDir Path dir) {
        AnalysisConfig cfg = new AnalysisConfig(List.of("."), "out", 21, List.of(), false)
                .withBytecodeRoots(List.of(dir.resolve("nope").toString()));
        assertThrows(IllegalArgumentException.class,
                () -> new SootUpCallGraphStage().run(new StageContext(cfg, new AnalysisModel())));
    }
}
