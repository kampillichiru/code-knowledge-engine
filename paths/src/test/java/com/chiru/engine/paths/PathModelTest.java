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
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The path model on the conformance probe and on sample-app, against hand-derived golden paths. */
class PathModelTest {

    private static final Path CONFORMANCE = Path.of("..", "conformance").toAbsolutePath().normalize();
    private static final Path SAMPLE = Path.of("..", "sample-app").toAbsolutePath().normalize();
    private static AnalysisModel probe;
    private static AnalysisModel sample;

    private static AnalysisModel analyse(Path source, PathOptions options) {
        AnalysisModel model = new AnalysisModel();
        Pipeline.Result r = new Pipeline(List.of(new DiscoveryStage(), new SpoonSourceStage(), new PathStage(options)))
                .run(new StageContext(new AnalysisConfig(List.of(source.toString()), "out", 21, List.of(), false), model));
        assertTrue(r.succeeded(), r.toString());
        return model;
    }

    @BeforeAll
    static void analyseBoth() {
        probe = analyse(CONFORMANCE.resolve("probe"), PathOptions.defaults());
        sample = analyse(SAMPLE, PathOptions.defaults());
    }

    /** The paths of the method with this simple name, in order, as {@code n | when | then | exit}. */
    private static List<String> paths(AnalysisModel model, String owner, String method) {
        return model.entitiesOfType(EntityType.PATH).stream()
                .filter(p -> p.attributes().get("method").contains(owner + "#" + method + "("))
                .sorted(Comparator.comparingInt(p -> Integer.parseInt(p.attributes().get("number"))))
                .map(p -> p.attributes().get("number") + " | " + p.attributes().get("when") + " | "
                        + (p.attributes().get("then").equals("nothing") ? "-" : p.attributes().get("then")) + " | "
                        + (p.attributes().get("exit").equals("FALL") ? "FALL"
                        : p.attributes().get("exit") + (p.attributes().containsKey("value") ? " " + p.attributes().get("value") : "")))
                .toList();
    }

    // ---------------------------------------------------------------- golden paths

