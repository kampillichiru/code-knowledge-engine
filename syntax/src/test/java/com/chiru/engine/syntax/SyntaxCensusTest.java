package com.chiru.engine.syntax;

import static org.junit.jupiter.api.Assertions.*;

import com.chiru.engine.core.AnalysisConfig;
import com.chiru.engine.core.Pipeline;
import com.chiru.engine.core.StageContext;
import com.chiru.engine.discovery.DiscoveryStage;
import com.chiru.engine.model.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SyntaxCensusTest {

    private static final Path CONFORMANCE = Path.of("..", "conformance").toAbsolutePath().normalize();
    private static final Path PROBE = CONFORMANCE.resolve("probe/src/probe/Probe.java");
    private static SyntaxCensus.FileResult probe;

    @BeforeAll
    static void census() throws IOException {
        probe = SyntaxCensus.analyse(Map.of(PROBE, "probe/Probe.java")).get(0);
    }

    private static List<SyntaxCensus.Unit> units(String owner, String name) {
        return probe.units().stream().filter(u -> u.owner().equals(owner) && u.name().equals(name)).toList();
    }

    private static SyntaxCensus.Unit unit(String name) {
        List<SyntaxCensus.Unit> found = units("probe.Probe", name);
        assertEquals(1, found.size(), name);
        return found.get(0);
    }

    private static List<String> kinds(SyntaxCensus.Unit u) {
        return u.statements().stream().map(SyntaxCensus.Statement::kind).toList();
    }

    private static SyntaxCensus.Decision decision(SyntaxCensus.Unit u, String kind) {
        return u.decisions().stream().filter(d -> d.kind().equals(kind)).findFirst().orElseThrow();
    }

    // ---------------------------------------------------------------- the golden numbers

    @Test
    void outcomesPerUnitMatchTheHandCountedGoldenFile() throws IOException {
        Map<String, Integer> expected = new TreeMap<>();
        for (String line : Files.readAllLines(CONFORMANCE.resolve("golden/probe-outcomes.txt"))) {
            if (!line.isBlank() && !line.startsWith("#")) {
                expected.put(line.substring(0, line.indexOf('=')), Integer.parseInt(line.substring(line.indexOf('=') + 1)));
            }
        }
        Map<String, Integer> actual = new TreeMap<>();
        probe.units().stream().filter(u -> u.outcomes() > 0)
                .forEach(u -> actual.merge(u.owner() + "#" + u.name(), u.outcomes(), Integer::sum));
        assertEquals(expected, actual);
        assertEquals(59, probe.units().stream().mapToInt(SyntaxCensus.Unit::outcomes).sum());
    }


    @Test
    void theStep3FixtureMatchesItsHandCountedGoldenFile() throws IOException {
        Path file = CONFORMANCE.resolve("probe2/src/probe2/Step3.java");
        SyntaxCensus.FileResult step3 = SyntaxCensus.analyse(Map.of(file, "probe2/Step3.java")).get(0);
        Map<String, Integer> expected = new TreeMap<>();
        for (String line : Files.readAllLines(CONFORMANCE.resolve("golden/step3-outcomes.txt"))) {
            if (!line.isBlank() && !line.startsWith("#")) {
                expected.put(line.substring(0, line.indexOf('=')), Integer.parseInt(line.substring(line.indexOf('=') + 1)));
            }
        }
        Map<String, Integer> actual = new TreeMap<>();
        step3.units().stream().filter(u -> u.outcomes() > 0).forEach(u -> actual.merge(u.owner() + "#" + u.name(), u.outcomes(), Integer::sum));
        assertEquals(expected, actual);
        assertEquals(26, step3.units().stream().mapToInt(SyntaxCensus.Unit::outcomes).sum());
        assertEquals(List.of(), step3.unaccountedLines());
        assertEquals(List.of(), step3.parseErrors());
    }

    @Test
    void everyCodeLineIsAccountedFor() {
        assertEquals(List.of(), probe.unaccountedLines());
        assertTrue(probe.codeLines() > 120, "the ledger counted real code lines: " + probe.codeLines());
        assertEquals(List.of(), probe.parseErrors());
    }

    // ---------------------------------------------------------------- what the engine's own extractor missed

    @Test
    void implicitElseGuardClausesAndThrowArmsAreDecisions() {
        assertEquals(List.of("then", "else"), decision(unit("implicitElse"), "IF").outcomes());
        assertEquals(List.of("then", "else"), decision(unit("earlyReturn"), "IF").outcomes());
        assertTrue(kinds(unit("throwsArm")).contains("THROW"));
        assertEquals(List.of("then", "else"), decision(unit("throwsArm"), "IF").outcomes());
    }

    @Test
    void staticBlockFieldInitialisersAssertAndSynchronizedAreCounted() {
        SyntaxCensus.Unit init = unit("<static-init>");
        assertEquals(List.of("ASSIGN"), kinds(init).stream().filter(k -> !k.equals("FIELD_INIT")).toList());
        assertTrue(probe.units().stream().anyMatch(u -> u.kind().equals("FIELD_INIT") && u.name().equals("total")),
                "total = compute(5) is code that runs");
        assertEquals(List.of("holds", "fails"), decision(unit("assertAndCast"), "ASSERT").outcomes());
        assertTrue(kinds(unit("sync")).contains("SYNC"));
    }

    @Test
    void incrementsLoopsAndTryCatchFinallyAreCounted() {
        assertTrue(kinds(unit("tryCatch")).containsAll(List.of("TRY", "CATCH", "FINALLY", "INCDEC", "RETURN")));
        assertEquals(List.of("normal", "catch NumberFormatException"), decision(unit("tryCatch"), "TRY").outcomes());
        SyntaxCensus.Unit loops = unit("loops");
        assertEquals(List.of("FOR", "WHILE", "FOREACH", "DO"), kinds(loops).stream()
                .filter(k -> Set.of("FOR", "WHILE", "FOREACH", "DO").contains(k)).toList());
        assertEquals(4, loops.decisions().stream().filter(d -> d.kind().equals("LOOP")).count());
        assertTrue(kinds(loops).stream().filter(k -> k.equals("INCDEC")).count() >= 2, "i++ and s++");
    }

    @Test
    void fallThroughLabelsAreEachAnOutcome() {
        assertEquals(List.of("1", "2", "3", "default"), decision(unit("switchOld"), "SWITCH").outcomes(),
                "case 2: case 3: shares a body but are two different outcomes");
    }

    @Test
    void switchExpressionsPatternLabelsAndGuardsAreModelled() {
        assertEquals(List.of("OPEN", "ARCHIVED"), decision(unit("switchNew"), "SWITCH_EXPR").outcomes());
        assertEquals(List.of("1", "default"), decision(unit("switchYield"), "SWITCH_EXPR").outcomes());
        assertTrue(kinds(unit("switchYield")).contains("YIELD"));
        SyntaxCensus.Unit pattern = unit("patternSwitch");
        assertEquals(List.of("pattern Integer i", "pattern String s", "default"), decision(pattern, "SWITCH_EXPR").outcomes());
        assertEquals("i > 5", decision(pattern, "PATTERN_GUARD").condition());
    }

    @Test
    void shortCircuitOperatorsAndTernariesAreDecisions() {
        assertEquals(2, unit("logical").decisions().stream().filter(d -> d.kind().equals("SHORT_CIRCUIT")).count(),
                "a > 0 && b > 0 || a < -5 has two operators");
        assertEquals(1, unit("instanceOfPattern").decisions().stream().filter(d -> d.kind().equals("SHORT_CIRCUIT")).count());
        assertEquals(List.of("then", "else"), decision(unit("ternary"), "TERNARY").outcomes());
    }

    @Test
    void lambdasAreUnitsAndABooleanBodyIsAPredicate() {
        List<SyntaxCensus.Unit> lambdas = units("probe.Probe", "<lambda>");
        assertEquals(2, lambdas.size(), "filter(v -> v > 2) and map(v -> v * 2); the method reference is not a lambda body");
        assertEquals(1, lambdas.stream().flatMap(u -> u.decisions().stream()).filter(d -> d.kind().equals("LAMBDA_PREDICATE")).count());
        assertEquals("v > 2", lambdas.stream().flatMap(u -> u.decisions().stream()).findFirst().orElseThrow().condition());
    }

    @Test
    void anonymousAndInnerClassesGetTheirOwnOwners() {
        assertEquals(1, units("probe.Probe$1", "apply").size(), "anonymous Rule implementation");
        assertEquals(1, units("probe.Probe$Inner", "get").size());
        assertEquals(1, units("probe.Probe$Status", "OPEN").size(), "enum constants are initialisers");
    }

    @Test
    void labeledBreakAndContinueAreStatements() {
        assertTrue(kinds(unit("labeled")).containsAll(List.of("LABEL", "BREAK", "CONTINUE")));
    }

    // ---------------------------------------------------------------- hard layouts and bad input

    @Test
    void awkwardLayoutsStillShipmentForEveryLine(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("Awk.java");
        Files.writeString(file, """
                package awk;
                /** doc */
                public class Awk
                {
                    /* block
                       comment */
                    @Override
                    public String toString()
                    {
                        int x = 1; // trailing
                        if (x > 0)
                            x = 2;
                        else
                            x = 3;
                        do
                        {
                            x++;
                        }
                        while (x < 5);
                        try
                        {
                            x = x / 0;
                        }
                        catch (ArithmeticException e)
                        {
                            x = 0;
                        }
                        finally
                        {
                            x = 9;
                        }
                        String t = \"\"\"
                            text
                            \"\"\";
                        Runnable r = () ->
                        {
                            if (t.isEmpty()) { return; }
                        };
                        return "" + x;
                    }
                    interface I { default int f() { return 1; } }
                    enum E { A { int g() { return 2; } }, B; int g() { return 0; } }
                    record R(int a) { R { if (a < 0) throw new IllegalArgumentException(); } }
                }
                """);
        SyntaxCensus.FileResult r = SyntaxCensus.analyse(Map.of(file, "Awk.java")).get(0);
        assertEquals(List.of(), r.parseErrors());
        assertEquals(List.of(), r.unaccountedLines(), "unaccounted: " + r.unaccountedLines());
        assertTrue(r.units().stream().flatMap(u -> u.decisions().stream()).anyMatch(d -> d.kind().equals("IF")));
    }

    @Test
    void aFileThatDoesNotParseIsReportedAndDoesNotStopTheOthers(@TempDir Path dir) throws IOException {
        Path broken = dir.resolve("Broken.java");
        Path fine = dir.resolve("Fine.java");
        Files.writeString(broken, "package x; public class Broken { void f( { } ");
        Files.writeString(fine, "package x; public class Fine { int g(int a) { if (a > 0) { return 1; } return 0; } }");
        Map<Path, String> files = new LinkedHashMap<>();
        files.put(broken, "Broken.java");
        files.put(fine, "Fine.java");
        List<SyntaxCensus.FileResult> results = SyntaxCensus.analyse(files);
        Map<String, SyntaxCensus.FileResult> byName = results.stream().collect(Collectors.toMap(SyntaxCensus.FileResult::file, r -> r));
        assertFalse(byName.get("Broken.java").parseErrors().isEmpty(), "the parse error must be reported, not swallowed");
        assertEquals(List.of(), byName.get("Fine.java").parseErrors());
        assertEquals(2, byName.get("Fine.java").units().stream().mapToInt(SyntaxCensus.Unit::outcomes).sum());
    }

    // ---------------------------------------------------------------- the stage

    @Test
    void stageWritesUnitsAndDecisionsIntoTheModel() {
        AnalysisModel model = new AnalysisModel();
        AnalysisConfig cfg = new AnalysisConfig(List.of(CONFORMANCE.resolve("probe").toString()), "out", 21, List.of(), false);
        Pipeline.Result r = new Pipeline(List.of(new DiscoveryStage(), new SyntaxStage())).run(new StageContext(cfg, model));
        assertTrue(r.succeeded(), r.toString());

        assertEquals(probe.units().size(), model.entitiesOfType(EntityType.SYNTAX_UNIT).size());
        int outcomes = model.entitiesOfType(EntityType.DECISION).stream()
                .mapToInt(d -> Integer.parseInt(d.attributes().get("outcomeCount"))).sum();
        assertEquals(59, outcomes);
        Entity file = model.entitiesOfType(EntityType.FILE).stream().filter(f -> f.name().endsWith("Probe.java")).findFirst().orElseThrow();
        assertEquals("0", file.attributes().get("syntaxUnaccounted"));
        assertTrue(Integer.parseInt(file.attributes().get("syntaxCodeLines")) > 120);

        Entity patternGuard = model.entitiesOfType(EntityType.DECISION).stream()
                .filter(d -> d.attributes().get("kind").equals("PATTERN_GUARD")).findFirst().orElseThrow();
        assertEquals("i > 5", patternGuard.attributes().get("condition"));
        assertFalse(patternGuard.evidence().isEmpty());
        assertEquals(1, model.incoming(patternGuard.id(), RelationType.CONTAINS).size());
        assertTrue(model.findProvenanceGaps().isEmpty());
        assertTrue(model.warnings().stream().anyMatch(w -> w.stage().equals("syntax") && w.message().contains("every one of")));
        assertTrue(model.warnings().stream().noneMatch(w -> w.message().contains("Conflicting attribute")),
                "two decisions must never share an ID");
    }

    @Test
    void stageIsDeterministic() {
        List<EntityId> first = run().entitiesOfType(EntityType.DECISION).stream().map(Entity::id).toList();
        assertEquals(first, run().entitiesOfType(EntityType.DECISION).stream().map(Entity::id).toList());
        assertFalse(first.isEmpty());
    }

    private static AnalysisModel run() {
        AnalysisModel model = new AnalysisModel();
        AnalysisConfig cfg = new AnalysisConfig(List.of(CONFORMANCE.resolve("probe").toString()), "out", 21, List.of(), false);
        assertTrue(new Pipeline(List.of(new DiscoveryStage(), new SyntaxStage())).run(new StageContext(cfg, model)).succeeded());
        return model;
    }

    @Test
    void noJavaFilesIsAnInfoNotAFailure() {
        AnalysisModel model = new AnalysisModel();
        new SyntaxStage_Runner().run(model);
        assertTrue(model.warnings().stream().anyMatch(w -> w.severity() == AnalysisWarning.Severity.INFO));
    }

    /** Runs the stage on a model with no FILE entities. */
    private static final class SyntaxStage_Runner {
        void run(AnalysisModel model) {
            try {
                new SyntaxStage().run(new StageContext(new AnalysisConfig(List.of("x"), "out", 21, List.of(), false), model));
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }
    }
}
