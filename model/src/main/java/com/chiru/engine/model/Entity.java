package com.chiru.engine.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** A node in the analysis model. Immutable; use {@link #withEvidence} / {@link AnalysisModel#putEntity} to extend. */
public record Entity(
        EntityId id,
        String name,
        Map<String, String> attributes,
        List<Evidence> evidence) {

    public Entity {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    public EntityType type() {
        return id.type();
    }

    public Entity withEvidence(Evidence extra) {
        return new Entity(id, name, attributes, append(evidence, extra));
    }

    static <T> List<T> append(List<T> list, T item) {
        if (list.contains(item)) {
            return list;
        }
        var copy = new java.util.ArrayList<>(list);
        copy.add(item);
        return copy;
    }
}
