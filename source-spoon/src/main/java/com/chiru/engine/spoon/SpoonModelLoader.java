package com.chiru.engine.spoon;

import com.chiru.engine.core.AnalysisConfig;
import com.chiru.engine.core.StageContext;
import com.chiru.engine.discovery.DiscoveryStage;
import com.chiru.engine.model.AnalysisModel;
import com.chiru.engine.model.AnalysisWarning;
import com.chiru.engine.model.Entity;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spoon.Launcher;
import spoon.reflect.CtModel;

/** Builds the Spoon model for the main-scope Java files found by discovery. Shared by stages that read Java. */
public final class SpoonModelLoader {

    private static final Logger LOG = LoggerFactory.getLogger(SpoonModelLoader.class);

    /** The parsed model plus a map from absolute file path to the display path used in evidence. */
    public record Loaded(CtModel model, Map<Path, String> displayPaths) {}

    private SpoonModelLoader() {}

    /** Like {@link #load} but parses once per run: later stages reuse the model cached on the context. */
    public static Optional<Loaded> loadShared(StageContext ctx, String stage, boolean reportSkips) {
        return ctx.cached("spoon-model", () -> load(ctx.config(), ctx.model(), stage, reportSkips));
    }

    /**
     * @param stage       name used on warnings
     * @param reportSkips whether to report test-scope files that were skipped (once per run is enough)
     * @return empty when there is nothing to parse (a warning has been recorded)
     */
    public static Optional<Loaded> load(AnalysisConfig config, AnalysisModel model, String stage, boolean reportSkips) {
        List<Entity> javaFiles = DiscoveryStage.filesOfKind(model, "JAVA");
        Map<Path, String> displayPaths = new HashMap<>();
        Launcher launcher = new Launcher();
        launcher.getEnvironment().setNoClasspath(true);
        launcher.getEnvironment().setComplianceLevel(config.javaRelease());
        launcher.getEnvironment().setCommentEnabled(false);
        launcher.getEnvironment().setAutoImports(false);

        int skippedTests = 0;
        int added = 0;
        for (Entity file : javaFiles) {
            if ("test".equals(file.attributes().get("scope"))) {
                skippedTests++;
                continue;
            }
            Path abs = Path.of(file.attributes().get("absolutePath")).toAbsolutePath().normalize();
            displayPaths.put(abs, file.name());
            launcher.addInputResource(abs.toString());
            added++;
        }
        if (reportSkips && skippedTests > 0) {
            model.addWarning(new AnalysisWarning(stage, AnalysisWarning.Severity.INFO,
                    skippedTests + " test-scope Java file(s) were not analysed", null));
        }
        if (added == 0) {
            model.addWarning(new AnalysisWarning(stage, AnalysisWarning.Severity.WARNING,
                    "No main-scope Java files to analyse", null));
            return Optional.empty();
        }
        LOG.info("[{}] Spoon parsing {} Java files at compliance level {}", stage, added, config.javaRelease());
        return Optional.of(new Loaded(launcher.buildModel(), displayPaths));
    }
}
