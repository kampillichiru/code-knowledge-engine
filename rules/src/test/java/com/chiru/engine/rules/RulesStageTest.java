package com.chiru.engine.rules;

import static org.junit.jupiter.api.Assertions.*;

import com.chiru.engine.core.AnalysisConfig;
import com.chiru.engine.core.Pipeline;
import com.chiru.engine.core.StageContext;
import com.chiru.engine.discovery.DiscoveryStage;
import com.chiru.engine.model.*;
import com.chiru.engine.spoon.SpoonSourceStage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RulesStageTest {

    private static final Path SAMPLE = Path.of("..", "sample-app").toAbsolutePath().normalize();
    private static final String PKG = "com.sample.shipping.";
    private static AnalysisModel model;

    @BeforeAll
    static void analyseSample() {
        model = new AnalysisModel();
        AnalysisConfig cfg = new AnalysisConfig(List.of(SAMPLE.toString()), "out", 21, List.of(), false);
        Pipeline.Result r = new Pipeline(List.of(new DiscoveryStage(), new SpoonSourceStage(), new RulesStage()))
                .run(new StageContext(cfg, model));
        assertTrue(r.succeeded(), r.toString());
    }

    private static List<Entity> rulesIn(String simpleClass) {
        return model.entitiesOfType(EntityType.BUSINESS_RULE).stream()
                .filter(e -> e.attributes().get("method").startsWith("method:" + PKG + simpleClass + "#")).toList();
    }

    private static Entity ruleWith(String simpleClass, String kind, String effectPart) {
        List<Entity> found = rulesIn(simpleClass).stream().filter(e -> kind.equals(e.attributes().get("kind")))
                .filter(e -> e.attributes().get("effects").contains(effectPart)).toList();
        assertEquals(1, found.size(), kind + " rule with effect '" + effectPart + "' in " + simpleClass
                + " among " + rulesIn(simpleClass).stream().map(Entity::name).toList());
        return found.get(0);
    }

    private static int lineOf(String file, String text) throws Exception {
        List<String> lines = Files.readAllLines(SAMPLE.resolve("src/main/java/com/sample/shipping/" + file));
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(text)) return i + 1;
        }
        throw new AssertionError(text + " not found in " + file);
    }

    @Test
    void nestedOverdueRuleCarriesTheWholeConditionChain() throws Exception {
        Entity penalty = ruleWith("StorageService", "conditional", "1.5");
        String conditions = penalty.attributes().get("conditions");
        assertTrue(conditions.contains("isBefore"), conditions);
        assertTrue(conditions.contains("LARGE_QUANTITY"), conditions);
        assertTrue(conditions.indexOf("isBefore") < conditions.indexOf("LARGE_QUANTITY"), "outermost condition first");
        assertTrue(conditions.contains(" AND "));
        assertTrue(penalty.attributes().get("constants").contains("LARGE_QUANTITY = "));
        assertTrue(penalty.attributes().get("constants").contains("1000"));
        assertEquals(2, model.outgoing(penalty.id(), RelationType.GUARDED_BY).size());
        int effectLine = lineOf("StorageService.java", "surcharge = surcharge.multiply");
        assertTrue(penalty.evidence().stream().anyMatch(ev -> ev.location().startLine() == effectLine));
        assertEquals("VERIFIED", penalty.attributes().get("traceStatus"),
                "BigDecimal and java.time calls only transform their inputs (pure-library list)");
    }

    @Test
    void guardsNameEveryEnclosingArmInnermostFirst() {
        Entity penalty = ruleWith("StorageService", "conditional", "1.5");
        String[] guards = penalty.attributes().get("guards").split(" ;; ");
        assertEquals(2, guards.length, "the inner large-quantity arm and the outer overdue arm");
        assertTrue(guards[0].startsWith("branch:") && guards[0].endsWith("#then") && guards[0].contains(":if#"));
        assertTrue(guards[1].endsWith("#then"));
        assertNotEquals(guards[0], guards[1]);
        Entity flat = ruleWith("StorageService", "conditional", "1.00");
        assertTrue(flat.attributes().get("guards").split(" ;; ")[0].endsWith("#else"));
        assertNull(ruleWith("StorageService", "computation", "surcharge = ").attributes().get("guards"),
                "an unconditional rule has no guard");
    }

    @Test
    void onlyCallsOutsideThePureListMakeARulePartial() {
        List<Entity> repository = rulesIn("ShipmentRepository");
        assertFalse(repository.isEmpty());
        assertTrue(repository.stream().allMatch(r -> "PARTIAL".equals(r.attributes().get("traceStatus"))),
                "JDBC calls (java.sql) are not on the pure list");

        AnalysisModel strict = new AnalysisModel();
        AnalysisConfig cfg = new AnalysisConfig(List.of(SAMPLE.toString()), "out", 21, List.of(), false);
        assertTrue(new Pipeline(List.of(new DiscoveryStage(), new SpoonSourceStage(), new RulesStage(List.of())))
                .run(new StageContext(cfg, strict)).succeeded());
        Entity penalty = strict.entitiesOfType(EntityType.BUSINESS_RULE).stream()
                .filter(r -> r.attributes().get("method").contains("StorageService#handle")
                        && r.attributes().get("effects").contains("1.5")).findFirst().orElseThrow();
        assertEquals("PARTIAL", penalty.attributes().get("traceStatus"), "with an empty pure list every library call counts");
    }

    @Test
    void elseArmIsANegatedConditionOfItsOwn() {
        Entity flat = ruleWith("StorageService", "conditional", "1.00");
        assertTrue(flat.attributes().get("conditions").contains("not ("), flat.attributes().get("conditions"));
        assertTrue(flat.attributes().get("conditions").contains("LARGE_QUANTITY"));
    }

    @Test
    void unconditionalComputationIsAComputationRule() {
        Entity surcharge = ruleWith("StorageService", "computation", "surcharge = ");
        assertTrue(surcharge.attributes().get("literals").contains("1200"));
        assertNull(surcharge.attributes().get("conditions"));
        Entity fee = ruleWith("FeeCalculator", "computation", "fee = ");
        assertTrue(fee.attributes().get("constants").contains("BASE_FEE = "));
    }

    @Test
    void feeCapIsAConditionalRuleWithItsConstant() {
        Entity cap = ruleWith("FeeCalculator", "conditional", "fee = ");
        assertTrue(cap.attributes().get("conditions").contains("FEE_CAP"));
        assertTrue(cap.attributes().get("constants").contains("FEE_CAP = "));
        assertTrue(cap.attributes().get("constants").contains("25.00"));
    }

    @Test
    void ternaryGivesOneRulePerArm() {
        Entity paid = ruleWith("DispatchService", "conditional", "status = \"SENT\"");
        Entity partial = ruleWith("DispatchService", "conditional", "status = \"PARTIAL\"");
        assertNotEquals(paid.id(), partial.id());
        assertFalse(paid.attributes().get("conditions").contains("not ("));
        assertTrue(partial.attributes().get("conditions").startsWith("not ("));
    }

    @Test
    void ifBlockWithSeveralEffectsIsOneRule() {
        Entity charge = ruleWith("DispatchService", "conditional", "fee = ");
        assertTrue(charge.attributes().get("effects").contains("newQuantity = "), charge.attributes().get("effects"));
        assertTrue(charge.attributes().get("conditions").contains("signum"));
    }

    @Test
    void earlyReturnInAnIfArmIsARule() {
        Entity closed = ruleWith("ArchiveService", "conditional", "ARCHIVED");
        assertTrue(closed.attributes().get("effects").startsWith("return "));
    }

    @Test
    void switchCaseArmsAreRules() {
        List<Entity> routes = rulesIn("ScenarioRouter");
        assertEquals(3, routes.size(), routes.stream().map(Entity::name).toList().toString());
        assertTrue(routes.stream().anyMatch(r -> r.attributes().get("conditions").contains("\"STORAGE\"")));
        assertTrue(routes.stream().allMatch(r -> r.attributes().get("conditions").contains("getStatus")));
    }

    @Test
    void valueFromRuntimePropertyIsNotARule() {
        assertTrue(rulesIn("ExternalRateClient").isEmpty(), "no operators, constants or conditions to report");
    }

    @Test
    void everyRuleIsLinkedAndHasEvidence() {
        assertFalse(model.entitiesOfType(EntityType.BUSINESS_RULE).isEmpty());
        for (Entity rule : model.entitiesOfType(EntityType.BUSINESS_RULE)) {
            assertFalse(rule.evidence().isEmpty(), rule.id().toString());
            assertTrue(rule.attributes().containsKey("traceStatus"));
            assertEquals(1, model.incoming(rule.id(), RelationType.APPLIES_RULE).size(), rule.id().toString());
            assertFalse(model.outgoing(rule.id(), RelationType.DERIVED_FROM).isEmpty(), rule.id().toString());
        }
        assertTrue(model.findProvenanceGaps().isEmpty(), model.findProvenanceGaps().toString());
    }

    @Test
    void ruleIdsAreStable() {
        AnalysisModel again = new AnalysisModel();
        AnalysisConfig cfg = new AnalysisConfig(List.of(SAMPLE.toString()), "out", 21, List.of(), false);
        assertTrue(new Pipeline(List.of(new DiscoveryStage(), new SpoonSourceStage(), new RulesStage()))
                .run(new StageContext(cfg, again)).succeeded());
        assertEquals(model.entitiesOfType(EntityType.BUSINESS_RULE).stream().map(Entity::id).toList(),
                again.entitiesOfType(EntityType.BUSINESS_RULE).stream().map(Entity::id).toList());
    }
}
