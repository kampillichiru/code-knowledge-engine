package com.chiru.engine.cli;

import ch.qos.logback.classic.Level;
import com.chiru.engine.core.AnalysisConfig;
import com.chiru.engine.core.Pipeline;
import com.chiru.engine.docs.DocsGenerator;
import com.chiru.engine.docs.Narration;
import com.chiru.engine.model.AnalysisModel;
import com.chiru.engine.model.AnalysisWarning;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/**
 * The whole workflow in one command, safe to run again and again:
 *
 * <ol>
 *   <li>analyse the code (all stages) and write the technical documents 00 to 14;</li>
 *   <li>write the functional documents F01 to F06, using what the business put in {@code context/} (glossary, answers);</li>
 *   <li>write the AI hand-off ({@code ai/PROMPT.md} and one small pack per section);</li>
 *   <li>if the AI has replied ({@code ai/responses/}), check every paragraph and write {@code N01_AI_Narrative.md};</li>
 *   <li>list what is still missing in {@code NEEDS_INPUT.md} and the state of the run in {@code PIPELINE_STATUS.md}.</li>
 * </ol>
 *
 * Add answers to {@code context/answers.properties} (or replies to {@code ai/responses/}) and run it again: everything is rebuilt.
 * Exit code 0 when finished, 4 when information is still missing and {@code --require-complete} is set.
 */
@Command(name = "pipeline", mixinStandardHelpOptions = true,
        description = "Runs the whole workflow: analysis, functional documents, AI hand-off, verification, and a list of what is still missing.")
