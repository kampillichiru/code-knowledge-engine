package com.chiru.engine.sootup;

import com.chiru.engine.model.*;
import java.util.*;
import java.util.stream.Collectors;
import sootup.callgraph.CallGraph;
import sootup.core.jimple.common.stmt.InvokableStmt;
import sootup.core.signatures.MethodSignature;
import sootup.core.types.ClassType;
import sootup.core.types.Type;
import sootup.java.core.JavaSootMethod;
import sootup.java.core.views.JavaView;

/**
 * Writes SootUp call edges into the analysis model using the same method IDs as the Spoon stage, so an edge
 * seen by both analyses becomes one relationship carrying evidence from both. Statuses are merged
 * conservatively (the weaker one wins), and disagreements between source and bytecode are reported.
 */
final class CallGraphMerger {

    private static final String EXTRACTOR = SootUpCallGraphStage.NAME;
    private static final String SOURCE_EXTRACTOR = "source-spoon";
    private static final int EXAMPLES = 5;

    private final AnalysisModel model;
    private final JavaView view;

    CallGraphMerger(AnalysisModel model, JavaView view) {
        this.model = model;
        this.view = view;
    }

    // ------------------------------------------------------------------ merge

    void merge(CallGraph callGraph) {
        List<CallGraph.Call> calls = callGraph.getCalls().stream()
                .sorted(Comparator.comparing((CallGraph.Call c) -> c.sourceMethodSignature().toString())
                        .thenComparingInt(CallGraph.Call::getLineNumber)
                        .thenComparing(c -> c.targetMethodSignature().toString()))
                .toList();

        // number of possible targets per call site (one invoke statement can fan out under CHA)
        Map<InvokableStmt, Integer> targetsPerSite = new IdentityHashMap<>();
        calls.forEach(c -> targetsPerSite.merge(c.invokableStmt(), 1, Integer::sum));

        for (CallGraph.Call call : calls) {
            String kind = invokeKind(call);
            Optional<JavaSootMethod> target = view.getMethod(call.targetMethodSignature());
            Verdict verdict = verdict(kind, target, targetsPerSite.getOrDefault(call.invokableStmt(), 1));
            Evidence ev = evidenceFor(call.sourceMethodSignature().getDeclClassType(), call.getLineNumber(),
                    "bytecode " + kind + ": " + verdict.note);

            EntityId from = ensureMethod(call.sourceMethodSignature(), ev);
            EntityId to = ensureMethod(call.targetMethodSignature(), ev);
            model.addRelationship(new Relationship(from, RelationType.CALLS, to, verdict.status, List.of(ev)));
        }
    }

    private record Verdict(TraceStatus status, String note) {}

    private static Verdict verdict(String kind, Optional<JavaSootMethod> target, int sites) {
        if (target.isEmpty()) {
            return new Verdict(TraceStatus.PARTIAL, "target outside the analysed bytecode");
        }
        return switch (kind) {
            case "invokestatic", "invokespecial" -> new Verdict(TraceStatus.VERIFIED, "statically bound");
            case "invokevirtual" -> sites > 1 || target.get().isAbstract()
                    ? new Verdict(TraceStatus.PARTIAL, "virtual call with " + sites + " possible targets (CHA)")
                    : new Verdict(TraceStatus.VERIFIED, "single possible target");
            case "invokeinterface" -> new Verdict(TraceStatus.PARTIAL,
                    "interface call, " + sites + " possible target(s) (CHA)");
            case "invokedynamic" -> new Verdict(TraceStatus.PARTIAL, "bootstrap-resolved at runtime");
            default -> new Verdict(TraceStatus.PARTIAL, "implicit call (class initialisation)");
        };
    }

    private static String invokeKind(CallGraph.Call call) {
        InvokableStmt stmt = call.invokableStmt();
        return stmt.getInvokeExpr().map(e -> switch (e.getClass().getSimpleName()) {
            case "JStaticInvokeExpr" -> "invokestatic";
            case "JSpecialInvokeExpr" -> "invokespecial";
            case "JVirtualInvokeExpr" -> "invokevirtual";
            case "JInterfaceInvokeExpr" -> "invokeinterface";
            case "JDynamicInvokeExpr" -> "invokedynamic";
            default -> "invoke";
        }).orElse("implicit");
    }

    private EntityId ensureMethod(MethodSignature sig, Evidence ev) {
        EntityId id = idOf(sig);
        if (model.entity(id).isPresent()) {
            return id;
        }
        boolean external = view.getClass(sig.getDeclClassType()).isEmpty();
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("name", sig.getName());
        attrs.put(external ? "external" : "bytecodeOnly", "true");
        if (external) {
            attrs.put("owner", sig.getDeclClassType().getFullyQualifiedName());
        }
        model.putEntity(new Entity(id, sig.getName(), attrs, List.of(ev)));
        return id;
    }

    static EntityId idOf(MethodSignature sig) {
        String params = sig.getParameterTypes().stream().map(Type::toString).collect(Collectors.joining(","));
        return EntityId.of(EntityType.METHOD,
                sig.getDeclClassType().getFullyQualifiedName() + "#" + sig.getName() + "(" + params + ")");
    }

