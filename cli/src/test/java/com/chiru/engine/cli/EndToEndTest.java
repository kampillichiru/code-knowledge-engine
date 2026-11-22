package com.chiru.engine.cli;

import static org.junit.jupiter.api.Assertions.*;

import com.chiru.engine.core.ModelStore;
import com.chiru.engine.docs.DocsGenerator;
import com.chiru.engine.model.AnalysisModel;
import com.chiru.engine.model.AnalysisWarning;
import com.chiru.engine.validation.Finding;
import com.chiru.engine.validation.ModelValidator;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the real command line on the sample application and checks what a user gets: exit codes, the saved model, all
 * documents, that every file:line reference in them is real, regeneration from the model, and bad-input handling.
 */
class EndToEndTest {

    private static final Path SAMPLE = Path.of("..", "sample-app").toAbsolutePath().normalize();
    private static final Path CLASSES = SAMPLE.resolve("target/classes");
    private static final List<String> DOCS = new ArrayList<>(DocsGenerator.DOCUMENTS.keySet());

    record Result(int code, String out, String err) {}

    private static Path out;
    private static Result analysis;

    @BeforeAll
    static void analyseOnce(@TempDir Path shared) throws IOException {
        out = Files.createTempDirectory("ci-e2e");
        analysis = cli("analyze", "--source", SAMPLE.toString(), "--bytecode", CLASSES.toString(), "--output", out.toString(), "-q");
    }

    private static Result cli(String... args) {
        StringWriter o = new StringWriter();
        StringWriter e = new StringWriter();
        int code = Main.run(new PrintWriter(o), new PrintWriter(e), args);
        return new Result(code, o.toString(), e.toString());
    }

    private static String read(Path dir, String file) throws IOException {
        return Files.readString(dir.resolve(file), StandardCharsets.UTF_8);
    }

    private static Map<String, String> readAll(Path dir) throws IOException {
        Map<String, String> all = new LinkedHashMap<>();
        for (String f : Stream.concat(Stream.of(DocsGenerator.INDEX), DOCS.stream()).toList()) {
            all.put(f, read(dir, f));
        }
        return all;
    }

    // ---------------------------------------------------------------- the run

    @Test
    void analyseSucceedsThroughEveryStage() {
        assertEquals(Main.OK, analysis.code(), analysis.err());
        for (String stage : List.of("discovery", "syntax", "source-spoon", "paths", "bytecode-sootup", "sql", "rules", "bindings", "lineage",
                "scenarios", "validation", "docs")) {
            assertTrue(analysis.out().matches("(?s).*\\n\\s+" + Pattern.quote(stage) + "\\s+ok\\s.*"), stage + " should be ok:\n" + analysis.out());
        }
        assertTrue(analysis.out().contains("Output fields: 4 VERIFIED, 1 PARTIAL, 0 UNRESOLVED (of 5)"), analysis.out());
        assertTrue(analysis.out().contains("12 classes") && analysis.out().contains("3 scenarios"), analysis.out());
    }

    @Test
    void writesTheModelAndAllDocuments() throws IOException {
        assertTrue(Files.isRegularFile(out.resolve(ModelStore.FILE_NAME)));
        assertEquals(22, DOCS.size() + 1);
        for (String file : readAll(out).keySet()) {
            String text = read(out, file);
            assertTrue(text.length() > 300, file + " is too short");
            assertTrue(text.contains("Generated from `analysis-model.json`"), file);
        }
        assertTrue(read(out, "00_Index.md").contains("(13_Analysis_Coverage_Report.md)"));
    }

