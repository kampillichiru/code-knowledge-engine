package com.chiru.engine.cli;

import static org.junit.jupiter.api.Assertions.*;

import com.chiru.engine.core.ModelStore;
import com.chiru.engine.model.AnalysisModel;
import com.chiru.engine.model.EntityType;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A project of several modules where {@code module-1} uses {@code common}: how the roots are given decides what the engine can
 * see across the module boundary, and the documents say which modules were analysed.
 */
class MultiModuleTest {

    private static final Path MULTI = Path.of("..", "conformance", "multimodule").toAbsolutePath().normalize();
    private static final Path SAMPLE = Path.of("..", "sample-app").toAbsolutePath().normalize();

    private record Run(int code, String out, String err) {}

    private static Run analyse(Path out, String... sources) {
        String[] args = new String[sources.length * 2 + 3];
        int i = 0;
        args[i++] = "analyze";
        for (String s : sources) {
            args[i++] = "--source";
            args[i++] = s;
        }
        args[i++] = "--output";
        args[i] = out.toString();
        StringWriter o = new StringWriter();
        StringWriter e = new StringWriter();
        int code = Main.run(new PrintWriter(o), new PrintWriter(e), concat(args, "-q"));
        return new Run(code, o.toString(), e.toString());
    }

    private static String[] concat(String[] a, String extra) {
        String[] out = java.util.Arrays.copyOf(a, a.length + 1);
        out[a.length] = extra;
        return out;
    }

    private static String read(Path dir, String file) throws IOException {
        return Files.readString(dir.resolve(file));
    }

    // ---------------------------------------------------------------- all the modules given

    @Test
    void aCallAcrossTheModuleBoundaryResolvesWhenBothModulesAreGiven(@TempDir Path out) throws IOException {
        Run r = analyse(out, MULTI.toString());
        assertEquals(Main.OK, r.code(), r.err());
        assertTrue(r.out().contains("2 classes, 7 methods"), r.out());
        assertTrue(r.out().contains("Output fields: 2 VERIFIED, 0 PARTIAL"), "the surcharge field traces into the common module: " + r.out());

        String trace = read(out, "08_Source_To_Output_Traceability.md");
        assertTrue(trace.contains("BASE = new java.math.BigDecimal(\"0.05\")") && trace.contains("VIP_BASE = new java.math.BigDecimal(\"0.03\")"),
                "the constants live in the common module");
        assertTrue(trace.contains("multimodule/common/src/main/java/com/acme/common/Rates.java"), "evidence points into the common module");
        assertFalse(read(out, "12_Open_Questions_And_Gaps.md").contains("was not analysed"));
    }

    @Test
    void theModuleOverviewShowsInheritedCoordinatesAndWhatDependsOnWhat(@TempDir Path out) throws IOException {
        analyse(out, MULTI.toString());
        String overview = read(out, "01_Module_Overview.md");
        assertTrue(overview.contains("| Module | Build | Coordinates | Packaging | Depends on (analysed) | Project modules NOT analysed |"), overview);
        assertTrue(overview.contains("| common | MAVEN | com.acme:common:1.0 (groupId or version inherited from the parent) |"));
        assertTrue(overview.contains("| module-1 | MAVEN | com.acme:module-1:1.0 (groupId or version inherited from the parent) | — | common | — |"),
                "module-1 depends on common, which was analysed: " + overview);
    }

    @Test
    void documentsNameTheModuleOfEveryClassAndRule(@TempDir Path out) throws IOException {
        analyse(out, MULTI.toString());
        String functionality = read(out, "02_Functionality_Inventory.md");
        assertTrue(functionality.contains("| Class | Module | Kind |"));
        assertTrue(functionality.contains("| com.acme.app.SurchargeJob | module-1 |"));
        assertTrue(functionality.contains("| com.acme.common.Rates | common |"));
        assertTrue(functionality.contains("| Method | Module | Kind | Evidence |"), "the entry point (main) is listed with its module");
        String rules = read(out, "04_Business_Rules.md");
        assertTrue(rules.contains("| ID | Module | Kind | Method | Status | Evidence |"));
        assertTrue(rules.contains("| BR-0001 | common | conditional | Rates.rateFor |"), rules);
    }

