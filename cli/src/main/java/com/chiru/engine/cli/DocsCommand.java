package com.chiru.engine.cli;

import com.chiru.engine.core.ModelStore;
import com.chiru.engine.docs.DocsGenerator;
import com.chiru.engine.model.AnalysisModel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

@Command(name = "docs", mixinStandardHelpOptions = true,
        description = "Regenerates the documents from a saved analysis-model.json, without analysing the code again.")
final class DocsCommand implements Callable<Integer> {

    @Spec
    CommandSpec spec;

    @Option(names = {"-m", "--model-dir"}, required = true, description = "Directory containing analysis-model.json.")
    Path modelDir;

    @Option(names = {"-o", "--output"}, description = "Where to write the documents (default: the model directory).")
    Path output;

    @Override
    public Integer call() throws IOException {
        if (!Files.isRegularFile(modelDir.resolve(ModelStore.FILE_NAME))) {
            spec.commandLine().getErr().println("error: no " + ModelStore.FILE_NAME + " in " + modelDir);
            return Main.BAD_INPUT;
        }
        AnalysisModel model = ModelStore.load(modelDir);
        List<Path> files = DocsGenerator.generate(model, output == null ? modelDir : output);
        spec.commandLine().getOut().println("Wrote " + files.size() + " documents to " + (output == null ? modelDir : output).toAbsolutePath());
        return Main.OK;
    }
}