final class PipelineCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Option(names = {"-s", "--source"}, required = true, description = "Source root (repeat for several modules).")
    List<String> sources = new ArrayList<>();

    @Option(names = {"-b", "--bytecode"}, description = "Compiled classes, for the bytecode cross-check (repeatable).")
    List<String> bytecode = new ArrayList<>();

    @Option(names = {"-o", "--output"}, defaultValue = "analysis-output", description = "Output directory (default: ${DEFAULT-VALUE}).")
    String output;

    @Option(names = {"-e", "--exclude"}, description = "Glob of files to leave out (repeatable).")
    List<String> excludes = new ArrayList<>();

    @Option(names = "--java-release", defaultValue = "21", description = "Java language level of the sources.")
    int javaRelease;

    @Option(names = "--require-complete", description = "Exit with code 4 while information is still missing (for CI).")
    boolean requireComplete;

    @Option(names = {"-q", "--quiet"}, description = "Less output.")
    boolean quiet;

    @Override
    public Integer call() throws IOException {
        PrintWriter out = spec.commandLine().getOut();
        if (quiet) {
            ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger("com.chiru")).setLevel(Level.WARN);
        }
        for (String s : sources) {
            if (!Files.exists(Path.of(s))) {
                spec.commandLine().getErr().println("error: source path does not exist: " + s);
                return Main.BAD_INPUT;
            }
        }
        AnalysisConfig cfg = new AnalysisConfig(sources, output, javaRelease, excludes, false, bytecode);
        Path outDir = cfg.outputPath();

        out.println("1/5 Analysing the code ...");
        Engine.Run run = Engine.analyse(cfg);
        if (!run.result().succeeded()) {
            spec.commandLine().getErr().println("error: a stage failed; see " + outDir.resolve("analysis-model.json"));
            return Main.STAGE_FAILED;
        }
        AnalysisModel model = run.model();
        Map<String, String> glossary = DocsGenerator.loadProperties(outDir, "glossary.properties");
        Map<String, String> answers = DocsGenerator.loadProperties(outDir, "answers.properties");

        out.println("2/5 Writing the AI hand-off (ai/PROMPT.md and one pack per section) ...");
        Path ai = outDir.resolve("ai");
        List<Path> packs = Narration.writePack(model, ai, glossary, answers);

        out.println("3/5 Checking the AI replies, if there are any ...");
        Narration.Report report = Narration.importResponses(model, ai, outDir, glossary, answers);
        writeMeanings(outDir, report.meanings());

        out.println("4/5 Writing the functional documents with your context ...");
        DocsGenerator.generate(model, outDir);

        out.println("5/5 Checking what is still missing ...");
        List<DocsGenerator.Missing> missing = DocsGenerator.missing(model, outDir);
        writeNeedsInput(outDir, missing);
        writeStatus(outDir, run, packs.size(), report, missing);

        out.println();
        out.println("Documents:  " + outDir.toAbsolutePath());
        out.println("Start here: F01_Business_Overview.md (functional), 00_Index.md (all)");
        out.println("AI step:    " + report.accepted().size() + " section(s) checked and written to N01_AI_Narrative.md, "
                + report.pending().size() + " waiting for a reply (" + report.paragraphsAccepted() + " paragraph(s) accepted, "
                + report.paragraphsRejected() + " rejected)");
        if (missing.isEmpty()) {
            out.println("Missing:    nothing. Every question the code raised has an answer.");
        } else {
            out.println("Missing:    " + missing.size() + " item(s) only you can answer; see NEEDS_INPUT.md. Add them to context/answers.properties and run again.");
        }
        long warnings = model.warnings().stream().filter(w -> w.severity() == AnalysisWarning.Severity.WARNING).count();
        out.println("Warnings:   " + warnings + " (see 12_Open_Questions_And_Gaps.md)");
        return requireComplete && !missing.isEmpty() ? Main.NEEDS_INPUT : Main.OK;
    }

    private static void writeMeanings(Path outDir, Map<String, String> meanings) throws IOException {
        if (meanings.isEmpty()) {
            return;
        }
        Path file = outDir.resolve("context").resolve("ai-meanings.properties");
        Files.createDirectories(file.getParent());
        StringBuilder sb = new StringBuilder("# AI-proposed meanings that passed verification. They are NOT confirmed; they only prefill glossary-worksheet.properties.\n");
        meanings.forEach((k, v) -> sb.append(k).append(" = ").append(v.replace("\n", " ")).append('\n'));
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    private static void writeNeedsInput(Path outDir, List<DocsGenerator.Missing> missing) throws IOException {
        Path file = outDir.resolve("NEEDS_INPUT.md");
        if (missing.isEmpty()) {
            Files.deleteIfExists(file);
            return;
        }
        StringBuilder sb = new StringBuilder("# Information needed\n\nThe analysis could not establish the following from the code. Add each answer to "
                + "`context/answers.properties` as `key = answer` (constants and names go to `context/glossary.properties`), then run the pipeline again; "
                + "the documents are rebuilt with your answers. `context/answers.template.properties` has these lines ready to copy.\n\n");
        int n = 0;
        for (DocsGenerator.Missing m : missing) {
            sb.append(++n).append(". **`").append(m.key()).append("`** (").append(m.type()).append(") — ").append(m.about()).append("  \n   ")
                    .append(m.question()).append("\n\n");
        }
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
    }

    private static void writeStatus(Path outDir, Engine.Run run, int packs, Narration.Report report, List<DocsGenerator.Missing> missing) throws IOException {
        StringBuilder sb = new StringBuilder("# Pipeline status\n\n| Step | State |\n| --- | --- |\n");
        Map<String, String> steps = new LinkedHashMap<>();
        for (Pipeline.StageResult s : run.result().stages()) {
            steps.put("analysis: " + s.stage(), s.success() ? "ok" : "FAILED");
        }
        steps.put("functional documents F01 to F06", "written");
        steps.put("AI hand-off", packs + " pack(s) in ai/pack; instructions in ai/PROMPT.md");
        steps.put("AI replies checked", report.accepted().size() + " section(s) accepted, " + report.pending().size() + " waiting"
                + (report.problems().isEmpty() ? "" : "; problems: " + String.join(" | ", report.problems())));
        steps.put("information missing", missing.isEmpty() ? "none" : missing.size() + " item(s), see NEEDS_INPUT.md");
        steps.forEach((k, v) -> sb.append("| ").append(k).append(" | ").append(v).append(" |\n"));
        sb.append("\n## What to do next\n\n");
        if (!report.pending().isEmpty()) {
            List<String> fieldPacks = report.pending().stream().filter(x -> x.startsWith("field-")).toList();
            List<String> others = report.pending().stream().filter(x -> !x.startsWith("field-")).toList();
            sb.append("- **AI:** give each file in `ai/pack` to your AI together with `ai/PROMPT.md`, save its reply under the same name in `ai/responses`: waiting sections: ")
                    .append(others.isEmpty() ? "none of the story sections" : String.join(", ", others)).append(".\n");
            if (!fieldPacks.isEmpty()) {
                sb.append("- **AI, one field at a time:** ").append(fieldPacks.size()).append(" field pack(s) (`ai/pack/field-*.json`) are waiting. With an agent (for example Copilot in agent mode) "
                        + "ask it to work through every `field-*.json` file; the replies are checked and written to `N02_Field_Derivations.md`.\n");
            }
        }
        if (!missing.isEmpty()) {
            sb.append("- **You:** answer the items in `NEEDS_INPUT.md` in `context/answers.properties`.\n");
        }
        sb.append("- Then run the pipeline again; nothing is lost between runs.\n");
        Files.writeString(outDir.resolve("PIPELINE_STATUS.md"), sb.toString(), StandardCharsets.UTF_8);
    }
}
