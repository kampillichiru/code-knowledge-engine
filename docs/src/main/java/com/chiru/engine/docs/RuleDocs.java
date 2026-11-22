package com.chiru.engine.docs;

import com.chiru.engine.model.*;
import java.util.*;

/** 04 business rules. */
final class RuleDocs {

    private RuleDocs() {}

    static String businessRules(DocContext c) {
        StringBuilder sb = new StringBuilder(c.header("04 Business rules",
                "Candidate rules found in the code: conditions, calculations and what they change."));
        sb.append("> A rule here is **structure found in the code**: a condition (or none) and the value it changes. "
                + "The engine does not name or explain rules. Rules that are not VERIFIED depend on a call outside the "
                + "analysed sources and need human review.\n\n");

        List<Entity> rules = new ArrayList<>(c.of(EntityType.BUSINESS_RULE));
        rules.sort(Comparator.comparing(r -> c.ruleLabel(r.id().value())));
        if (rules.isEmpty()) {
            return sb.append("_No business rule was extracted._\n").toString();
        }

        Map<String, TreeSet<String>> fieldsOfRule = new HashMap<>();
        for (Entity t : c.of(EntityType.TRANSFORMATION)) {
            String ruleIds = c.attr(t, "rules");
            if (ruleIds == null) {
                continue;
            }
            for (Relationship owner : c.index.in(t.id(), RelationType.DERIVED_FROM)) {
                if (owner.from().type() == EntityType.OUTPUT_FIELD) {
                    for (String id : ruleIds.split(", ")) {
                        fieldsOfRule.computeIfAbsent(id, k -> new TreeSet<>()).add(c.name(owner.from()));
                    }
                }
            }
        }

        sb.append(Md.h(2, "Index"));
        List<List<String>> index = new ArrayList<>();
        for (Entity r : rules) {
            index.add(c.withModule(c.moduleOf(r), List.of(c.ruleLabel(r.id().value()), Optional.ofNullable(c.attr(r, "kind")).orElse(Md.NONE),
                    c.method(c.attr(r, "method")), Optional.ofNullable(c.attr(r, "traceStatus")).orElse("UNRESOLVED"), c.loc(r))));
        }
        sb.append(Md.table(c.headers("ID", "Kind", "Method", "Status", "Evidence"), index));

        sb.append(Md.h(2, "Rules"));
        for (Entity r : rules) {
            sb.append(Md.h(3, c.ruleLabel(r.id().value()) + " " + r.name()));
            sb.append(Md.bullet("Model ID", Md.code(r.id().value())));
            sb.append(Md.bullet("Kind", c.attr(r, "kind")));
            sb.append(Md.bullet("Where", Md.code(c.keyOf(c.attr(r, "method"))) + " at " + c.loc(r)));
            sb.append(Md.bullet("Trigger condition", c.attr(r, "conditions") == null ? "none (unconditional)"
                    : Md.code(c.attr(r, "conditions"))));
            sb.append(Md.bullet("Inputs read by the condition", c.attr(r, "reads")));
            sb.append(Md.bullet("Calculation / effect", Md.code(c.attr(r, "effects"))));
            sb.append(Md.bullet("Constants used", c.attr(r, "constants")));
            sb.append(Md.bullet("Literals", c.attr(r, "literals")));
            TreeSet<String> fields = fieldsOfRule.get(r.id().value());
            sb.append(Md.bullet("Output fields affected", fields == null ? "none traced" : String.join(", ", fields)));
            Optional<Entity> own = c.scenarioOf(r.id());
            Optional<Entity> viaMethod = c.scenarioOfId(c.attr(r, "method"));
            sb.append(Md.bullet("Scenario", own.or(() -> viaMethod).map(Entity::name).orElse("not part of a scenario")));
            String status = Optional.ofNullable(c.attr(r, "traceStatus")).orElse("UNRESOLVED");
            String review = "PARTIAL".equals(status)
                    ? " (needs human review: a condition or value passes through a call outside the analysed sources)" : "";
            sb.append(Md.bullet("Status", status + review));
            List<String> ev = r.evidence().stream().map(e -> c.loc(e.location()) + " (" + e.note() + ")").distinct().toList();
            sb.append(Md.bullet("Evidence", String.join("; ", ev)));
            sb.append('\n');
        }
        return sb.toString();
    }
}