    @Test
    void documentsStateTheRealFindingsOfTheSample() throws IOException {
        String mapping = read(out, "07_Field_Mapping.md");
        assertTrue(mapping.contains("**Position:** 44-53 (width 10)"));
        assertTrue(mapping.contains("System.getProperty") && mapping.contains("UNRESOLVED"));
        assertTrue(mapping.contains("| StorageService | shipment.rate_pct |"), "quantity depends on the rate column in the storage scenario");

        String trace = read(out, "08_Source_To_Output_Traceability.md");
        assertTrue(trace.contains("| shipment.quantity | fee |"), "forward tracing lists the fields a column feeds");
        assertTrue(trace.contains("## adjustment — PARTIAL"));
        assertTrue(trace.contains("runtime input"));

        String rules = read(out, "04_Business_Rules.md");
        assertTrue(rules.contains("### BR-0001") && rules.contains("LARGE_QUANTITY = "));
        assertTrue(read(out, "11_Business_Scenario_Analysis.md").contains("## StorageService (STORAGE)"));
        assertTrue(read(out, "03_Execution_Flow.md").contains("flowchart TD"));
        assertTrue(read(out, "05_Database_Inventory.md").contains("| rate_pct | DECIMAL (5, 2) |"));
        assertTrue(read(out, "06_SQL_Analysis.md").contains("UPDATE shipment SET quantity = ?, status = ? WHERE id = ?"));
        assertTrue(read(out, "09_Output_File_Specification.md").contains("Record length by the sum of widths: **53**"));
        assertTrue(read(out, "12_Open_Questions_And_Gaps.md").contains("adjustment"));
        assertTrue(read(out, "13_Analysis_Coverage_Report.md").contains("| Output fields with a trace status | 5 of 5 | 100% |"));
        assertTrue(read(out, "10_Class_Method_Dependencies.md").contains("| com.sample.shipping.DispatchService | IMPLEMENTS |"));
    }

    @Test
    void everyFileAndLineReferenceInTheDocumentsIsReal() throws IOException {
        Pattern ref = Pattern.compile("(sample-app/[\\w./-]+\\.(?:java|sql|xml|properties)):(\\d+)(?:-(\\d+))?");
        Path base = SAMPLE.getParent();
        int checked = 0;
        for (Map.Entry<String, String> doc : readAll(out).entrySet()) {
            Matcher m = ref.matcher(doc.getValue());
            while (m.find()) {
                Path file = base.resolve(m.group(1));
                assertTrue(Files.isRegularFile(file), doc.getKey() + " cites a file that does not exist: " + m.group(0));
                long lines = Files.readAllLines(file).size();
                int start = Integer.parseInt(m.group(2));
                int end = m.group(3) == null ? start : Integer.parseInt(m.group(3));
                assertTrue(start >= 1 && end >= start && end <= lines, doc.getKey() + " cites lines outside the file: " + m.group(0));
                checked++;
            }
        }
        assertTrue(checked > 200, "documents should be full of evidence references, found " + checked);
    }

    @Test
    void savedModelLoadsAndPassesValidation() throws IOException {
        AnalysisModel model = ModelStore.load(out);
        List<Finding> findings = ModelValidator.validate(model, List.of(SAMPLE));
        assertTrue(findings.stream().noneMatch(f -> f.severity() == AnalysisWarning.Severity.ERROR),
                findings.stream().map(Finding::describe).toList().toString());
        Result validate = cli("validate", "--model-dir", out.toString(), "--source", SAMPLE.toString());
        assertEquals(Main.OK, validate.code(), validate.out());
        assertTrue(validate.out().contains("V007"), "the unresolved adjustment field is reported by the validator");
    }

    // ---------------------------------------------------------------- regeneration and determinism

    @Test
    void docsRegenerateFromTheSavedModelWithoutReanalysis(@TempDir Path elsewhere) throws IOException {
        Result r = cli("docs", "--model-dir", out.toString(), "--output", elsewhere.toString());
        assertEquals(Main.OK, r.code(), r.err());
        assertEquals(readAll(out), readAll(elsewhere), "the same model must give byte-identical documents");
    }

    @Test
    void aSecondFullRunGivesIdenticalDocumentsAndModel(@TempDir Path second) throws IOException {
        Result r = cli("analyze", "--source", SAMPLE.toString(), "--bytecode", CLASSES.toString(), "--output", second.toString(), "-q");
        assertEquals(Main.OK, r.code(), r.err());
        assertEquals(readAll(out), readAll(second));
        assertEquals(read(out, ModelStore.FILE_NAME), read(second, ModelStore.FILE_NAME), "the model is deterministic too");
    }

