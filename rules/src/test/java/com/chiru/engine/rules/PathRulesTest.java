package com.chiru.engine.rules;

import static org.junit.jupiter.api.Assertions.*;

import com.chiru.engine.core.AnalysisConfig;
import com.chiru.engine.core.Pipeline;
import com.chiru.engine.core.StageContext;
import com.chiru.engine.discovery.DiscoveryStage;
import com.chiru.engine.model.*;
import com.chiru.engine.paths.PathStage;
import com.chiru.engine.spoon.SpoonSourceStage;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Rules taken from the path model: each effect carries the conditions of the paths it is on. */
class PathRulesTest {

    private static final Path SAMPLE = Path.of("..", "sample-app").toAbsolutePath().normalize();
    private static AnalysisModel model;

    @BeforeAll
    static void analyse() {
        model = new AnalysisModel();
        AnalysisConfig cfg = new AnalysisConfig(List.of(SAMPLE.toString()), "out", 21, List.of(), false);
        Pipeline.Result r = new Pipeline(List.of(new DiscoveryStage(), new SpoonSourceStage(), new PathStage(), new RulesStage()))
                .run(new StageContext(cfg, model));
        assertTrue(r.succeeded(), r.toString());
    }

    private static List<Entity> rulesIn(String simpleClass) {
        return model.entitiesOfType(EntityType.BUSINESS_RULE).stream()
                .filter(e -> e.attributes().get("method").startsWith("method:com.sample.shipping." + simpleClass + "#")).toList();
    }

    @Test
    void archiveRejectionIsConditionedOnTheQuantityNotBeingZero() {
        List<Entity> rules = rulesIn("ArchiveService");
        Entity rejected = rules.stream().filter(e -> e.attributes().get("effects").contains("\"REJECTED\"")).findFirst().orElseThrow();
        assertEquals("conditional", rejected.attributes().get("kind"));
        assertTrue(rejected.attributes().get("conditions").contains("not (shipment.getQuantity().signum() == 0)"),
                rejected.attributes().get("conditions"));
        Entity closed = rules.stream().filter(e -> e.attributes().get("effects").contains("\"ARCHIVED\"")).findFirst().orElseThrow();
        assertFalse(closed.attributes().get("conditions").contains("not ("), closed.attributes().get("conditions"));
    }

    @Test
    void conditionalRulesNameTheDecisionOutcomesTheyCover() {
        List<Entity> conditional = model.entitiesOfType(EntityType.BUSINESS_RULE).stream()
                .filter(e -> e.attributes().get("kind").equals("conditional") && e.id().value().contains("#")).toList();
        assertFalse(conditional.isEmpty());
        long withOutcomes = model.entitiesOfType(EntityType.BUSINESS_RULE).stream()
                .filter(e -> e.attributes().containsKey("outcomes")).count();
        assertTrue(withOutcomes > 0, "path-derived rules record the outcomes they cover");
    }

    @Test
    void everyPathRuleIsAppliedByItsMethodAndDerivedFromAnExpression() {
        ModelIndex index = new ModelIndex(model);
        for (Entity r : model.entitiesOfType(EntityType.BUSINESS_RULE)) {
            assertFalse(index.in(r.id(), RelationType.APPLIES_RULE).isEmpty(), r.name());
            assertFalse(index.out(r.id(), RelationType.DERIVED_FROM).isEmpty(), r.name());
        }
    }
}
