package com.chiru.engine.cli;

import ch.qos.logback.classic.Level;
import com.chiru.engine.core.AnalysisConfig;
import com.chiru.engine.core.ModelStore;
import com.chiru.engine.core.Pipeline;
import com.chiru.engine.docs.DocsGenerator;
import com.chiru.engine.model.AnalysisModel;
import com.chiru.engine.model.AnalysisWarning;
import com.chiru.engine.model.EntityType;
import com.chiru.engine.validation.OutcomeCoverage;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(name = "analyze", mixinStandardHelpOptions = true,
        description = "Analyses Java sources (and optionally compiled classes), writes analysis-model.json and the 13 documents.")
final class AnalyzeCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Option(names = {"-s", "--source"}, description = "Directory or file to analyse. Repeat for several.")
    List<String> sources = new ArrayList<>();

    @Option(names = {"-b", "--bytecode"}, description = "Directory or jar of compiled classes for the call graph and reachability "
            + "(optional; classes must be Java 21 bytecode or older). Repeat for several.")
    List<String> bytecode = new ArrayList<>();

    @Option(names = {"-o", "--output"}, description = "Output directory (default: ${DEFAULT-VALUE}).", defaultValue = "analysis-output")
    String output;

    @Option(names = "--java-release", description = "Java language level of the analysed code (default: ${DEFAULT-VALUE}).", defaultValue = "21")
    int javaRelease;

    @Option(names = {"-x", "--exclude"}, description = "Glob of files to skip, e.g. **/generated/**. Repeat for several.")
    List<String> excludes = new ArrayList<>();

    @Option(names = {"-c", "--config"}, description = "JSON file with the same settings; command line options override it.")
    Path config;

    @Option(names = "--continue-on-error", description = "Run later stages even if one fails (default: stop at the first failure).")
    boolean continueOnError;

    @Option(names = "--strict", description = "Exit with code 3 when validation finds errors or a completeness defect (V015, V016, V017).")
    boolean strict;

    @Option(names = {"-q", "--quiet"}, description = "Only warnings and errors on stderr.")
    boolean quiet;

    @Override
    public Integer call() throws Exception {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        if (quiet) {
            ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger("com.chiru")).setLevel(Level.WARN);
        }
        AnalysisConfig cfg;
        try {
            cfg = buildConfig();
        } catch (IllegalArgumentException | java.io.IOException e) {
            err.println("error: " + e.getMessage());
            return Main.BAD_INPUT;
        }
        for (String root : cfg.sourceRoots()) {
            if (!Files.exists(Path.of(root))) {
                err.println("error: source path does not exist: " + root);
                return Main.BAD_INPUT;
            }
        }
        for (String root : cfg.bytecodeRoots()) {
            if (!Files.exists(Path.of(root))) {
                err.println("error: bytecode path does not exist: " + root);
                return Main.BAD_INPUT;
            }
        }

        Engine.Run run = Engine.analyse(cfg);
        print(out, cfg, run);

        if (!run.result().succeeded()) {
            err.println("error: a stage failed; see the table above and the ERROR warnings in "
                    + cfg.outputPath().resolve(ModelStore.FILE_NAME));
            return Main.STAGE_FAILED;
        }
        boolean validationErrors = run.model().warnings().stream().anyMatch(w -> w.stage().equals("validation")
                && (w.severity() == AnalysisWarning.Severity.ERROR
                || w.severity() == AnalysisWarning.Severity.WARNING && isCompletenessGate(w.message())));
        if (strict && validationErrors) {
            err.println("error: validation reported errors (--strict); see 12_Open_Questions_And_Gaps.md");
            return Main.VALIDATION_ERRORS;
        }
        return Main.OK;
    }

    /** Findings that mean the analysis missed code: a decision outcome absent from the path model (V016), a code line the census cannot account for (V015) or a branch the compiler made that the source model lacks (V017). */
    static boolean isCompletenessGate(String message) {
        return message.startsWith("[V015]") || message.startsWith("[V016]") || message.startsWith("[V017]");
    }

    private AnalysisConfig buildConfig() throws java.io.IOException {
        AnalysisConfig base = config == null ? null : AnalysisConfig.load(config);
        List<String> src = sources.isEmpty() && base != null ? base.sourceRoots() : sources;
        if (src.isEmpty()) {
            throw new IllegalArgumentException("no source given: use --source <dir> or --config <file>");
        }
        List<String> bc = bytecode.isEmpty() && base != null ? base.bytecodeRoots() : bytecode;
        List<String> ex = excludes.isEmpty() && base != null ? base.excludeGlobs() : excludes;
        String outDir = base != null && !spec.commandLine().getParseResult().hasMatchedOption("--output") ? base.outputDir() : output;
        int release = base != null && !spec.commandLine().getParseResult().hasMatchedOption("--java-release") ? base.javaRelease() : javaRelease;
        boolean cont = continueOnError || (base != null && base.continueOnError());
        return new AnalysisConfig(src, outDir, release, ex, cont, bc);
    }

    private static void print(PrintWriter out, AnalysisConfig cfg, Engine.Run run) {
        out.println();
        out.println("Stages");
        for (Pipeline.StageResult s : run.result().stages()) {
            out.printf("  %-18s %-7s %6d ms%s%n", s.stage(), s.success() ? "ok" : "FAILED", s.durationMillis(),
                    s.error() == null ? "" : "  " + s.error());
        }
        AnalysisModel m = run.model();
        out.println();
        out.printf("Found: %d classes, %d methods, %d SQL statements, %d rules, %d scenarios, %d output fields%n",
                inSource(m, EntityType.CLASS), inSource(m, EntityType.METHOD), m.entitiesOfType(EntityType.SQL_STATEMENT).size(),
                m.entitiesOfType(EntityType.BUSINESS_RULE).size(), m.entitiesOfType(EntityType.SCENARIO).size(),
                m.entitiesOfType(EntityType.OUTPUT_FIELD).size());
        long verified = fields(m, "VERIFIED");
        long partial = fields(m, "PARTIAL");
        long unresolved = fields(m, "UNRESOLVED");
        out.printf("Output fields: %d VERIFIED, %d PARTIAL, %d UNRESOLVED (of %d)%n", verified, partial, unresolved,
                m.entitiesOfType(EntityType.OUTPUT_FIELD).size());
        OutcomeCoverage.Result outcomes = OutcomeCoverage.compute(m);
        if (outcomes.censusAvailable()) {
            out.printf("Decision outcomes in the source: %d, captured as conditional rules: %d (%d%%); see 13_Analysis_Coverage_Report.md%n",
                    outcomes.total(), outcomes.captured(), outcomes.total() == 0 ? 100 : Math.round(100.0 * outcomes.captured() / outcomes.total()));
        }
            out.printf("Decision outcomes in the path model: %d of %d%s%n", outcomes.pathModelled(), outcomes.total(),
                    outcomes.pathModelled() == outcomes.total() ? " (all of them)" : " (the rest: see V016 in 12_Open_Questions_And_Gaps.md)");
        long errors = m.warnings().stream().filter(w -> w.severity() == AnalysisWarning.Severity.ERROR).count();
        long warnings = m.warnings().stream().filter(w -> w.severity() == AnalysisWarning.Severity.WARNING).count();
        out.printf("Warnings: %d error(s), %d warning(s), %d note(s)%n", errors, warnings,
                m.warnings().size() - errors - warnings);
        out.println();
        out.println("Written to " + cfg.outputPath().toAbsolutePath());
        out.println("  " + ModelStore.FILE_NAME + " (the model; documents can be regenerated from it)");
        out.println("  " + DocsGenerator.INDEX + " and " + DocsGenerator.DOCUMENTS.size() + " documents; start with " + DocsGenerator.INDEX);
    }

    private static long inSource(AnalysisModel m, EntityType type) {
        return m.entitiesOfType(type).stream().filter(e -> !"true".equals(e.attributes().get("external"))).count();
    }

    private static long fields(AnalysisModel m, String status) {
        return m.entitiesOfType(EntityType.OUTPUT_FIELD).stream().filter(f -> status.equals(f.attributes().get("traceStatus"))).count();
    }
}