    // ---------------------------------------------------------------- queries

    @Test
    void traceAnswersReverseAndForwardQuestions() {
        Result reverse = cli("trace", "--model-dir", out.toString(), "--field", "adjustment", "--steps");
        assertEquals(Main.OK, reverse.code(), reverse.err());
        assertTrue(reverse.out().contains("trace status PARTIAL"), reverse.out());
        assertTrue(reverse.out().contains("[UNRESOLVED] StorageService -> runtime input"), reverse.out());
        assertTrue(reverse.out().contains("ManifestWriter.java:"), "steps carry file and line");

        Result forward = cli("trace", "--model-dir", out.toString(), "--column", "shipment.quantity");
        assertEquals(Main.OK, forward.code(), forward.err());
        for (String field : List.of("status", "quantity", "fee")) {
            assertTrue(forward.out().contains(field), forward.out());
        }
        assertFalse(forward.out().contains("shipmentId"), "shipmentId depends on the id column only");
    }

    @Test
    void traceRejectsUnknownNamesAndMissingModels(@TempDir Path empty) {
        assertEquals(Main.BAD_INPUT, cli("trace", "--model-dir", out.toString(), "--field", "nosuchfield").code());
        assertEquals(Main.BAD_INPUT, cli("trace", "--model-dir", out.toString(), "--column", "no.column").code());
        Result noModel = cli("trace", "--model-dir", empty.toString(), "--field", "quantity");
        assertEquals(Main.BAD_INPUT, noModel.code());
        assertTrue(noModel.err().contains("analysis-model.json"));
        assertEquals(Main.BAD_INPUT, cli("docs", "--model-dir", empty.toString()).code());
    }

    // ---------------------------------------------------------------- options and bad input

    @Test
    void sourceOnlyRunWorksAndSaysBytecodeWasNotAnalysed(@TempDir Path dir) throws IOException {
        Result r = cli("analyze", "--source", SAMPLE.toString(), "--output", dir.toString(), "-q");
        assertEquals(Main.OK, r.code(), r.err());
        assertTrue(r.out().contains("Output fields: 4 VERIFIED, 1 PARTIAL"), "lineage does not need bytecode");
        assertTrue(read(dir, "02_Functionality_Inventory.md").contains("Bytecode analysis did not run"));
        assertTrue(read(dir, "13_Analysis_Coverage_Report.md").contains("bytecode analysis did not run"));
    }

    @Test
    void configFileIsReadAndCommandLineOverridesIt(@TempDir Path dir) throws IOException {
        Path cfg = dir.resolve("config.json");
        Files.writeString(cfg, "{\"sourceRoots\":[\"" + SAMPLE.toString().replace("\\", "/") + "\"],\"outputDir\":\""
                + dir.resolve("from-config").toString().replace("\\", "/") + "\",\"javaRelease\":21,\"excludeGlobs\":[],"
                + "\"continueOnError\":false,\"bytecodeRoots\":[]}");
        Result a = cli("analyze", "--config", cfg.toString(), "-q");
        assertEquals(Main.OK, a.code(), a.err());
        assertTrue(Files.isRegularFile(dir.resolve("from-config").resolve("13_Analysis_Coverage_Report.md")));

        Result b = cli("analyze", "--config", cfg.toString(), "--output", dir.resolve("override").toString(), "-q");
        assertEquals(Main.OK, b.code(), b.err());
        assertTrue(Files.isRegularFile(dir.resolve("override").resolve("13_Analysis_Coverage_Report.md")));
    }

    @Test
    void excludeGlobHidesFilesFromTheAnalysis(@TempDir Path dir) {
        Result r = cli("analyze", "--source", SAMPLE.toString(), "--exclude", "**/ManifestWriter.java", "--output", dir.toString(), "-q");
        assertEquals(Main.OK, r.code(), r.err());
        assertTrue(r.out().contains("0 output fields"), "without the writer there is no output layout:\n" + r.out());
    }

