package com.chiru.engine.lineage;

import com.chiru.engine.core.AnalysisStage;
import com.chiru.engine.core.StageContext;
import com.chiru.engine.model.*;
import java.util.List;

/**
 * Phase 7a, the part that needs SQL facts: a SQL result value that the Java code passes straight into a call or
 * constructor argument ({@code flowsTo = method:...#argN}) is linked to the callee's parameter:
 * {@code parameter DERIVED_FROM OUT PARAMETER}. Together with the argument bindings written by {@code source-spoon}
 * this lets a trace continue from a field assigned in a constructor back to the table column it was read from.
 *
 * Run after {@code source-spoon} and {@code sql}. It reads and writes the model only.
 */
public final class BindingsStage implements AnalysisStage {

    public static final String NAME = "bindings";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void run(StageContext ctx) {
        AnalysisModel model = ctx.model();
        int bound = 0;
        int unbound = 0;
        for (Entity out : model.entitiesOfType(EntityType.PARAMETER)) {
            String flowsTo = out.attributes().get("flowsTo");
            if (!"OUT".equals(out.attributes().get("direction")) || flowsTo == null) {
                continue;
            }
            for (String target : flowsTo.split(" \\| ")) {
                int cut = target.lastIndexOf("#arg");
                if (cut < 0) {
                    unbound++;
                    continue;
                }
                EntityId callee = new EntityId(target.substring(0, cut));
                String index = target.substring(cut + "#arg".length());
                EntityId parameter = model.entity(callee).isEmpty() ? null : model.outgoing(callee, RelationType.CONTAINS)
                        .stream().map(Relationship::to).filter(id -> id.type() == EntityType.VARIABLE)
                        .filter(id -> model.entity(id).map(e -> "parameter".equals(e.attributes().get("kind"))
                                && index.equals(e.attributes().get("index"))).orElse(false))
                        .findFirst().orElse(null);
                if (parameter == null) {
                    unbound++;
                    continue;
                }
                Evidence from = out.evidence().stream().skip(1).findFirst().orElse(out.evidence().get(0));
                model.addRelationship(new Relationship(parameter, RelationType.DERIVED_FROM, out.id(),
                        TraceStatus.VERIFIED, List.of(new Evidence(from.location(), NAME, Certainty.EXTRACTED,
                                "SQL result '" + out.attributes().get("label") + "' passed as argument " + index))));
                bound++;
            }
        }
        model.addWarning(new AnalysisWarning(NAME, AnalysisWarning.Severity.INFO, bound
                + " SQL result value(s) linked to the parameter they are passed into"
                + (unbound == 0 ? "" : "; " + unbound + " flow target(s) are outside the analysed sources and not linked"),
                null));
    }
}
