package com.chiru.engine.validation;

import com.chiru.engine.model.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compares independent views of the same code. The syntax census (the JDK parser) says which decision outcomes exist in the
 * source; that is the denominator. Two things are measured against it:
 *
 * <ul>
 *   <li><b>rules</b>: outcomes captured as conditional rules (an outcome is captured when a rule sits under that branch arm).
 *       Only if, ternary, switch and switch-expression arms can be captured this way.</li>
 *   <li><b>path model</b>: outcomes that the Spoon-based path model contains. For the decision kinds the path model supports
 *       this is an identity: every source outcome must be there. A missing one is a defect (or a construct inside a lambda or
 *       an initialiser, which is not modelled yet and is reported as such).</li>
 * </ul>
 *
 * Census and path-model decisions are paired by file, line and kind, in source order, so two decisions on one line are
 * still paired correctly.
 */
public final class OutcomeCoverage {

    /** One source outcome: where it is, what kind of decision, which outcome. */
    public record Outcome(String file, int line, String kind, String outcome) {
        @Override
        public String toString() {
            return file + ":" + line + " " + kind + " " + outcome;
        }
    }

    /**
     * An outcome the path model does not contain. {@code expected} is true when that is known and reported (a kind the path
     * model does not support yet, or a lambda or initialiser body), false when it is a defect.
     */
    public record PathGap(Outcome outcome, String reason, boolean expected) {}

    /**
     * @param total        outcomes in the source (the census)
     * @param captured     of those, captured as conditional rules; {@code byKind}: kind to {total, captured}
     * @param pathModelled of those, present in the path model; {@code pathByKind}: kind to {total, modelled}
     */
    public record Result(boolean censusAvailable, int total, int captured, Map<String, int[]> byKind, List<Outcome> missing,
                         int pathModelled, Map<String, int[]> pathByKind, List<PathGap> pathMissing) {}

    /** Census kinds the rules stage can model, mapped to the branch kind in rule IDs. */
    private static final Map<String, String> MODELLED = Map.of("IF", "if", "TERNARY", "ternary", "SWITCH", "switch",
            "SWITCH_EXPR", "switch-expression");
    /** Census kinds the path model supports: every kind the census counts. A kind added to the census later is reported as not modelled yet. */
    private static final Set<String> PATH_KINDS = Set.of("IF", "TERNARY", "LOOP", "SWITCH", "TRY", "SHORT_CIRCUIT", "ASSERT",
            "SWITCH_EXPR", "PATTERN_GUARD", "LAMBDA_PREDICATE");
    private static final Pattern GUARD = Pattern.compile("@(\\d+):\\d+-\\d+:([a-z-]+)#(.*)$");

    private OutcomeCoverage() {}

    public static Result compute(AnalysisModel model) {
        List<Entity> decisions = model.entitiesOfType(EntityType.DECISION);
        if (decisions.isEmpty()) {
            return new Result(false, 0, 0, Map.of(), List.of(), 0, Map.of(), List.of());
        }
        ModelIndex index = new ModelIndex(model);
        Map<String, Integer> available = capturedKeys(model);
        Map<String, List<Entity>> pathDecisions = pathDecisions(model);
        Map<String, Integer> pairing = new HashMap<>();

        Map<String, int[]> byKind = new TreeMap<>();
        Map<String, int[]> pathByKind = new TreeMap<>();
        List<Outcome> missing = new ArrayList<>();
        List<PathGap> pathMissing = new ArrayList<>();
        int total = 0;
        int captured = 0;
        int pathModelled = 0;
        List<Entity> ordered = new ArrayList<>(decisions);
        ordered.sort(Comparator.comparing((Entity d) -> d.evidence().get(0).location().file())
                .thenComparingInt(d -> d.evidence().get(0).location().startLine())
                .thenComparing(d -> d.attributes().get("kind")).thenComparingInt(OutcomeCoverage::sequence)
                .thenComparing(d -> d.id().value()));
        for (Entity d : ordered) {
            String kind = d.attributes().get("kind");
            SourceLocation loc = d.evidence().get(0).location();
            String group = loc.file() + "|" + loc.startLine() + "|" + kind;
            int ordinal = pairing.merge(group, 1, Integer::sum) - 1;
            List<Entity> candidates = pathDecisions.getOrDefault(group, List.of());
            Set<String> pathLabels = new HashSet<>();
            if (ordinal < candidates.size()) {
                for (String label : candidates.get(ordinal).attributes().getOrDefault("outcomes", "").split(" \\|\\| ")) {
                    pathLabels.add(label(kind, label));
                }
            }
            for (String outcome : d.attributes().getOrDefault("outcomes", "").split(" \\|\\| ")) {
                Outcome o = new Outcome(loc.file(), loc.startLine(), kind, outcome);
                int[] counts = byKind.computeIfAbsent(kind, k -> new int[2]);
                int[] pathCounts = pathByKind.computeIfAbsent(kind, k -> new int[2]);
                counts[0]++;
                pathCounts[0]++;
                total++;

                String engineKind = MODELLED.get(kind);
                String key = engineKind == null ? null : key(loc.file(), loc.startLine(), engineKind, normalise(outcome));
                if (key != null && available.getOrDefault(key, 0) > 0) {
                    available.merge(key, -1, Integer::sum);
                    counts[1]++;
                    captured++;
                } else {
                    missing.add(o);
                }

                if (pathLabels.contains(label(kind, outcome))) {
                    pathCounts[1]++;
                    pathModelled++;
                } else if (!PATH_KINDS.contains(kind)) {
                    pathMissing.add(new PathGap(o, "this kind of decision is not modelled yet", true));
                } else {
                    pathMissing.add(new PathGap(o, "missing from the path model", false));
                }
            }
        }
        return new Result(true, total, captured, byKind, missing, pathModelled, pathByKind, pathMissing);
    }

