package com.chiru.engine.lineage;

import static org.junit.jupiter.api.Assertions.*;

import com.chiru.engine.core.AnalysisConfig;
import com.chiru.engine.core.Pipeline;
import com.chiru.engine.core.StageContext;
import com.chiru.engine.discovery.DiscoveryStage;
import com.chiru.engine.model.*;
import com.chiru.engine.spoon.SpoonSourceStage;
import com.chiru.engine.sql.SqlStage;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BindingsStageTest {

    private static final Path SAMPLE = Path.of("..", "sample-app").toAbsolutePath().normalize();
    private static final String PKG = "com.sample.shipping.";
    private static final String SHIPMENT_CTOR = PKG + "Shipment#<init>(long,java.lang.String,java.math.BigDecimal,"
            + "java.math.BigDecimal,java.time.LocalDate)";
    private static AnalysisModel model;

    @BeforeAll
    static void analyseSample() {
        model = new AnalysisModel();
        AnalysisConfig cfg = new AnalysisConfig(List.of(SAMPLE.toString()), "out", 21, List.of(), false);
        Pipeline.Result r = new Pipeline(List.of(new DiscoveryStage(), new SpoonSourceStage(), new SqlStage(),
                new BindingsStage())).run(new StageContext(cfg, model));
        assertTrue(r.succeeded(), r.toString());
    }

    private static EntityId parameter(String name) {
        List<Entity> found = model.entitiesOfType(EntityType.VARIABLE).stream()
                .filter(e -> e.id().value().startsWith("variable:" + SHIPMENT_CTOR + "#" + name + "@")).toList();
        assertEquals(1, found.size(), name);
        return found.get(0).id();
    }

    private static Entity out(String label) {
        return model.entitiesOfType(EntityType.PARAMETER).stream()
                .filter(e -> "OUT".equals(e.attributes().get("direction")) && label.equals(e.attributes().get("label"))
                        && e.attributes().containsKey("flowsTo")).findFirst().orElseThrow();
    }

    @Test
    void sqlResultIsLinkedToTheConstructorParameterItFeeds() {
        EntityId quantity = parameter("quantity");
        Entity result = out("quantity");
        Relationship link = model.outgoing(quantity, RelationType.DERIVED_FROM).stream()
                .filter(r -> r.to().equals(result.id())).findFirst().orElseThrow();
        assertEquals(TraceStatus.VERIFIED, link.status());
        assertEquals(BindingsStage.NAME, link.evidence().get(0).extractor());
        assertTrue(link.evidence().get(0).location().file().endsWith("ShipmentRepository.java"));
    }

    @Test
    void everyShipmentParameterIsFedByItsResultColumn() {
        for (String name : List.of("id", "status", "quantity", "ratePct", "dueDate")) {
            long fromSql = model.outgoing(parameter(name), RelationType.DERIVED_FROM).stream()
                    .filter(r -> r.to().type() == EntityType.PARAMETER).count();
            assertEquals(1, fromSql, name);
        }
    }

    @Test
    void theChainReachesTheTableColumn() {
        Entity result = out("quantity");
        assertTrue(model.outgoing(result.id(), RelationType.DERIVED_FROM).stream()
                .anyMatch(r -> r.to().equals(EntityId.of(EntityType.COLUMN, "shipment.quantity"))
                        && r.status() == TraceStatus.VERIFIED));
    }

    @Test
    void constructorArgumentsAlsoReachTheParameterFromTheCallSite() {
        List<Relationship> fromArguments = model.outgoing(parameter("quantity"), RelationType.DERIVED_FROM).stream()
                .filter(r -> r.to().type() == EntityType.EXPRESSION).toList();
        assertEquals(1, fromArguments.size(), "new Shipment(...) in ShipmentRepository.findDue");
        assertEquals("rs.getBigDecimal(\"quantity\")",
                model.entity(fromArguments.get(0).to()).orElseThrow().attributes().get("text"));
    }

    @Test
    void stageReportsWhatItLinked() {
        assertTrue(model.warnings().stream().anyMatch(w -> w.stage().equals(BindingsStage.NAME)
                && w.severity() == AnalysisWarning.Severity.INFO && w.message().contains("linked")));
        assertTrue(model.findProvenanceGaps().isEmpty(), model.findProvenanceGaps().toString());
    }
}
