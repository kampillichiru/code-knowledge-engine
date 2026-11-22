package com.chiru.engine.model;

import java.util.List;
import java.util.Objects;

/** A typed, directed edge: {@code from --type--> to}, with a completeness status and evidence. */
public record Relationship(
        EntityId from,
        RelationType type,
        EntityId to,
        TraceStatus status,
        List<Evidence> evidence) {

    public Relationship {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(status, "status");
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    /** Identity of the edge, ignoring status and evidence. */
    public String key() {
        return from + " -" + type + "-> " + to;
    }
}
