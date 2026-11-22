package com.chiru.engine.scenarios;

import com.chiru.engine.core.AnalysisStage;
import com.chiru.engine.core.StageContext;

/**
 * Phase 8: discovers business scenarios from the code and attaches to each one the methods, rules, SQL, columns and
 * output fields that belong to it. Nothing is hardcoded: a scenario is a handler class that a dispatching
 * {@code switch} or {@code if} chain selects, or (when no dispatcher is recognised) a class that the lineage
 * traces name as a variant. Reads the model only; run after {@code lineage}.
 */
public final class ScenarioStage implements AnalysisStage {

    public static final String NAME = "scenarios";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void run(StageContext ctx) {
        new ScenarioExtractor(ctx.model()).extract();
    }
}
