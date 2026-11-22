package com.chiru.engine.docs;

import com.chiru.engine.model.*;
import com.chiru.engine.validation.OutcomeCoverage;
import java.util.*;

/** 14 decision paths: every execution path of every method that branches. */
final class PathDocs {

    private static final int MAX_METHODS = 300;

    private PathDocs() {}

    static String decisionPaths(DocContext c) {
        StringBuilder sb = new StringBuilder(c.header("14 Decision paths",
                "Every execution path of every method, lambda or initialiser that has a decision: when it is taken, what it does in order, how it ends."));
        sb.append("> A **path** is one way through a method: the conditions that select it, the effects it performs in order, and how it ends "
                + "(`RETURN`, `THROW`, or falling off the end). This is what makes guard clauses visible: after `if (x < 0) return -1;` "
                + "the rest of the method appears under `not (x < 0)`, never as unconditional.\n>\n"
                + "> **Abstractions to know.** A switch expression used as a value (a return, a declaration, an assignment) gives one path per case and a `yield` ends its case; one used as a call argument or operand is counted but not split. Lambdas and initialisers (field initialisers, static and instance blocks) are listed under their own heading. A loop is taken as zero passes or at least one pass through its body, then on. A `break` leaves the "
                + "loop and a `continue` ends the pass. A `finally` block's effects are added to every way out of its `try`. A method with many "
                + "sequential decisions has too many paths to list: it is cut at a cap and marked, but all its decisions are still counted. "
                + "Values are shown as written (`return x`), not yet substituted through the effects.\n\n");

        Map<String, List<Entity>> byMethod = new TreeMap<>();
        for (Entity p : c.of(EntityType.PATH)) {
            byMethod.computeIfAbsent(p.attributes().get("method") + "|" + Optional.ofNullable(p.attributes().get("unit")).orElse(""), k -> new ArrayList<>()).add(p);
        }
        int paths = byMethod.values().stream().mapToInt(List::size).sum();
        long capped = c.of(EntityType.METHOD).stream().filter(m -> "true".equals(c.attr(m, "pathsCapped"))).count();
        sb.append(Md.h(2, "Summary"));
        sb.append(Md.bullet("Methods, lambdas and initialisers that branch", String.valueOf(byMethod.size())));
        sb.append(Md.bullet("Paths listed", String.valueOf(paths)));
        sb.append(Md.bullet("Methods whose list of paths is cut at the cap", String.valueOf(capped)));
        sb.append(Md.bullet("Decision points / outcomes modelled", c.of(EntityType.PATH_DECISION).size() + " / " + c.of(EntityType.PATH_DECISION).stream()
                .mapToInt(d -> c.attr(d, "outcomes").split(" \\|\\| ").length).sum()));
        sb.append('\n');

        OutcomeCoverage.Result coverage = OutcomeCoverage.compute(c.model);
        sb.append(Md.h(2, "What the path model does not contain yet"));
        if (!coverage.censusAvailable()) {
            sb.append("_The syntax census did not run, so there is nothing to compare the path model with._\n\n");
        } else if (coverage.pathMissing().isEmpty()) {
            sb.append("_Every decision outcome counted in the source is in the path model._\n\n");
        } else {
            Map<String, Integer> counts = new TreeMap<>();
            Map<String, String> example = new TreeMap<>();
            for (OutcomeCoverage.PathGap g : coverage.pathMissing()) {
                String key = g.outcome().kind() + " | " + g.reason() + (g.expected() ? "" : " (DEFECT)");
                counts.merge(key, 1, Integer::sum);
                example.putIfAbsent(key, g.outcome().toString());
            }
            List<List<String>> rows = new ArrayList<>();
            counts.forEach((key, n) -> rows.add(List.of(key.substring(0, key.indexOf(" | ")), key.substring(key.indexOf(" | ") + 3),
                    String.valueOf(n), example.get(key))));
            sb.append("Outcomes in the source that the path model does not hold, from the independent syntax census. ")
                    .append(coverage.pathModelled()).append(" of ").append(coverage.total()).append(" are in the model.\n\n");
            sb.append(Md.table(List.of("Decision kind", "Why", "Outcomes", "First example"), rows));
        }

        sb.append(Md.h(2, "Methods"));
        if (byMethod.isEmpty()) {
            sb.append("_No method has a decision._\n");
            return sb.toString();
        }
        int shown = 0;
        for (Map.Entry<String, List<Entity>> e : byMethod.entrySet()) {
            if (shown++ >= MAX_METHODS) {
                sb.append("_").append(byMethod.size() - MAX_METHODS).append(" more method(s) not shown; see the model._\n");
                break;
            }
            String[] parts = e.getKey().split("\\|", 2);
            EntityId id = new EntityId(parts[0]);
            Entity method = c.index.entity(id);
            String unit = parts.length > 1 ? parts[1] : "";
            sb.append(Md.h(3, ModelIndex.shortName(id) + (unit.isEmpty() ? "" : " — " + unit)));
            sb.append(Md.bullet(id.type() == EntityType.METHOD ? "Method" : "Class", Md.code(ModelIndex.key(id)) + " at " + c.loc(method)
                    + (unit.isEmpty() ? "" : " (" + unit + ")")));
            if ("true".equals(c.attr(method, "pathsCapped"))) {
                sb.append(Md.bullet("Note", "the list of paths is cut at the cap; the decisions are all counted"));
            }
            sb.append('\n');
            List<Entity> paths2 = new ArrayList<>(e.getValue());
            paths2.sort(Comparator.comparingInt(p -> Integer.parseInt(c.attr(p, "number"))));
            List<List<String>> rows = new ArrayList<>();
            for (Entity p : paths2) {
                String exit = c.attr(p, "exit");
                String ends = exit.equals("FALL") ? "falls off the end" : exit.toLowerCase(Locale.ROOT) + (c.attr(p, "value") == null ? "" : " " + c.attr(p, "value"));
                rows.add(List.of(c.attr(p, "number"), c.attr(p, "when"), c.attr(p, "then"), ends, c.loc(p)));
            }
            sb.append(Md.table(List.of("#", "When", "Then (in order)", "Ends", "Evidence"), rows));
        }
        return sb.toString();
    }
}