    /** True when the rules stage has a model for this kind of decision. */
    public static boolean modelled(String censusKind) {
        return MODELLED.containsKey(censusKind);
    }

    /** True when the path model supports this kind of decision. */
    public static boolean pathSupported(String censusKind) {
        return PATH_KINDS.contains(censusKind);
    }

    // ---------------------------------------------------------------- path model side

    private static Map<String, List<Entity>> pathDecisions(AnalysisModel model) {
        Map<String, List<Entity>> groups = new HashMap<>();
        for (Entity d : model.entitiesOfType(EntityType.PATH_DECISION)) {
            SourceLocation loc = d.evidence().get(0).location();
            groups.computeIfAbsent(loc.file() + "|" + loc.startLine() + "|" + d.attributes().get("kind"), k -> new ArrayList<>()).add(d);
        }
        // source order; for two operators that start together the outer one (the longer one) comes first, as in the census
        groups.values().forEach(list -> list.sort(Comparator.comparingInt((Entity e) -> Integer.parseInt(e.attributes().getOrDefault("col", "0")))
                .thenComparing(e -> -Integer.parseInt(e.attributes().getOrDefault("end", "0")))
                .thenComparing(e -> e.id().value())));
        return groups;
    }

    /** The decision's order inside its unit: the number in {@code decision:<unit>:<seq>:<line>:<kind>}. */
    private static int sequence(Entity decision) {
        String[] parts = decision.id().value().split(":");
        try {
            return Integer.parseInt(parts[parts.length - 3]);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** Labels compared across tools: a catch is "catch A | B" by simple names, anything else is normalised as a case label. */
    static String label(String kind, String label) {
        if (label.startsWith("catch ")) {
            StringBuilder names = new StringBuilder("catch ");
            String[] types = label.substring(6).split("\\|");
            for (int i = 0; i < types.length; i++) {
                String t = types[i].trim();
                names.append(i == 0 ? "" : " | ").append(t.substring(t.lastIndexOf('.') + 1));
            }
            return names.toString();
        }
        return normalise(label);
    }

    // ---------------------------------------------------------------- rule side

    private static Map<String, Integer> capturedKeys(AnalysisModel model) {
        Map<String, Integer> keys = new HashMap<>();
        for (Entity rule : model.entitiesOfType(EntityType.BUSINESS_RULE)) {
            String guards = rule.attributes().get("guards");
            if (guards == null || rule.evidence().isEmpty() || rule.evidence().get(0).location() == null) {
                continue;
            }
            String file = rule.evidence().get(0).location().file();
            for (String guard : guards.split(" ;; ")) {
                Matcher m = GUARD.matcher(guard);
                if (m.find()) {
                    for (String label : m.group(3).split("\\|")) {        // "1 | 2" is two source outcomes
                        keys.put(key(file, Integer.parseInt(m.group(1)), m.group(2), normalise(label)), 1);   // credited once, however many rules
                    }
                }
            }
        }
        return keys;
    }

    private static String key(String file, int line, String kind, String arm) {
        return file + "|" + line + "|" + kind + "|" + arm;
    }

    /**
     * Case labels as the tools print them ({@code Status.OPEN}, {@code 1 | 2}) and as the source has them
     * ({@code OPEN}, {@code 1}): drop spaces and an enum qualifier so the two can be compared.
     */
    static String normalise(String arm) {
        String a = arm.replaceAll("\\s+", "");
        if (a.matches("[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)+")) {
            return a.substring(a.lastIndexOf('.') + 1);
        }
        return a;
    }
}
