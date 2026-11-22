package com.chiru.engine.cli;

import com.chiru.engine.core.ModelStore;
import com.chiru.engine.lineage.LineageQuery;
import com.chiru.engine.model.AnalysisModel;
import com.chiru.engine.model.EntityId;
import com.chiru.engine.model.ModelIndex;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(name = "trace", mixinStandardHelpOptions = true,
        description = "Answers lineage questions from a saved model: how an output field is derived (--field), "
                + "or which output fields a column feeds (--column).")
final class TraceCommand implements Callable<Integer> {

    /** {@code business_rule:calc:com.x.Foo#bar(int)@33:14-1049:init} */
    private static final Pattern RULE_ID = Pattern.compile("^business_rule:(calc|cond):([^@]+)@");

    static final class Target {
        @Option(names = "--field", description = "Output field name, e.g. total (reverse trace).")
        String field;

        @Option(names = "--column", description = "Source column, e.g. orders.total (forward trace).")
        String column;
    }

    @Spec
    CommandSpec spec;

    @Option(names = {"-m", "--model-dir"}, required = true, description = "Directory containing analysis-model.json.")
    Path modelDir;

    @ArgGroup(exclusive = true, multiplicity = "1")
    Target target;

    @Option(names = "--steps", description = "Also print every step of each trace, with file and line.")
    boolean steps;

    @Override
    public Integer call() throws IOException {
        PrintWriter out = spec.commandLine().getOut();
        PrintWriter err = spec.commandLine().getErr();
        if (!Files.isRegularFile(modelDir.resolve(ModelStore.FILE_NAME))) {
            err.println("error: no " + ModelStore.FILE_NAME + " in " + modelDir);
            return Main.BAD_INPUT;
        }
        AnalysisModel model = ModelStore.load(modelDir);
        if (target.field != null) {
            Optional<LineageQuery.FieldLineage> lineage = LineageQuery.reverse(model, target.field);
            if (lineage.isEmpty()) {
                err.println("error: no output field named '" + target.field + "'");
                return Main.BAD_INPUT;
            }
            var f = lineage.get().field();
            out.printf("Output field %s  (positions %s-%s)  trace status %s%n", f.name(), f.attributes().getOrDefault("start", "?"),
                    f.attributes().getOrDefault("end", "?"), f.attributes().getOrDefault("traceStatus", "UNRESOLVED"));
            if (f.attributes().containsKey("gaps")) {
                out.println("Gaps: " + f.attributes().get("gaps"));
            }
            for (LineageQuery.Trace t : lineage.get().traces()) {
                out.printf("  [%s] %s -> %s %s%n", t.status(), t.scenario(), t.terminalKind(), t.terminal());
                if (!t.rules().isEmpty()) {
                    out.println("        rules: " + readable(t.rules()));
                }
                if (steps) {
                    for (String step : t.steps().split(" <- ")) {
                        out.println("        <- " + step);
                    }
                }
            }
            return Main.OK;
        }
        List<LineageQuery.Impact> impacts = LineageQuery.forward(model, target.column);
        if (impacts.isEmpty()) {
            err.println("error: no output field depends on a column named '" + target.column + "'");
            return Main.BAD_INPUT;
        }
        // several paths can lead to the same field in the same scenario; show each result once with its path count
        Map<String, Integer> grouped = new LinkedHashMap<>();
        for (LineageQuery.Impact i : impacts) {
            grouped.merge(String.format("%-12s [%s] %s", i.field().name(), i.trace().status(), i.trace().scenario()), 1, Integer::sum);
        }
        out.println("Column " + target.column + " feeds:");
        grouped.forEach((line, n) -> out.println("  " + line + (n > 1 ? "  (" + n + " paths)" : "")));
        return Main.OK;
    }

    /** Rule IDs as {@code calc ManifestWriter.amount}: the kind and the method, not the model key. */
    static String readable(String ruleIds) {
        return Arrays.stream(ruleIds.split(", ")).map(id -> {
            Matcher m = RULE_ID.matcher(id);
            return m.find() ? m.group(1) + " " + ModelIndex.shortName(new EntityId("method:" + m.group(2))) : id;
        }).distinct().collect(Collectors.joining(", "));
    }
}
