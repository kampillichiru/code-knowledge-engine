package com.chiru.engine.sootup;

import com.chiru.engine.core.AnalysisStage;
import com.chiru.engine.core.StageContext;
import com.chiru.engine.model.AnalysisWarning;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sootup.callgraph.CallGraph;
import sootup.callgraph.ClassHierarchyAnalysisAlgorithm;
import sootup.core.inputlocation.AnalysisInputLocation;
import sootup.core.model.SootMethod;
import sootup.core.model.SourceType;
import sootup.core.signatures.MethodSignature;
import sootup.java.bytecode.frontend.inputlocation.PathBasedAnalysisInputLocation;
import sootup.java.core.JavaSootClass;
import sootup.java.core.views.JavaView;

/**
 * Builds a class-hierarchy (CHA) call graph from the compiled classes in {@code bytecodeRoots} and merges it
 * into the model. Every concrete method is used as an entry so that library code without a {@code main}
 * is covered; reachability from real entry points is computed afterwards on the model.
 *
 * <p>CHA over-approximates virtual calls (every compatible override is a possible target), so such edges
 * are never reported as VERIFIED. Reflection, dependency injection and invokedynamic bootstrap targets are
 * not resolved.
 */
public final class SootUpCallGraphStage implements AnalysisStage {

    public static final String NAME = "bytecode-sootup";
    private static final Logger LOG = LoggerFactory.getLogger(SootUpCallGraphStage.class);

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void run(StageContext ctx) {
        List<Path> roots = ctx.config().bytecodeRootPaths();
        if (roots.isEmpty()) {
            ctx.model().addWarning(new AnalysisWarning(NAME, AnalysisWarning.Severity.INFO,
                    "No bytecodeRoots configured: bytecode call graph and reachability were skipped", null));
            return;
        }
        List<PathBasedAnalysisInputLocation> locations = new ArrayList<>();
        try {
            for (Path root : roots) {
                Path abs = root.toAbsolutePath().normalize();
                if (!Files.exists(abs)) {
                    throw new IllegalArgumentException("Bytecode root does not exist: " + abs);
                }
                locations.add(PathBasedAnalysisInputLocation.create(abs, SourceType.Application));
            }
            JavaView view = new JavaView(new ArrayList<AnalysisInputLocation>(locations));

            List<MethodSignature> entries = new ArrayList<>();
            int classCount = 0;
            for (JavaSootClass cls : view.getClasses().toList()) {
                classCount++;
                for (SootMethod m : cls.getMethods()) {
                    if (m.hasBody()) {
                        entries.add(m.getSignature());
                    }
                }
            }
            if (classCount == 0) {
                ctx.model().addWarning(new AnalysisWarning(NAME, AnalysisWarning.Severity.WARNING,
                        "No classes found under the configured bytecodeRoots", null));
                return;
            }
            LOG.info("SootUp CHA over {} classes, {} concrete methods", classCount, entries.size());
            CallGraph callGraph = new ClassHierarchyAnalysisAlgorithm(view).initialize(entries);
            CallGraphMerger merger = new CallGraphMerger(ctx.model(), view);
            merger.merge(callGraph);
            merger.crossCheckWithSource();
            merger.computeReachability();
            int classes = BranchCensus.write(ctx.model(), view);
            ctx.model().addWarning(new AnalysisWarning(NAME, AnalysisWarning.Severity.INFO,
                    "Bytecode branch census recorded for " + classes + " class(es); validation compares it with the decisions found in source", null));
        } finally {
            locations.forEach(PathBasedAnalysisInputLocation::close);
        }
    }
}