    /** Evidence located in the source file of the declaring class (when Spoon found it), with the call's line. */
    private Evidence evidenceFor(ClassType type, int line, String note) {
        SourceLocation loc = model.entity(EntityId.of(EntityType.CLASS, type.getFullyQualifiedName()))
                .flatMap(e -> e.evidence().stream().map(Evidence::location).filter(Objects::nonNull).findFirst())
                .map(base -> line >= 1 ? new SourceLocation(base.file(), line, line) : base)
                .orElse(null);
        return new Evidence(loc, EXTRACTOR, Certainty.EXTRACTED,
                loc == null ? note + " (no source location: class " + type.getFullyQualifiedName() + ")" : note);
    }

    // ------------------------------------------------------------------ cross-check

    void crossCheckWithSource() {
        int both = 0;
        int bytecodeOnly = 0;
        int sourceOnly = 0;
        int sourceOnlyAbstractOrExternal = 0;
        List<String> bytecodeOnlyExamples = new ArrayList<>();
        List<String> sourceOnlyExamples = new ArrayList<>();

        for (Relationship r : model.relationships()) {
            if (r.type() != RelationType.CALLS) {
                continue;
            }
            boolean src = hasExtractor(r, SOURCE_EXTRACTOR);
            boolean byt = hasExtractor(r, EXTRACTOR);
            if (src && byt) {
                both++;
            } else if (byt) {
                bytecodeOnly++;
                if (bytecodeOnlyExamples.size() < EXAMPLES) {
                    bytecodeOnlyExamples.add(describe(r));
                }
            } else if (src) {
                Entity callee = model.entity(r.to()).orElseThrow();
                boolean expected = "true".equals(callee.attributes().get("external"))
                        || "true".equals(callee.attributes().get("abstract"));
                if (expected) {
                    sourceOnlyAbstractOrExternal++;
                } else {
                    sourceOnly++;
                    if (sourceOnlyExamples.size() < EXAMPLES) {
                        sourceOnlyExamples.add(describe(r));
                    }
                }
            }
        }
        warn(AnalysisWarning.Severity.INFO, "Call-graph cross-check: " + both + " edge(s) confirmed by source and bytecode, "
                + bytecodeOnly + " only in bytecode" + examples(bytecodeOnlyExamples) + ", "
                + sourceOnlyAbstractOrExternal + " source-only edge(s) to abstract or external targets (expected)");
        if (sourceOnly > 0) {
            warn(AnalysisWarning.Severity.WARNING, sourceOnly + " source call edge(s) to analysed methods are missing "
                    + "from the bytecode call graph: class files may be stale or built from different sources"
                    + examples(sourceOnlyExamples));
        }
    }

    private static boolean hasExtractor(Relationship r, String extractor) {
        return r.evidence().stream().anyMatch(e -> e.extractor().equals(extractor));
    }

    private static String describe(Relationship r) {
        return r.from().value().substring(7) + " -> " + r.to().value().substring(7);
    }

    private static String examples(List<String> list) {
        return list.isEmpty() ? "" : " (e.g. " + String.join("; ", list) + ")";
    }

    // ------------------------------------------------------------------ reachability

    /**
     * Marks every analysed method with {@code reachable}: {@code verified} (reachable from an entry point using
     * only VERIFIED call edges), {@code possible} (reachable only through PARTIAL or UNRESOLVED edges), or
     * {@code no}. Entry points are methods the source stage flagged ({@code main}, Spring/messaging annotations).
     */
    void computeReachability() {
        List<Entity> methods = model.entitiesOfType(EntityType.METHOD);
        List<EntityId> entries = methods.stream().filter(m -> m.attributes().containsKey("entryPoint"))
                .map(Entity::id).toList();
        if (entries.isEmpty()) {
            warn(AnalysisWarning.Severity.INFO, "No entry points were found, so reachability was not computed");
            return;
        }
        Set<EntityId> verified = reach(entries, true);
        Set<EntityId> possible = reach(entries, false);
        for (Entity m : methods) {
            if ("true".equals(m.attributes().get("external"))) {
                continue;
            }
            String value = verified.contains(m.id()) ? "verified" : possible.contains(m.id()) ? "possible" : "no";
            model.putEntity(new Entity(m.id(), m.name(), Map.of("reachable", value), List.of()));
        }
    }

    private Set<EntityId> reach(List<EntityId> entries, boolean verifiedOnly) {
        Set<EntityId> seen = new LinkedHashSet<>(entries);
        Deque<EntityId> queue = new ArrayDeque<>(entries);
        while (!queue.isEmpty()) {
            for (Relationship r : model.outgoing(queue.poll(), RelationType.CALLS)) {
                if ((!verifiedOnly || r.status() == TraceStatus.VERIFIED) && seen.add(r.to())) {
                    queue.add(r.to());
                }
            }
        }
        return seen;
    }

    private void warn(AnalysisWarning.Severity severity, String message) {
        model.addWarning(new AnalysisWarning(EXTRACTOR, severity, message, null));
    }
}
