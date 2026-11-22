package com.chiru.engine.docs;

import com.chiru.engine.model.AnalysisModel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Phase 9: writes the Markdown documents (and Mermaid diagrams) from the analysis model and nothing else, so they can be
 * regenerated from a saved {@code analysis-model.json} without analysing the code again. Output is deterministic: the
 * same model gives byte-identical files.
 */
public final class DocsGenerator {

    /** File name and what it answers, in reading order. */
    public static final Map<String, String> DOCUMENTS = new LinkedHashMap<>();

    private static final Map<String, Function<DocContext, String>> BUILDERS = new LinkedHashMap<>();

    static {
        add("01_Module_Overview.md", "What was analysed: repositories, modules, files, packages", StructureDocs::moduleOverview);
        add("02_Functionality_Inventory.md", "Entry points, scenarios, inputs and outputs, classes", StructureDocs::functionality);
        add("03_Execution_Flow.md", "Possible call flow from each entry point (Mermaid)", StructureDocs::executionFlow);
        add("04_Business_Rules.md", "Conditions and calculations found in the code, with evidence", RuleDocs::businessRules);
        add("05_Database_Inventory.md", "Tables and columns and how they are accessed", DatabaseDocs::inventory);
        add("06_SQL_Analysis.md", "Every SQL statement, its tables and parameter bindings", DatabaseDocs::sqlAnalysis);
        add("07_Field_Mapping.md", "How each output field is derived, per scenario", LineageDocs::fieldMapping);
        add("08_Source_To_Output_Traceability.md", "Step-by-step reverse traces and forward impact of each column", LineageDocs::traceability);
        add("09_Output_File_Specification.md", "Layout, positions and widths of each output file", LineageDocs::outputSpecification);
        add("10_Class_Method_Dependencies.md", "Inheritance, class calls and method call counts", StructureDocs::dependencies);
        add("11_Business_Scenario_Analysis.md", "Each scenario: trigger, methods, rules, columns, outputs", LineageDocs::scenarios);
        add("12_Open_Questions_And_Gaps.md", "Everything not established: missing links, review items, limits", QualityDocs::gaps);
        add("13_Analysis_Coverage_Report.md", "What share of the discovered items was analysed, with denominators", QualityDocs::coverage);
        add("14_Decision_Paths.md", "Every execution path of every method that branches: when, what in order, how it ends", PathDocs::decisionPaths);
        add("F01_Business_Overview.md", "FUNCTIONAL: what the application does, in business words", FunctionalDocs::overview);
        add("F02_End_To_End_Business_Flow.md", "FUNCTIONAL: the flow from start to result, with a diagram", FunctionalDocs::endToEnd);
        add("F03_Business_Scenarios_And_Rules.md", "FUNCTIONAL: for each kind of work, what it decides and calculates", FunctionalDocs::scenarios);
        add("F04_Data_Dictionary.md", "FUNCTIONAL: every output field, its position and where its value comes from", FunctionalDocs::dataDictionary);
        add("F07_Field_Reference.md", "FUNCTIONAL: one section per output field, traced both ways, with what changes its value", FunctionalDocs::fieldReference);
        add("F05_Glossary.md", "FUNCTIONAL: terms, columns and fixed values spelled out for the business to define", FunctionalDocs::glossary);
        add("F06_Questions_For_Business_Experts.md", "FUNCTIONAL: what the code cannot say and a person must confirm", FunctionalDocs::questions);
    }

    public static final String INDEX = "00_Index.md";

    private DocsGenerator() {}

    private static void add(String file, String description, Function<DocContext, String> builder) {
        DOCUMENTS.put(file, description);
        BUILDERS.put(file, builder);
    }

    /** Renders every document in memory: file name to content. */
    public static Map<String, String> render(AnalysisModel model) {
        return render(model, Map.of());
    }

    /** As {@link #render(AnalysisModel)}, with business names for the functional documents ({@code name = term}). */
    public static Map<String, String> render(AnalysisModel model, Map<String, String> glossary) {
        return render(model, glossary, Map.of());
    }

    /** As above, with the business answers (keys such as {@code system.purpose} and {@code source.<field>}) from {@code answers.properties}. */
    public static Map<String, String> render(AnalysisModel model, Map<String, String> glossary, Map<String, String> answers) {
        DocContext context = new DocContext(model, glossary, answers);
        Map<String, String> out = new LinkedHashMap<>();
        out.put(INDEX, StructureDocs.index(context, DOCUMENTS));
        BUILDERS.forEach((file, builder) -> out.put(file, builder.apply(context)));
        return out;
    }

