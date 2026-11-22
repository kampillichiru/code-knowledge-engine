package com.chiru.engine.paths;

import static org.junit.jupiter.api.Assertions.*;

import com.chiru.engine.core.AnalysisConfig;
import com.chiru.engine.core.Pipeline;
import com.chiru.engine.core.StageContext;
import com.chiru.engine.discovery.DiscoveryStage;
import com.chiru.engine.model.*;
import com.chiru.engine.spoon.SpoonSourceStage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Switch expressions with yield, pattern guards, lambdas, field initialisers and initialiser blocks, on
 * {@code conformance/probe2}, against hand-derived golden paths and a hand-counted number of outcomes.
 */
class Step3PathTest {

    private static final Path CONFORMANCE = Path.of("..", "conformance").toAbsolutePath().normalize();
    private static AnalysisModel model;

    @BeforeAll
    static void analyse() {
        model = new AnalysisModel();
        AnalysisConfig cfg = new AnalysisConfig(List.of(CONFORMANCE.resolve("probe2").toString()), "out", 21, List.of(), false);
        assertTrue(new Pipeline(List.of(new DiscoveryStage(), new SpoonSourceStage(), new PathStage())).run(new StageContext(cfg, model)).succeeded());
    }

    private static List<String> paths(String method) {
        return model.entitiesOfType(EntityType.PATH).stream()
                .filter(p -> p.attributes().get("method").contains("Step3#" + method + "(") && !p.attributes().containsKey("unit"))
                .sorted(Comparator.comparingInt(p -> Integer.parseInt(p.attributes().get("number"))))
                .map(p -> p.attributes().get("number") + " | " + p.attributes().get("when") + " | "
                        + (p.attributes().get("then").equals("nothing") ? "-" : p.attributes().get("then")) + " | "
                        + (p.attributes().get("exit").equals("FALL") ? "FALL"
                        : p.attributes().get("exit") + (p.attributes().containsKey("value") ? " " + p.attributes().get("value") : "")))
                .toList();
    }

    private static List<Entity> decisions(String kind) {
        return model.entitiesOfType(EntityType.PATH_DECISION).stream().filter(d -> d.attributes().get("kind").equals(kind)).toList();
    }

