package com.chiru.engine.validation;

import com.chiru.engine.core.AnalysisStage;
import com.chiru.engine.core.StageContext;
import com.chiru.engine.model.AnalysisWarning;

/**
 * Phase 10: runs {@link ModelValidator} and records each finding as a warning of stage {@code validation}, so the
 * result is saved with the model and shows up in the gap and coverage documents. Run last, after every analysis stage.
 */
public final class ValidationStage implements AnalysisStage {

    public static final String NAME = "validation";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void run(StageContext ctx) {
        for (Finding f : ModelValidator.validate(ctx.model(), ctx.config().sourceRootPaths())) {
            ctx.model().addWarning(new AnalysisWarning(NAME, f.severity(), f.describe(), null));
        }
    }
}
