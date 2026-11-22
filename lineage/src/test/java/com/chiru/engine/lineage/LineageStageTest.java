package com.chiru.engine.lineage;

import static org.junit.jupiter.api.Assertions.*;

import com.chiru.engine.core.AnalysisConfig;
import com.chiru.engine.core.Pipeline;
import com.chiru.engine.core.StageContext;
import com.chiru.engine.discovery.DiscoveryStage;
import com.chiru.engine.model.*;
import com.chiru.engine.rules.RulesStage;
import com.chiru.engine.spoon.SpoonSourceStage;
import com.chiru.engine.sql.SqlStage;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class LineageStageTest {

    private static final Path SAMPLE = Path.of("..", "sample-app").toAbsolutePath().normalize();
    private static AnalysisModel model;

    private static AnalysisModel analyse(LineageOptions options) {
        AnalysisModel m = new AnalysisModel();
        AnalysisConfig cfg = new AnalysisConfig(List.of(SAMPLE.toString()), "out", 21, List.of(), false);
        Pipeline.Result r = new Pipeline(List.of(new DiscoveryStage(), new SpoonSourceStage(), new SqlStage(),
                new RulesStage(), new BindingsStage(), new LineageStage(options))).run(new StageContext(cfg, m));
        assertTrue(r.succeeded(), r.toString());
        return m;
    }

    @BeforeAll
    static void analyseSample() {
        model = analyse(LineageOptions.defaults());
    }

    private static Entity field(AnalysisModel m, String name) {
        List<Entity> found = m.entitiesOfType(EntityType.OUTPUT_FIELD).stream().filter(e -> e.name().equals(name)).toList();
        assertEquals(1, found.size(), name);
        return found.get(0);
    }

    private static List<Entity> traces(AnalysisModel m, Entity field) {
        return m.outgoing(field.id(), RelationType.DERIVED_FROM).stream().map(r -> m.entity(r.to()).orElseThrow())
                .filter(e -> e.type() == EntityType.TRANSFORMATION).toList();
    }

    private static List<Entity> traces(Entity field, String scenario) {
        return traces(model, field).stream().filter(t -> scenario.equals(t.attributes().get("scenario"))).toList();
    }

    @Test
    void findsTheFileWriterAndItsLayout() {
        List<Entity> files = model.entitiesOfType(EntityType.OUTPUT_FILE);
        assertEquals(1, files.size());
        Map<String, String> a = files.get(0).attributes();
        assertTrue(a.get("writerMethod").contains("ManifestWriter#write("));
        assertTrue(a.get("layoutMethod").contains("ManifestWriter#format("));
        assertEquals("java.nio.file.Files#write", a.get("sink"));
        assertEquals("5", a.get("fieldCount"));
        assertEquals(TraceStatus.VERIFIED, model.incoming(files.get(0).id(), RelationType.PRODUCES).get(0).status());
    }

    @Test
    void fieldsComeInLayoutOrderWithTheDocumentedPositions() {
        // ManifestWriter's Javadoc: shipmentId 1-10, status 11-18, quantity 19-33, fee 34-43, adjustment 44-53
        String[][] expected = {{"shipmentId", "1", "10"}, {"status", "11", "18"}, {"quantity", "19", "33"},
                {"fee", "34", "43"}, {"adjustment", "44", "53"}};
        List<Entity> fields = model.entitiesOfType(EntityType.OUTPUT_FIELD);
        assertEquals(expected.length, fields.size());
        for (int i = 0; i < expected.length; i++) {
            Entity f = fields.get(i);
            assertEquals(expected[i][0], f.name());
            assertEquals(String.valueOf(i + 1), f.attributes().get("index"));
            assertEquals(expected[i][1], f.attributes().get("start"), f.name());
            assertEquals(expected[i][2], f.attributes().get("end"), f.name());
            assertTrue(f.attributes().get("widthSource").contains("integer literal"), "the heuristic is stated");
        }
    }

    @Test
    void shipmentIdTracesOnlyToTheIdColumn() {
        Entity f = field(model, "shipmentId");
        assertEquals("shipment.id", f.attributes().get("columns"), "no other column may leak in through shared helpers");
        assertEquals("VERIFIED", f.attributes().get("traceStatus"));
        assertEquals(4, traces(model, f).size(), "one trace per handler path: shipped, storage and the two returns of archive, each under its own rule");
        assertTrue(traces(model, f).stream().allMatch(t -> t.attributes().get("terminalKind").equals("column")));
    }

    @Test
    void quantityFollowsTheStorageRuleBackToTheColumns() {
        Entity f = field(model, "quantity");
        assertFalse(f.attributes().get("columns").contains("shipment.id"));
        List<Entity> storage = traces(f, "StorageService");
        assertTrue(storage.stream().anyMatch(t -> "shipment.rate_pct".equals(t.attributes().get("terminal"))
                && t.attributes().get("rules").contains("StorageService#handle")), "surcharge uses the rate column");
        Entity viaColumn = storage.stream().filter(t -> "shipment.quantity".equals(t.attributes().get("terminal"))).findFirst().orElseThrow();
        assertEquals("VERIFIED", viaColumn.attributes().get("status"));
        assertEquals(TraceStatus.VERIFIED, model.outgoing(viaColumn.id(), RelationType.DERIVED_FROM).stream()
                .filter(r -> r.to().equals(EntityId.of(EntityType.COLUMN, "shipment.quantity"))).findFirst().orElseThrow().status());
        assertTrue(viaColumn.attributes().get("steps").contains("StorageService.java"), "steps carry file evidence");
    }

    @Test
    void feeCapRuleIsOnTheDispatchPath() {
        Entity f = field(model, "fee");
        List<Entity> shipped = traces(f, "DispatchService");
        assertTrue(shipped.stream().anyMatch(t -> "shipment.rate_pct".equals(t.attributes().get("terminal"))
                && t.attributes().get("rules").contains(":if#then")), "the cap and charge conditions apply");
        assertTrue(f.attributes().get("constants").contains("FEE_CAP = "));
        assertTrue(traces(f, "ArchiveService").stream().allMatch(t -> t.attributes().get("terminalKind").equals("constant")));
    }

    @Test
    void statusKeepsLiteralsPerScenarioAndTheTernaryRules() {
        Entity f = field(model, "status");
        assertTrue(f.attributes().get("constants").contains("StorageService: \"STORED\""));
        assertTrue(traces(f, "ArchiveService").stream().anyMatch(t -> "\"ARCHIVED\"".equals(t.attributes().get("terminal"))));
        assertTrue(traces(f, "DispatchService").stream().anyMatch(t -> t.attributes().get("rules").contains("ternary#then")));
    }

    @Test
    void runtimePropertyEndsUnresolvedAndNeverVerified() {
        Entity f = field(model, "adjustment");
        Entity runtime = traces(f, "StorageService").stream()
                .filter(t -> t.attributes().get("terminalKind").equals("runtime input")).findFirst().orElseThrow();
        assertEquals("UNRESOLVED", runtime.attributes().get("status"));
        assertTrue(runtime.attributes().get("terminal").contains("System.getProperty"));
        assertEquals("PARTIAL", f.attributes().get("traceStatus"), "some scenarios are traced, one is not");
        assertTrue(f.attributes().get("gaps").contains("runtime input"));
        assertTrue(traces(f, "DispatchService").stream().allMatch(t -> t.attributes().get("terminalKind").equals("constant")));
        assertNotEquals("VERIFIED", f.attributes().get("traceStatus"));
        assertEquals(TraceStatus.UNRESOLVED, model.outgoing(f.id(), RelationType.DERIVED_FROM).stream()
                .filter(r -> r.to().equals(runtime.id())).findFirst().orElseThrow().status());
    }

    @Test
    void layoutConstantsAreNotReportedAsDataSources() {
        // widths (10, 15, ...) and the pad character reach the helpers only as covered arguments, so they never
        // become a source of any field
        for (Entity t : model.entitiesOfType(EntityType.TRANSFORMATION)) {
            assertNotEquals("15", t.attributes().get("terminal"));
            assertNotEquals("10", t.attributes().get("terminal"));
            assertNotEquals("'0'", t.attributes().get("terminal"));
        }
    }

    @Test
    void everyTraceHasEvidenceAndTheModelHasNoProvenanceGaps() {
        assertFalse(model.entitiesOfType(EntityType.TRANSFORMATION).isEmpty());
        for (Entity t : model.entitiesOfType(EntityType.TRANSFORMATION)) {
            assertFalse(t.evidence().isEmpty(), t.id().toString());
            assertTrue(t.attributes().get("steps").contains(" @"), "each step names its file and line");
        }
        assertTrue(model.findProvenanceGaps().isEmpty(), model.findProvenanceGaps().toString());
    }

    @Test
    void pureLibraryListDecidesWhetherBigDecimalCallsKeepATraceVerified() {
        AnalysisModel strict = analyse(new LineageOptions(List.of(), 80, 2000));
        Entity quantity = field(strict, "quantity");
        assertNotEquals("VERIFIED", quantity.attributes().get("traceStatus"));
        assertTrue(traces(strict, quantity).stream().anyMatch(t -> t.attributes().get("terminalKind").equals("external call")
                && t.attributes().get("status").equals("PARTIAL")), "library calls outside the list stay PARTIAL");
    }

    @Test
    void capsAreReportedNeverHidden() {
        AnalysisModel capped = analyse(new LineageOptions(LineageOptions.defaults().pureLibraryPrefixes(), 80, 3));
        assertTrue(capped.warnings().stream().anyMatch(w -> w.stage().equals("lineage")
                && w.severity() == AnalysisWarning.Severity.WARNING && w.message().contains("path cap")));
        AnalysisModel shallow = analyse(new LineageOptions(LineageOptions.defaults().pureLibraryPrefixes(), 4, 2000));
        assertTrue(shallow.warnings().stream().anyMatch(w -> w.stage().equals("lineage")
                && w.severity() == AnalysisWarning.Severity.WARNING && w.message().contains("depth cap")));
    }

    @Test
    void noLayoutMeansAnInfoWarningAndNothingElse() {
        AnalysisModel empty = new AnalysisModel();
        new LineageStage().run(new StageContext(new AnalysisConfig(List.of(SAMPLE.toString()), "out", 21, List.of(), false), empty));
        assertTrue(empty.entities().isEmpty());
        assertTrue(empty.warnings().stream().anyMatch(w -> w.severity() == AnalysisWarning.Severity.INFO
                && w.message().contains("no fixed-width output layout")));
    }

    @Test
    void idsAreStableAcrossRuns() {
        AnalysisModel again = analyse(LineageOptions.defaults());
        for (EntityType type : List.of(EntityType.OUTPUT_FILE, EntityType.OUTPUT_FIELD, EntityType.TRANSFORMATION)) {
            assertEquals(model.entitiesOfType(type).stream().map(Entity::id).toList(),
                    again.entitiesOfType(type).stream().map(Entity::id).toList(), type.toString());
        }
    }
}