    @Test
    void pathsMatchTheHandDerivedGoldenFile() throws IOException {
        Map<String, List<String>> expected = new LinkedHashMap<>();
        String current = null;
        for (String line : Files.readAllLines(CONFORMANCE.resolve("golden/step3-paths.txt"))) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            if (Character.isDigit(line.charAt(0))) {
                expected.get(current).add(line.trim());
            } else {
                current = line.trim();
                expected.put(current, new ArrayList<>());
            }
        }
        assertEquals(3, expected.size());
        for (Map.Entry<String, List<String>> e : expected.entrySet()) {
            assertEquals(e.getValue(), paths(e.getKey()), "paths of " + e.getKey());
        }
    }

    @Test
    void aYieldEndsItsCaseSoTheLaterYieldIsOnlyReachedWhenTheIfFails() {
        List<String> rows = paths("viaSwitch");
        assertEquals(4, rows.size());
        assertTrue(rows.get(1).contains("r = 20") && !rows.get(1).contains("r = 30"), "yield 20 leaves the case: " + rows.get(1));
        assertTrue(rows.get(2).contains("not (k > 0)") && rows.get(2).contains("r = 30"));
    }

    @Test
    void aSwitchExpressionUsedAsAnArgumentIsCountedButNotSplit() {
        assertTrue(paths("nested").isEmpty(), "no PATH entities: the call is one flow");
        List<Entity> nested = decisions("SWITCH_EXPR").stream().filter(d -> d.attributes().get("method").contains("Step3#nested(")).toList();
        assertEquals(1, nested.size());
        assertEquals("true", nested.get(0).attributes().get("exprLevel"));
        assertEquals("1 || default", nested.get(0).attributes().get("outcomes"));
        assertEquals("true", nested.get(0).attributes().get("reachable"));
    }

    @Test
    void aPatternGuardIsADecisionAndPartOfItsCasesCondition() {
        List<Entity> guards = decisions("PATTERN_GUARD");
        assertEquals(1, guards.size());
        assertEquals("i > 1", guards.get(0).attributes().get("condition"));
        assertEquals("guard true || guard false", guards.get(0).attributes().get("outcomes"));
        assertTrue(paths("guardStmt").get(0).contains("when i > 1"));
        assertEquals(3, decisions("SWITCH").stream().filter(d -> d.attributes().get("method").contains("guardStmt")).findFirst().orElseThrow()
                .attributes().get("outcomes").split(" \\|\\| ").length, "a switch over patterns has no implicit default");
    }

    @Test
    void lambdasAreUnitsOfTheirOwn() {
        List<Entity> predicate = decisions("LAMBDA_PREDICATE");
        assertEquals(1, predicate.size());
        assertEquals("(v > 0) && (v < 100)", predicate.get(0).attributes().get("condition"));
        assertEquals("lambda at line 23", predicate.get(0).attributes().get("unit"));
        assertEquals("true || false", predicate.get(0).attributes().get("outcomes"));
        assertEquals(1, decisions("SHORT_CIRCUIT").stream().filter(d -> "lambda at line 23".equals(d.attributes().get("unit"))).count(),
                "the && inside the lambda belongs to the lambda");

        List<Entity> blockLambda = model.entitiesOfType(EntityType.PATH).stream()
                .filter(p -> "lambda at line 25".equals(p.attributes().get("unit"))).sorted(Comparator.comparing(p -> p.attributes().get("number"))).toList();
        assertEquals(2, blockLambda.size(), "the if inside the block lambda splits its paths");
        assertTrue(blockLambda.get(0).attributes().get("when").contains("counter > 3"));
    }

    @Test
    void initialisersAndInitialiserBlocksAreUnitsOfTheirClass() {
        EntityId step3 = EntityId.of(EntityType.CLASS, "probe2.Step3");
        List<Entity> owned = model.outgoing(step3, RelationType.CONTAINS).stream().map(r -> model.entity(r.to()).orElseThrow())
                .filter(e -> e.type() == EntityType.PATH_DECISION).toList();
        assertTrue(owned.stream().anyMatch(d -> d.attributes().get("kind").equals("TERNARY") && "mode initialiser".equals(d.attributes().get("unit"))),
                "the ternary in the field initialiser");
        assertTrue(owned.stream().anyMatch(d -> d.attributes().get("kind").equals("IF") && d.attributes().get("unit").startsWith("static initialiser block")));
        assertTrue(owned.stream().anyMatch(d -> d.attributes().get("kind").equals("LOOP") && d.attributes().get("unit").startsWith("instance initialiser block")));

        List<Entity> staticPaths = model.entitiesOfType(EntityType.PATH).stream()
                .filter(p -> p.attributes().getOrDefault("unit", "").startsWith("static initialiser block")).toList();
        assertEquals(2, staticPaths.size());
        assertEquals(List.of("counter > 5", "not (counter > 5)"), staticPaths.stream().map(p -> p.attributes().get("when")).sorted().toList());
    }

    @Test
    void everyOutcomeInTheFileIsInTheModel() {
        int outcomes = model.entitiesOfType(EntityType.PATH_DECISION).stream()
                .mapToInt(d -> d.attributes().get("outcomes").split(" \\|\\| ").length).sum();
        assertEquals(26, outcomes, "hand count in conformance/golden/step3-outcomes.txt");
        assertTrue(model.entitiesOfType(EntityType.PATH_DECISION).stream().allMatch(d -> "true".equals(d.attributes().get("reachable"))),
                "nothing in this fixture is dead code");
        assertTrue(model.findProvenanceGaps().isEmpty(), model.findProvenanceGaps().toString());
    }
}
