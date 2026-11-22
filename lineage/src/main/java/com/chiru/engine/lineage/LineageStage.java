package com.chiru.engine.lineage;

import com.chiru.engine.core.AnalysisStage;
import com.chiru.engine.core.StageContext;

/**
 * Phase 7b: finds output files and their fields and traces every field backwards to where its value comes from.
 * Reads and writes the analysis model only. Run after {@code source-spoon}, {@code sql}, {@code rules} and
 * {@link BindingsStage}; without the SQL stage a trace simply cannot reach a table column.
 */
public final class LineageStage implements AnalysisStage {

    public static final String NAME = "lineage";

    private final LineageOptions options;

    public LineageStage() {
        this(LineageOptions.defaults());
    }

    public LineageStage(LineageOptions options) {
        this.options = options;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void run(StageContext ctx) {
        new LineageExtractor(ctx.model(), options).extract();
    }
}
