package com.chiru.engine.core;

import com.chiru.engine.model.AnalysisWarning;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs stages in order. By default the first failure stops the run, because later stages would build
 * on incomplete data. Every failure is recorded as an ERROR warning in the model.
 */
public final class Pipeline {

    private static final Logger LOG = LoggerFactory.getLogger(Pipeline.class);

    public record StageResult(String stage, boolean success, long durationMillis, String error) {}

    public record Result(List<StageResult> stages) {
        public boolean succeeded() {
            return stages.stream().allMatch(StageResult::success);
        }
    }

    private final List<AnalysisStage> stages;

    public Pipeline(List<AnalysisStage> stages) {
        this.stages = List.copyOf(stages);
    }

    public Result run(StageContext context) {
        List<StageResult> results = new ArrayList<>();
        for (AnalysisStage stage : stages) {
            long start = System.nanoTime();
            LOG.info("Stage '{}' starting", stage.name());
            try {
                stage.run(context);
                long ms = (System.nanoTime() - start) / 1_000_000;
                results.add(new StageResult(stage.name(), true, ms, null));
                LOG.info("Stage '{}' finished in {} ms", stage.name(), ms);
            } catch (Exception e) {
                long ms = (System.nanoTime() - start) / 1_000_000;
                String message = e.getClass().getSimpleName() + ": " + e.getMessage();
                results.add(new StageResult(stage.name(), false, ms, message));
                context.model().addWarning(new AnalysisWarning(stage.name(),
                        AnalysisWarning.Severity.ERROR, "Stage failed: " + message, null));
                LOG.error("Stage '{}' failed", stage.name(), e);
                if (!context.config().continueOnError()) {
                    break;
                }
            }
        }
        return new Result(results);
    }
}
