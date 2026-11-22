package com.chiru.engine.cli;

import com.chiru.engine.core.ModelStore;
import com.chiru.engine.model.AnalysisModel;
import com.chiru.engine.model.AnalysisWarning;
import com.chiru.engine.validation.Finding;
import com.chiru.engine.validation.ModelValidator;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(name = "validate", mixinStandardHelpOptions = true,
        description = "Re-runs the integrity checks on a saved model. Exit code 3 when any check reports an error.")
final class ValidateCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Option(names = {"-m", "--model-dir"}, required = true, description = "Directory containing analysis-model.json.")
    Path modelDir;

    @Option(names = {"-s", "--source"}, description = "Source root(s), to check that evidence still matches the files. Repeat for several.")
    List<Path> sources = new ArrayList<>();

    @Override
    public Integer call() throws IOException {
        PrintWriter out = spec.commandLine().getOut();
        if (!Files.isRegularFile(modelDir.resolve(ModelStore.FILE_NAME))) {
            spec.commandLine().getErr().println("error: no " + ModelStore.FILE_NAME + " in " + modelDir);
            return Main.BAD_INPUT;
        }
        AnalysisModel model = ModelStore.load(modelDir);
        List<Finding> findings = ModelValidator.validate(model, sources);
        findings.forEach(f -> out.println(f.severity() + " " + f.describe()));
        long errors = findings.stream().filter(f -> f.severity() == AnalysisWarning.Severity.ERROR).count();
        out.println(findings.size() + " finding(s), " + errors + " error(s)");
        return errors > 0 ? Main.VALIDATION_ERRORS : Main.OK;
    }
}
