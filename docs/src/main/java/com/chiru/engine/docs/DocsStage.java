package com.chiru.engine.docs;

import com.chiru.engine.core.AnalysisStage;
import com.chiru.engine.core.StageContext;
import java.nio.file.Path;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Phase 9: writes the documentation into the configured output directory. Run after validation. */
public final class DocsStage implements AnalysisStage {

    public static final String NAME = "docs";
    private static final Logger LOG = LoggerFactory.getLogger(DocsStage.class);

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void run(StageContext ctx) throws Exception {
        List<Path> files = DocsGenerator.generate(ctx.model(), ctx.config().outputPath());
        LOG.info("Wrote {} documents to {}", files.size(), ctx.config().outputPath().toAbsolutePath());
    }
}
