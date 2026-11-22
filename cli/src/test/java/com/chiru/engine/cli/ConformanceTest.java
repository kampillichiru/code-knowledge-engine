package com.chiru.engine.cli;

import static org.junit.jupiter.api.Assertions.*;

import com.chiru.engine.core.ModelStore;
import com.chiru.engine.model.AnalysisModel;
import com.chiru.engine.model.AnalysisWarning;
import com.chiru.engine.validation.OutcomeCoverage;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The completeness ratchet. The syntax census says how many decision outcomes the source contains; the engine says how many
 * it captured as conditional rules. The first number is fixed (it is counted by hand in {@code conformance/golden}); the
 * second may only go UP. When a later step improves the engine, raise the baselines below. Lowering one is a regression.
 */
class ConformanceTest {

    private static final Path PROBE = Path.of("..", "conformance", "probe", "src").toAbsolutePath().normalize();
    private static final Path SAMPLE = Path.of("..", "sample-app").toAbsolutePath().normalize();
    private static final Path STEP3 = Path.of("..", "conformance", "probe2", "src").toAbsolutePath().normalize();
    static final int STEP3_TOTAL = 26;

    /** Captured outcomes on the probe. Measured 2026-11-22 with the arm-based rules stage. Raise it, never lower it. */
    static final int PROBE_CAPTURED_BASELINE = 12;
    static final int PROBE_TOTAL = 59;
    /** Outcomes in the Spoon path model on the probe. Step 2 held 48 (59 minus the 11 of kinds that were step 3). Raise it, never lower it. */
    static final int PROBE_PATH_BASELINE = 59;          // every outcome: step 3 added switch expressions, pattern guards and lambda predicates
    static final int SAMPLE_PATH_BASELINE = 30;
    /** Captured outcomes on sample-app. Measured with the arm-based rules stage. Raise it, never lower it. */
    static final int SAMPLE_CAPTURED_BASELINE = 11;
    static final int SAMPLE_TOTAL = 30;

    private static AnalysisModel analyse(Path source, Path out, String... extra) throws IOException {
        StringWriter o = new StringWriter();
        StringWriter e = new StringWriter();
        String[] base = {"analyze", "--source", source.toString(), "--output", out.toString(), "-q"};
        String[] args = new String[base.length + extra.length];
        System.arraycopy(base, 0, args, 0, base.length);
        System.arraycopy(extra, 0, args, base.length, extra.length);
        int code = Main.run(new PrintWriter(o), new PrintWriter(e), args);
        assertEquals(Main.OK, code, e + o.toString());
        return ModelStore.load(out);
    }

    @Test
    void theProbeHasTheHandCountedNumberOfOutcomesAndTheEngineCapturesAtLeastTheBaseline(@TempDir Path out) throws IOException {
        AnalysisModel model = analyse(PROBE, out);
        OutcomeCoverage.Result r = OutcomeCoverage.compute(model);
        assertTrue(r.censusAvailable());
        assertEquals(PROBE_TOTAL, r.total(), "the census must match the golden hand count");
        assertTrue(r.captured() >= PROBE_CAPTURED_BASELINE,
                "captured " + r.captured() + " of " + r.total() + " is below the baseline " + PROBE_CAPTURED_BASELINE);
        assertTrue(r.captured() < r.total(), "if everything is captured, replace this test by an equality and celebrate");
    }

    @Test
    void sampleAppCountsAndRatchet(@TempDir Path out) throws IOException {
        AnalysisModel model = analyse(SAMPLE, out);
        OutcomeCoverage.Result r = OutcomeCoverage.compute(model);
        assertEquals(SAMPLE_TOTAL, r.total());
        assertTrue(r.captured() >= SAMPLE_CAPTURED_BASELINE, "captured " + r.captured() + " of " + r.total());
        // what the sample is missing is exactly: the implicit else paths, the throwing default, the loops and the try blocks
        assertEquals(3, r.missing().stream().filter(o -> o.kind().equals("IF")).count(), "three implicit else paths (the path rules now capture the fourth: a statement after an early return is the else path)");
        assertEquals(10, r.missing().stream().filter(o -> o.kind().equals("LOOP")).count());
        assertEquals(4, r.missing().stream().filter(o -> o.kind().equals("TRY")).count());
    }


    @Test
    void thePathModelContainsEveryOutcomeOfTheKindsItSupports(@TempDir Path probeOut, @TempDir Path sampleOut) throws IOException {
        OutcomeCoverage.Result probe = OutcomeCoverage.compute(analyse(PROBE, probeOut));
        assertTrue(probe.pathModelled() >= PROBE_PATH_BASELINE, "path model holds " + probe.pathModelled() + " of " + probe.total());
        assertTrue(probe.pathMissing().stream().noneMatch(g -> !g.expected()), "no defect: " + probe.pathMissing().stream().filter(g -> !g.expected()).toList());
        assertEquals(List.of(), probe.pathMissing(), "the path model holds every outcome in the probe");

        OutcomeCoverage.Result sample = OutcomeCoverage.compute(analyse(SAMPLE, sampleOut));
        assertEquals(SAMPLE_PATH_BASELINE, sample.pathModelled(), "every outcome in sample-app is in the path model");
        assertEquals(List.of(), sample.pathMissing());
    }


