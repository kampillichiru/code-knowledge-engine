package com.chiru.engine.cli;

import com.chiru.engine.core.AnalysisConfig;
import com.chiru.engine.core.AnalysisStage;
import com.chiru.engine.core.ModelStore;
import com.chiru.engine.core.Pipeline;
import com.chiru.engine.core.StageContext;
import com.chiru.engine.discovery.DiscoveryStage;
import com.chiru.engine.docs.DocsStage;
import com.chiru.engine.lineage.BindingsStage;
import com.chiru.engine.lineage.LineageStage;
import com.chiru.engine.model.AnalysisModel;
import com.chiru.engine.rules.RulesStage;
import com.chiru.engine.syntax.DocEvidenceStage;
import com.chiru.engine.syntax.SyntaxStage;
import com.chiru.engine.scenarios.ScenarioStage;
import com.chiru.engine.sootup.SootUpCallGraphStage;
import com.chiru.engine.paths.PathStage;
import com.chiru.engine.spoon.SpoonSourceStage;
import com.chiru.engine.sql.SqlStage;
import com.chiru.engine.validation.ValidationStage;
import java.io.IOException;
import java.util.List;

/** Assembles and runs the whole pipeline. The order matters: each stage reads what the earlier ones wrote. */
public final class Engine {

    private Engine() {}

    /**
     * discovery, syntax (a parse-only census of the source), source-spoon, paths (the path model), bytecode-sootup (skipped without bytecode roots), sql, rules, bindings, lineage,
     * scenarios, validation, docs.
     */
    public static List<AnalysisStage> stages() {
        return List.of(new DiscoveryStage(), new SyntaxStage(), new SpoonSourceStage(), new DocEvidenceStage(), new PathStage(), new SootUpCallGraphStage(), new SqlStage(),
                new RulesStage(), new BindingsStage(), new LineageStage(), new ScenarioStage(), new ValidationStage(),
                new DocsStage());
    }

    /** The result of a run: the pipeline outcome and the model, which is saved next to the documents. */
    public record Run(Pipeline.Result result, AnalysisModel model) {}

    /** Runs every stage, then saves {@code analysis-model.json} even when a stage failed, to help find out why. */
    public static Run analyse(AnalysisConfig config) throws IOException {
        AnalysisModel model = new AnalysisModel();
        Pipeline.Result result = new Pipeline(stages()).run(new StageContext(config, model));
        ModelStore.save(model, config.outputPath());
        return new Run(result, model);
    }
}
