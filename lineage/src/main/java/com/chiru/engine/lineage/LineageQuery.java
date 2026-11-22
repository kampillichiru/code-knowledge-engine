package com.chiru.engine.lineage;

import com.chiru.engine.model.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Questions over a finished model, with no re-analysis: reverse ("how is this output field derived?") and forward
 * ("which output fields does this table column feed?"). Works on a model loaded from {@code analysis-model.json}.
 */
public final class LineageQuery {

    private LineageQuery() {}

    /** One trace of an output field. */
    public record Trace(EntityId id, String scenario, TraceStatus status, String terminalKind, String terminal,
                        String rules, String steps) {}

    /** An output field and all its traces. */
    public record FieldLineage(Entity field, List<Trace> traces) {}

    /** An output field fed by a column, through one trace. */
    public record Impact(Entity field, Trace trace) {}

    /** Reverse: the output field with this name (or full ID), with its traces; empty when there is none. */
    public static Optional<FieldLineage> reverse(AnalysisModel model, String fieldNameOrId) {
        ModelIndex index = new ModelIndex(model);
        return model.entitiesOfType(EntityType.OUTPUT_FIELD).stream()
                .filter(f -> f.name().equals(fieldNameOrId) || f.id().value().equals(fieldNameOrId))
                .findFirst().map(f -> new FieldLineage(f, tracesOf(index, f)));
    }

    /** Forward: every output field fed by the column ({@code table.column}, or just the column name). */
    public static List<Impact> forward(AnalysisModel model, String column) {
        ModelIndex index = new ModelIndex(model);
        List<Impact> impacts = new ArrayList<>();
        for (Entity c : model.entitiesOfType(EntityType.COLUMN)) {
            boolean matches = ModelIndex.key(c.id()).equals(column) || (!column.contains(".") && c.name().equals(column));
            if (!matches) {
                continue;
            }
            for (Relationship r : index.in(c.id(), RelationType.DERIVED_FROM)) {
                if (r.from().type() != EntityType.TRANSFORMATION) {
                    continue;
                }
                for (Relationship owner : index.in(r.from(), RelationType.DERIVED_FROM)) {
                    if (owner.from().type() == EntityType.OUTPUT_FIELD) {
                        impacts.add(new Impact(index.entity(owner.from()), trace(index.entity(r.from()), owner.status())));
                    }
                }
            }
        }
        impacts.sort(Comparator.comparing((Impact i) -> i.field().id().value()).thenComparing(i -> i.trace().id().value()));
        return impacts;
    }

    static List<Trace> tracesOf(ModelIndex index, Entity field) {
        List<Trace> traces = new ArrayList<>();
        for (Relationship r : index.out(field.id(), RelationType.DERIVED_FROM)) {
            if (r.to().type() == EntityType.TRANSFORMATION) {
                traces.add(trace(index.entity(r.to()), r.status()));
            }
        }
        return traces;
    }

    private static Trace trace(Entity t, TraceStatus status) {
        return new Trace(t.id(), ModelIndex.attr(t, "scenario", ""), status, ModelIndex.attr(t, "terminalKind", ""),
                ModelIndex.attr(t, "terminal", ""), ModelIndex.attr(t, "rules", ""), ModelIndex.attr(t, "steps", ""));
    }
}