    @Test
    void theStep3FixtureHasAllItsOutcomesInThePathModelAndItsDocument(@TempDir Path out) throws IOException {
        AnalysisModel model = analyse(STEP3, out);
        OutcomeCoverage.Result r = OutcomeCoverage.compute(model);
        assertEquals(STEP3_TOTAL, r.total(), "the census matches the hand count in conformance/golden/step3-outcomes.txt");
        assertEquals(STEP3_TOTAL, r.pathModelled(), "lambdas, initialisers, switch expressions and pattern guards are all in the path model");
        assertEquals(List.of(), r.pathMissing());

        String doc = Files.readString(out.resolve("14_Decision_Paths.md"));
        assertTrue(doc.contains("### Step3.viaSwitch"), doc);
        assertTrue(doc.contains("| 3 | k == 2 AND not (k &gt; 0) | r = 30 | return r |"), "yield 20 ends its case: " + doc);
        assertTrue(doc.contains("### Step3 — lambda at line 25"), "a block lambda has its own paths");
        assertTrue(doc.contains("static initialiser block at line 9") && doc.contains("instance initialiser block at line 15"));
    }

    @Test
    void theDecisionPathsDocumentShowsTheGuardClauseAndTheRejectedPath(@TempDir Path probeOut, @TempDir Path sampleOut) throws IOException {
        analyse(PROBE, probeOut);
        String probeDoc = Files.readString(probeOut.resolve("14_Decision_Paths.md"));
        assertTrue(probeDoc.contains("### Probe.earlyReturn"));
        assertTrue(probeDoc.contains("| 2 | not (x &lt; 0) | x = x + 5 | return x |"), probeDoc);
        assertTrue(probeDoc.contains("Every decision outcome counted in the source is in the path model"), probeDoc);

        analyse(SAMPLE, sampleOut);
        String sampleDoc = Files.readString(sampleOut.resolve("14_Decision_Paths.md"));
        assertTrue(sampleDoc.contains("### ArchiveService.handle"));
        assertTrue(sampleDoc.contains("not (shipment.getQuantity().signum() == 0)") && sampleDoc.contains("\"REJECTED\""),
                "the REJECTED path is conditioned");
        assertTrue(sampleDoc.contains("Every decision outcome counted in the source is in the path model"));
    }

    @Test
    void everyCodeLineOfBothFixturesIsAccountedFor(@TempDir Path probeOut, @TempDir Path sampleOut) throws IOException {
        for (AnalysisModel m : new AnalysisModel[] {analyse(PROBE, probeOut), analyse(SAMPLE, sampleOut)}) {
            assertTrue(m.entitiesOfType(com.chiru.engine.model.EntityType.FILE).stream()
                    .filter(f -> "JAVA".equals(f.attributes().get("kind")))
                    .allMatch(f -> "0".equals(f.attributes().get("syntaxUnaccounted"))), "no code line may be unaccounted for");
            assertTrue(m.warnings().stream().noneMatch(w -> w.stage().equals("validation") && w.message().contains("V015")));
        }
    }

    @Test
    void theGapIsVisibleToTheUserInTheReportsAndTheConsole(@TempDir Path out) throws IOException {
        StringWriter o = new StringWriter();
        Main.run(new PrintWriter(o), new PrintWriter(new StringWriter()), "analyze", "--source", PROBE.toString(), "--output", out.toString(), "-q");
        assertTrue(o.toString().contains("Decision outcomes in the source: 59, captured as conditional rules:"), o.toString());
        String coverage = Files.readString(out.resolve("13_Analysis_Coverage_Report.md"));
        assertTrue(coverage.contains("| Decision outcomes in the source | 59 |"), coverage);
        assertTrue(coverage.contains("| Outcomes captured as conditional rules |"));
        assertTrue(coverage.contains("the rules stage does not model this kind yet"));
        String gaps = Files.readString(out.resolve("12_Open_Questions_And_Gaps.md"));
        assertTrue(gaps.contains("[V014]") && gaps.contains("LOOP decision outcomes in the source are not captured"), "the gap is listed, not hidden");
        assertTrue(gaps.contains("SWITCH_EXPR"), "switch expressions are among the missed kinds");
    }

    @Test
    void anIncompleteAnalysisDoesNotFailAStrictRunYet(@TempDir Path out) {
        // V014 is INFO today. When the path model lands it becomes a gate; this test then changes to expect exit code 3.
        StringWriter e = new StringWriter();
        int code = Main.run(new PrintWriter(new StringWriter()), new PrintWriter(e), "analyze", "--source", PROBE.toString(),
                "--output", out.toString(), "--strict", "-q");
        assertEquals(Main.OK, code, e.toString());
        assertNotNull(AnalysisWarning.Severity.valueOf("INFO"));
    }
}
