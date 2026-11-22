package com.chiru.engine.rules;

import com.chiru.engine.core.AnalysisStage;
import com.chiru.engine.core.PureLibraries;
import com.chiru.engine.core.StageContext;
import java.util.List;

/**
 * Phase 6b: derives BUSINESS_RULE entities from the facts earlier stages wrote (expressions, branches, DERIVED_FROM
 * and GUARDED_BY edges). It reads only the analysis model, so it runs after {@code source-spoon} and needs no parser.
 * A rule is the structure found in the code; it is never named or explained here.
 *
 * <p>A rule is VERIFIED when everything it reads is traced; a call outside the analysed sources makes it PARTIAL unless
 * the callee is on the pure-library list ({@link PureLibraries}), the same list the lineage tracer uses.
 */
public final class RulesStage implements AnalysisStage {

    public static final String NAME = "rules";

    private final List<String> pureLibraryPrefixes;

    public RulesStage() {
        this(PureLibraries.DEFAULT_PREFIXES);
    }

    public RulesStage(List<String> pureLibraryPrefixes) {
        this.pureLibraryPrefixes = List.copyOf(pureLibraryPrefixes);
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void run(StageContext ctx) {
        new RuleExtractor(ctx.model(), pureLibraryPrefixes).extract();
    }
}