    @Test
    void theModuleDependencyGraphSetsWhatIsDeclaredAgainstWhatIsCalled(@TempDir Path out) throws IOException {
        analyse(out, MULTI.toString());
        String deps = read(out, "10_Class_Method_Dependencies.md");
        assertTrue(deps.contains("## Module dependencies"));
        assertTrue(deps.contains("m0 -->|1| m1"), "one call edge from module-1 to common: " + deps);
        assertTrue(deps.contains("| module-1 | common | yes | 1 | SurchargeJob.format calls Rates.surcharge |"), deps);
        assertTrue(deps.contains("| Method | Module | Modifiers |"), "the method table carries the module too");
    }

    @Test
    void passingTheModuleFoldersSeparatelyGivesTheSameAnalysis(@TempDir Path parentRun, @TempDir Path separateRun) throws IOException {
        analyse(parentRun, MULTI.toString());
        Run r = analyse(separateRun, MULTI.resolve("common").toString(), MULTI.resolve("module-1").toString());
        assertEquals(Main.OK, r.code(), r.err());
        assertTrue(r.out().contains("2 classes, 7 methods") && r.out().contains("2 VERIFIED"), r.out());
        assertTrue(read(separateRun, "10_Class_Method_Dependencies.md").contains("| module-1 | common | yes | 1 |"));
    }

    @Test
    void foldersCalledSrcDoNotMergeIntoOneRepositoryOrLoseTheirModule(@TempDir Path out) throws IOException {
        Run r = analyse(out, MULTI.resolve("common/src").toString(), MULTI.resolve("module-1/src").toString());
        assertEquals(Main.OK, r.code(), r.err());
        assertTrue(r.out().contains("2 classes, 7 methods"), r.out());
        String gaps = read(out, "12_Open_Questions_And_Gaps.md");
        assertFalse(gaps.contains("Conflicting attribute"), "two roots named src are two repositories");
        String functionality = read(out, "02_Functionality_Inventory.md");
        assertTrue(functionality.contains("common/src/main/java/com/acme/common/Rates.java"));
        assertTrue(functionality.contains("module-1/src/main/java/com/acme/app/SurchargeJob.java"));
    }

    // ---------------------------------------------------------------- a module left out

    @Test
    void aMissingProjectModuleIsReportedAndTheTraceIsPartial(@TempDir Path out) throws IOException {
        Run r = analyse(out, MULTI.resolve("module-1").toString());
        assertEquals(Main.OK, r.code(), "a missing module is a warning, not a failure: " + r.err());
        assertTrue(r.out().contains("1 classes, 4 methods") && r.out().contains("1 PARTIAL"), r.out());
        assertTrue(r.out().contains("1 warning(s)"), r.out());

        String gaps = read(out, "12_Open_Questions_And_Gaps.md");
        assertTrue(gaps.contains("module 'module-1' declares a dependency on com.acme:common:1.0"), gaps);
        assertTrue(gaps.contains("was not analysed") && gaps.contains("Add that module's folder"), gaps);
        String overview = read(out, "01_Module_Overview.md");
        assertTrue(overview.contains("**com.acme:common:1.0**"), "the module overview marks the missing module: " + overview);
        assertTrue(read(out, "08_Source_To_Output_Traceability.md").contains("external call"), "the value behind the call cannot be seen");
    }

    @Test
    void aMissingModuleDoesNotFailAStrictRunButShowsAsAWarning(@TempDir Path out) {
        StringWriter e = new StringWriter();
        int code = Main.run(new PrintWriter(new StringWriter()), new PrintWriter(e), "analyze", "--source", MULTI.resolve("module-1").toString(),
                "--output", out.toString(), "--strict", "-q");
        assertEquals(Main.OK, code, "--strict fails on validation errors, and an unanalysed module is a warning: " + e);
    }

    // ---------------------------------------------------------------- one module: nothing changes

    @Test
    void aSingleModuleProjectKeepsItsTablesWithoutAModuleColumn(@TempDir Path out) throws IOException {
        Run r = analyse(out, SAMPLE.toString());
        assertEquals(Main.OK, r.code(), r.err());
        assertTrue(read(out, "02_Functionality_Inventory.md").contains("| Class | Kind | Stereotype |"), "no Module column for one module");
        assertTrue(read(out, "10_Class_Method_Dependencies.md").contains("The analysis covers a single module"));
        AnalysisModel model = ModelStore.load(out);
        assertTrue(model.entitiesOfType(EntityType.MODULE).stream().anyMatch(m -> "sample-app".equals(m.attributes().get("artifactId"))));
    }
}