    /** {@code glossary.properties} in the output directory: business names for identifiers and {@code table.column}. Absent means none. */
    static Map<String, String> loadGlossary(Path outputDir) {
        return loadProperties(outputDir, "glossary.properties");
    }

    /** A properties file from {@code outputDir/context} or, failing that, {@code outputDir}. Absent means empty. */
    public static Map<String, String> loadProperties(Path outputDir, String name) {
        Path file = Files.isRegularFile(outputDir.resolve("context").resolve(name)) ? outputDir.resolve("context").resolve(name) : outputDir.resolve(name);
        Map<String, String> out = new LinkedHashMap<>();
        if (Files.isRegularFile(file)) {
            try (var in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                java.util.Properties p = new java.util.Properties();
                p.load(in);
                p.stringPropertyNames().stream().sorted().forEach(k -> {
                    if (!p.getProperty(k).isBlank()) {
                        out.put(k.trim(), p.getProperty(k).trim());
                    }
                });
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
        return out;
    }

    /** Writes the documents into {@code outputDir} (created if needed) and returns the files written. */
    public static List<Path> generate(AnalysisModel model, Path outputDir) throws IOException {
        Files.createDirectories(outputDir);
        List<Path> written = new ArrayList<>();
        Map<String, String> glossary = loadGlossary(outputDir);
        Map<String, String> answers = loadProperties(outputDir, "answers.properties");
        for (Map.Entry<String, String> doc : render(model, glossary, answers).entrySet()) {
            Path target = outputDir.resolve(doc.getKey());
            Files.writeString(target, doc.getValue().replace("\r\n", "\n"), StandardCharsets.UTF_8);
            written.add(target);
        }
        written.addAll(writeContext(model, outputDir, glossary, answers));
        return written;
    }

    /** One thing only the business can say, as the pipeline reports it. {@code key} is the line to add to {@code context/answers.properties}. */
    public record Missing(String key, String about, String question, String type) {}

    /** The blocking questions nobody has answered yet (answers and glossary are read from the output directory's {@code context} folder). */
    public static List<Missing> missing(AnalysisModel model, Path outputDir) {
        DocContext c = new DocContext(model, loadGlossary(outputDir), loadProperties(outputDir, "answers.properties"));
        List<Missing> out = new ArrayList<>();
        for (FunctionalDocs.Open o : FunctionalDocs.openItems(c)) {
            if (o.blocking() && !FunctionalDocs.answered(c, o)) {
                out.add(new Missing(o.key(), o.about(), o.question(), o.type()));
            }
        }
        return out;
    }

    /**
     * The {@code context} folder: where the business adds what the code cannot say. {@code answers.properties} and {@code glossary.properties}
     * are the business's files and are never overwritten; the worksheet and the template are regenerated each time.
     */
    static List<Path> writeContext(AnalysisModel model, Path outputDir, Map<String, String> glossary, Map<String, String> answers) throws IOException {
        Path ctx = outputDir.resolve("context");
        Files.createDirectories(ctx);
        DocContext c = new DocContext(model, glossary, answers);
        List<Path> out = new ArrayList<>();
        Path worksheet = ctx.resolve("glossary-worksheet.properties");
        Files.writeString(worksheet, FunctionalDocs.glossaryWorksheet(c, glossary, loadProperties(outputDir, "ai-meanings.properties")).replace("\r\n", "\n"), StandardCharsets.UTF_8);
        out.add(worksheet);
        StringBuilder t = new StringBuilder("# Questions the analysis could not answer from the code. Copy a line into answers.properties and write the answer after the '='.\n"
                + "# Keys that start with 'system.' describe the application; 'source.<field>' says where a value really comes from.\n"
                + "# A key that is a constant name (for example MAX_LIMIT) is answered in glossary.properties.\n\n");
        for (FunctionalDocs.Open o : FunctionalDocs.openItems(c)) {
            if (!FunctionalDocs.answered(c, o)) {
                t.append("# ").append(o.type()).append(" - ").append(o.about()).append(": ").append(o.question()).append('\n')
                        .append(o.key()).append(" = \n\n");
            }
        }
        Path template = ctx.resolve("answers.template.properties");
        Files.writeString(template, t.toString().replace("\r\n", "\n"), StandardCharsets.UTF_8);
        out.add(template);
        Path mine = ctx.resolve("answers.properties");
        if (!Files.exists(mine)) {
            Files.writeString(mine, "# Your answers, one per line: key = answer. See answers.template.properties for the open questions.\n", StandardCharsets.UTF_8);
        }
        return out;
    }
}