    @Test
    void strictModePassesWhenThereAreNoValidationErrors(@TempDir Path dir) {
        assertEquals(Main.OK, cli("analyze", "--source", SAMPLE.toString(), "--output", dir.toString(), "--strict", "-q").code());
    }

    @Test
    void badInputIsRefusedWithAClearMessage(@TempDir Path dir) {
        Result missing = cli("analyze", "--source", dir.resolve("does-not-exist").toString(), "--output", dir.toString());
        assertEquals(Main.BAD_INPUT, missing.code());
        assertTrue(missing.err().contains("source path does not exist"), missing.err());

        Result none = cli("analyze", "--output", dir.toString());
        assertEquals(Main.BAD_INPUT, none.code());
        assertTrue(none.err().contains("no source given"), none.err());

        Result badBytecode = cli("analyze", "--source", SAMPLE.toString(), "--bytecode", dir.resolve("nope").toString(), "--output", dir.toString());
        assertEquals(Main.BAD_INPUT, badBytecode.code());

        Result badConfig = cli("analyze", "--config", dir.resolve("missing.json").toString());
        assertEquals(Main.BAD_INPUT, badConfig.code());
        assertEquals(2, cli("analyze", "--no-such-option").code(), "picocli usage errors exit with 2");
    }

    @Test
    void aProjectWithNoOutputsStillGetsCompleteDocumentsAndSaysSo(@TempDir Path dir) throws IOException {
        Path src = dir.resolve("src/demo");
        Files.createDirectories(src);
        Files.writeString(src.resolve("Calc.java"), "package demo;\npublic class Calc {\n"
                + "    public int plusOne(int x) {\n        if (x > 10) {\n            return x + 1;\n        }\n        return x;\n    }\n"
                + "    public static void main(String[] args) {\n        System.out.println(new Calc().plusOne(3));\n    }\n}\n");
        Path docs = dir.resolve("docs");
        Result r = cli("analyze", "--source", dir.resolve("src").toString(), "--output", docs.toString(), "-q");
        assertEquals(Main.OK, r.code(), r.err() + r.out());
        assertEquals(24, Files.list(docs).count(), "all 22 documents, the model and the context folder");
        assertTrue(read(docs, "09_Output_File_Specification.md").contains("No fixed-width output file was identified"));
        assertTrue(read(docs, "04_Business_Rules.md").contains("BR-0001"), "the condition in plusOne is a rule");
        assertTrue(read(docs, "12_Open_Questions_And_Gaps.md").contains("no fixed-width output layout"));
        assertTrue(read(docs, "02_Functionality_Inventory.md").contains("Calc.main"));
    }

    @Test
    void sourceThatDoesNotParseDoesNotCrashTheRun(@TempDir Path dir) throws IOException {
        Path src = dir.resolve("src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("Broken.java"), "package x; public class Broken { void f( { } ");
        Files.writeString(src.resolve("Fine.java"), "package x; public class Fine { int g(int a) { return a * 2; } }");
        Result r = cli("analyze", "--source", src.toString(), "--output", dir.resolve("o").toString(), "-q");
        assertTrue(r.code() == Main.OK || r.code() == Main.STAGE_FAILED, "either outcome is fine, a stack trace is not: " + r.err());
        assertFalse(r.err().contains("Exception in thread"), r.err());
        assertTrue(Files.isRegularFile(dir.resolve("o").resolve(ModelStore.FILE_NAME)), "the model is saved even when a stage fails");
    }

    @Test
    void helpListsTheCommands() {
        Result r = cli("--help");
        assertEquals(Main.OK, r.code());
        for (String c : List.of("analyze", "docs", "trace", "validate")) {
            assertTrue(r.out().contains(c), r.out());
        }
        assertTrue(cli("analyze", "--help").out().contains("--bytecode"));
    }
}