    @Test
    void probePathsMatchTheHandDerivedGoldenFile() throws IOException {
        Map<String, List<String>> expected = new LinkedHashMap<>();
        String current = null;
        for (String line : Files.readAllLines(CONFORMANCE.resolve("golden/probe-paths.txt"))) {
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
        assertFalse(expected.isEmpty());
        for (Map.Entry<String, List<String>> e : expected.entrySet()) {
            assertEquals(e.getValue(), paths(probe, "probe.Probe", e.getKey()), "paths of " + e.getKey());
        }
    }

    @Test
    void aGuardClauseNeverLeavesTheRestOfTheMethodUnconditional() {
        List<String> rows = paths(probe, "probe.Probe", "earlyReturn");
        assertEquals(2, rows.size());
        assertTrue(rows.get(1).contains("not (x < 0)") && rows.get(1).contains("x = x + 5"),
                "x = x + 5 runs only when x >= 0; the old rules reported it as unconditional");
    }

    @Test
    void loopsAreZeroOrAtLeastOnePassAndEveryCombinationIsAPath() {
        assertEquals(16, paths(probe, "probe.Probe", "loops").size(), "four sequential loops: 2^4");
        assertEquals(2, paths(probe, "probe.Probe", "varargs").size());
    }

    @Test
    void labeledBreakAndContinueEndThePassOrTheLoops() {
        List<String> rows = paths(probe, "probe.Probe", "labeled");
        assertEquals(5, rows.size());
        assertTrue(rows.stream().anyMatch(r -> r.contains("v < 0") && r.endsWith("RETURN c") && !r.contains("c = c + v")),
                "break outer leaves both loops without adding v");
        assertTrue(rows.stream().anyMatch(r -> r.contains("not (v < 0) AND v == 0") && !r.contains("c = c + v")),
                "continue skips the addition");
        assertTrue(rows.stream().anyMatch(r -> r.contains("not (v < 0) AND not (v == 0)") && r.contains("c = c + v")));
    }

    @Test
    void anonymousClassMethodsHaveTheirOwnPaths() {
        assertEquals(2, paths(probe, "probe.Probe$1", "apply").size());
    }

    @Test
    void expressionLevelDecisionsAreCountedButDoNotSplitPaths() {
        assertTrue(paths(probe, "probe.Probe", "ternary").isEmpty(), "a ternary inside an expression does not split a method");
        long ternary = probe.entitiesOfType(EntityType.PATH_DECISION).stream().filter(d -> d.attributes().get("kind").equals("TERNARY")).count();
        assertEquals(1, ternary);
        assertEquals("true", probe.entitiesOfType(EntityType.PATH_DECISION).stream()
                .filter(d -> d.attributes().get("kind").equals("TERNARY")).findFirst().orElseThrow().attributes().get("exprLevel"));
        assertEquals(2, paths(probe, "probe.Probe", "logical").size(), "&& and || count as decisions but only the if splits");
    }

    // ---------------------------------------------------------------- sample-app: the paths the old rules could not show

    @Test
    void archiveRejectionIsAConditionedPathNotAnUnconditionalFact() {
        List<String> rows = paths(sample, "com.sample.shipping.ArchiveService", "handle");
        assertEquals(2, rows.size());
        assertTrue(rows.get(0).contains("shipment.getQuantity().signum() == 0") && rows.get(0).contains("RETURN new ManifestRecord("), rows.get(0));
        assertTrue(rows.get(0).contains("\"ARCHIVED\""));
        assertTrue(rows.get(1).contains("not (shipment.getQuantity().signum() == 0)") && rows.get(1).contains("\"REJECTED\""),
                "REJECTED happens only when the quantity is not zero: " + rows.get(1));
    }

    @Test
    void feeCapAndTheThrowingDefaultOfTheRouterAreVisible() {
        List<String> fee = paths(sample, "com.sample.shipping.FeeCalculator", "feeFor");
        assertEquals(2, fee.size());
        assertTrue(fee.get(0).contains("fee.compareTo(FeeCalculator.FEE_CAP) > 0") && fee.get(0).contains("fee = FeeCalculator.FEE_CAP"));
        assertTrue(fee.get(1).startsWith("2 | not (fee.compareTo"));

        List<String> route = paths(sample, "com.sample.shipping.ScenarioRouter", "route");
        assertEquals(4, route.size(), "three cases and the default");
        assertTrue(route.get(3).contains("matches no other case") && route.get(3).contains("THROW new IllegalArgumentException("),
                "the default arm that only throws was not a rule before");
    }

    @Test
    void tryWithResourcesLoopsAndNestedIfsAreAllThere() {
        assertFalse(paths(sample, "com.sample.shipping.ShipmentRepository", "findDue").isEmpty());
        assertEquals(3, paths(sample, "com.sample.shipping.StorageService", "handle").size(),
                "overdue and a large quantity, overdue and not large, and not overdue");
    }

    // ---------------------------------------------------------------- identity with the decisions in the model

    @Test
    void everyDecisionIsInTheModelWithItsOutcomes() {
        Map<String, Integer> byKind = new TreeMap<>();
        for (Entity d : probe.entitiesOfType(EntityType.PATH_DECISION)) {
            byKind.merge(d.attributes().get("kind"), d.attributes().get("outcomes").split(" \\|\\| ").length, Integer::sum);
        }
        assertEquals(Map.of("ASSERT", 2, "IF", 18, "LAMBDA_PREDICATE", 2, "LOOP", 14, "PATTERN_GUARD", 2, "SHORT_CIRCUIT", 6, "SWITCH", 4,
                "SWITCH_EXPR", 7, "TERNARY", 2, "TRY", 2), new TreeMap<>(byKind), "every one of the 59 outcomes in the source");
        assertEquals(59, byKind.values().stream().mapToInt(Integer::intValue).sum());
    }

    // ---------------------------------------------------------------- the cap

    @Test
    void theCapCutsThePathsButNeverTheDecisions() {
        AnalysisModel capped = analyse(CONFORMANCE.resolve("probe"), new PathOptions(4));
        assertEquals(4, paths(capped, "probe.Probe", "loops").size());
        Entity method = capped.entitiesOfType(EntityType.METHOD).stream()
                .filter(m -> m.id().value().contains("Probe#loops(")).findFirst().orElseThrow();
        assertEquals("true", method.attributes().get("pathsCapped"));
        assertTrue(capped.warnings().stream().anyMatch(w -> w.stage().equals("paths") && w.severity() == AnalysisWarning.Severity.WARNING
                && w.message().contains("loops")), "a capped method is reported, not hidden");
        assertEquals(probe.entitiesOfType(EntityType.PATH_DECISION).size(), capped.entitiesOfType(EntityType.PATH_DECISION).size(),
                "all decisions are still present");
        assertThrows(IllegalArgumentException.class, () -> new PathOptions(0));
    }

    // ---------------------------------------------------------------- bookkeeping

    @Test
    void everyPathAndDecisionHasEvidenceAndAnOwningMethod() {
        for (EntityType type : List.of(EntityType.PATH, EntityType.PATH_DECISION)) {
            for (Entity e : probe.entitiesOfType(type)) {
                assertFalse(e.evidence().isEmpty(), e.id().toString());
                assertEquals(1, probe.incoming(e.id(), RelationType.CONTAINS).size(), e.id().toString());
            }
        }
        assertTrue(probe.findProvenanceGaps().isEmpty(), probe.findProvenanceGaps().toString());
    }

    @Test
    void whatTheModelDoesNotSplitIsStatedNotHidden() {
        String notes = probe.warnings().stream().filter(w -> w.stage().equals("paths")).map(AnalysisWarning::message)
                .collect(Collectors.joining("\n"));
        assertTrue(notes.contains("a switch expression used as a call argument or operand is not split into paths"), notes);
    }

    @Test
    void theModelIsDeterministic() {
        AnalysisModel again = analyse(CONFORMANCE.resolve("probe"), PathOptions.defaults());
        assertEquals(probe.entitiesOfType(EntityType.PATH).stream().map(Entity::id).toList(),
                again.entitiesOfType(EntityType.PATH).stream().map(Entity::id).toList());
        assertEquals(probe.entitiesOfType(EntityType.PATH).stream().map(e -> e.attributes().toString()).toList(),
                again.entitiesOfType(EntityType.PATH).stream().map(e -> e.attributes().toString()).toList());
    }
}
