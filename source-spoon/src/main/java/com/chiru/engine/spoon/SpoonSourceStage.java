package com.chiru.engine.spoon;

import com.chiru.engine.core.AnalysisStage;
import com.chiru.engine.core.StageContext;

/**
 * Parses the Java files found by discovery with Spoon (no classpath needed) and extracts types, members,
 * calls and branches into the analysis model. Test-scope files are skipped and reported.
 */
public final class SpoonSourceStage implements AnalysisStage {

    public static final String NAME = "source-spoon";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void run(StageContext ctx) {
        SpoonModelLoader.loadShared(ctx, NAME, true)
                .ifPresent(loaded -> new SourceExtractor(ctx.model(), loaded.displayPaths()).extract(loaded.model()));
    }
}
