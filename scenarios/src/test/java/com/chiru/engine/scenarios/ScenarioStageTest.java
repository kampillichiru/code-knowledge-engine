package com.chiru.engine.scenarios;

import static org.junit.jupiter.api.Assertions.*;

import com.chiru.engine.core.AnalysisConfig;
import com.chiru.engine.core.Pipeline;
import com.chiru.engine.core.StageContext;
import com.chiru.engine.discovery.DiscoveryStage;
import com.chiru.engine.lineage.BindingsStage;
import com.chiru.engine.lineage.LineageStage;
import com.chiru.engine.model.*;
import com.chiru.engine.rules.RulesStage;
import com.chiru.engine.spoon.SpoonSourceStage;
import com.chiru.engine.sql.SqlStage;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ScenarioStageTest {

    private static final Path SAMPLE = Path.of("..", "sample-app").toAbsolutePath().normalize();
    private static final String PKG = "com.sample.shipping.";
    private static AnalysisModel model;

    @BeforeAll
    static void analyseSample() {
        model = new AnalysisModel();
        AnalysisConfig cfg = new AnalysisConfig(List.of(SAMPLE.toString()), "out", 21, List.of(), false);
        Pipeline.Result r = new Pipeline(List.of(new DiscoveryStage(), new SpoonSourceStage(), new SqlStage(),
                new RulesStage(), new BindingsStage(), new LineageStage(), new ScenarioStage()))
                .run(new StageContext(cfg, model));
        assertTrue(r.succeeded(), r.toString());
    }

    private static Entity scenario(String simple) {
        return model.entity(EntityId.of(EntityType.SCENARIO, PKG + simple)).orElseThrow();
    }

    @Test
    void findsOneScenarioPerHandlerSelectedByTheSwitch() {
        List<String> names = model.entitiesOfType(EntityType.SCENARIO).stream().map(Entity::name).sorted().toList();
        assertEquals(List.of("ArchiveService", "DispatchService", "StorageService"), names);
    }

    @Test
    void triggerAndLabelComeFromTheCaseLabel() {
        Entity shipped = scenario("DispatchService");
        assertEquals("DISPATCH", shipped.attributes().get("label"));
        assertTrue(shipped.attributes().get("trigger").contains("getStatus"));
        assertEquals("STORAGE", scenario("StorageService").attributes().get("label"));
        assertEquals("ARCHIVE", scenario("ArchiveService").attributes().get("label"));
        assertTrue(shipped.attributes().get("dispatcher").contains("ScenarioRouter#route"));
        assertEquals("VERIFIED", shipped.attributes().get("status"), "the field is final and only set to new DispatchService()");
        assertTrue(shipped.attributes().get("dispatchNote").contains("final"));
    }

    @Test
    void entryPointReachesTheDispatcherOnlyThroughPartialEdges() {
        String entries = scenario("StorageService").attributes().get("entryPoints");
        assertTrue(entries.contains("ShippingJob.main"), entries);
        assertTrue(entries.contains("[main, VERIFIED]") || entries.contains("[main, PARTIAL]"), entries);
    }

    @Test
    void scenarioCarriesItsMethodsRulesAndOutputFields() {
        Entity shipped = scenario("DispatchService");
        assertTrue(shipped.attributes().get("methods").contains("DispatchService.handle"));
        assertTrue(shipped.attributes().get("methods").contains("FeeCalculator.feeFor"), "callees inside the sources belong to it");
        assertTrue(shipped.attributes().get("rules").contains("FeeCalculator#feeFor"));
        assertEquals("shipmentId, status, quantity, fee, adjustment", shipped.attributes().get("outputFields"));
        assertTrue(shipped.attributes().get("columnsRead").contains("shipment.rate_pct"));
        assertFalse(scenario("ArchiveService").attributes().get("columnsRead").contains("rate_pct"),
                "archive never reads the rate");
    }

    @Test
    void partOfScenarioEdgesLinkMethodsRulesAndTraces() {
        EntityId shipped = scenario("DispatchService").id();
        assertTrue(model.incoming(shipped, RelationType.PART_OF_SCENARIO).stream()
                .anyMatch(r -> r.from().value().contains("DispatchService#handle")));
        assertTrue(model.incoming(shipped, RelationType.PART_OF_SCENARIO).stream()
                .anyMatch(r -> r.from().type() == EntityType.TRANSFORMATION));
        assertTrue(model.incoming(shipped, RelationType.PART_OF_SCENARIO).stream()
                .anyMatch(r -> r.from().type() == EntityType.BUSINESS_RULE && r.from().value().contains("ScenarioRouter")),
                "the dispatching case is the trigger rule");
    }

    @Test
    void sameFieldDerivedDifferentlyPerScenarioIsFlaggedNotMerged() {
        Entity adjustment = model.entitiesOfType(EntityType.OUTPUT_FIELD).stream()
                .filter(f -> f.name().equals("adjustment")).findFirst().orElseThrow();
        assertEquals("true", adjustment.attributes().get("derivationDiffersByScenario"));
        String derivations = adjustment.attributes().get("scenarioDerivations");
        assertTrue(derivations.contains("StorageService: ") && derivations.contains("runtime input"));
        assertTrue(derivations.contains("DispatchService: ") && derivations.contains("constant"));
    }

    @Test
    void everyScenarioHasEvidenceAndNoProvenanceGaps() {
        for (Entity s : model.entitiesOfType(EntityType.SCENARIO)) {
            assertFalse(s.evidence().isEmpty(), s.id().toString());
        }
        assertTrue(model.findProvenanceGaps().isEmpty(), model.findProvenanceGaps().toString());
    }

    @Test
    void emptyModelGivesAnInfoWarningOnly() {
        AnalysisModel empty = new AnalysisModel();
        new ScenarioStage().run(new StageContext(new AnalysisConfig(List.of("x"), "out", 21, List.of(), false), empty));
        assertTrue(empty.entities().isEmpty());
        assertEquals(AnalysisWarning.Severity.INFO, empty.warnings().get(0).severity());
    }
}
