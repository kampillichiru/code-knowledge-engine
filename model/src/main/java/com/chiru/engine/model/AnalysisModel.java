package com.chiru.engine.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The single intermediate representation shared by all analysis stages. Stages add entities and
 * relationships; documentation is generated from this model, never from raw parser output.
 */
public final class AnalysisModel {

    public static final int SCHEMA_VERSION = 1;

    private final Map<EntityId, Entity> entities = new LinkedHashMap<>();
    private final Map<String, Relationship> relationships = new LinkedHashMap<>();
    private final List<AnalysisWarning> warnings = new ArrayList<>();

    /**
     * Adds an entity, or merges it into the existing one with the same ID: evidence is accumulated and
     * new attributes are added. A conflicting attribute value keeps the existing one and records a warning.
     */
    public Entity putEntity(Entity incoming) {
        Entity existing = entities.get(incoming.id());
        if (existing == null) {
            entities.put(incoming.id(), incoming);
            return incoming;
        }
        Map<String, String> merged = new LinkedHashMap<>(existing.attributes());
        incoming.attributes().forEach((k, v) -> {
            String old = merged.putIfAbsent(k, v);
            if (old != null && !old.equals(v)) {
                addWarning(new AnalysisWarning("model", AnalysisWarning.Severity.WARNING,
                        "Conflicting attribute '" + k + "' on " + incoming.id() + ": kept '" + old
                                + "', ignored '" + v + "'", null));
            }
        });
        List<Evidence> evidence = new ArrayList<>(existing.evidence());
        incoming.evidence().stream().filter(e -> !evidence.contains(e)).forEach(evidence::add);
        Entity result = new Entity(existing.id(), existing.name(), merged, evidence);
        entities.put(result.id(), result);
        return result;
    }

    /**
     * Adds a relationship between two existing entities. A repeated edge accumulates evidence and
     * takes the weaker status, so repeating a link can never strengthen it.
     */
    public Relationship addRelationship(Relationship incoming) {
        requireEntity(incoming.from());
        requireEntity(incoming.to());
        Relationship existing = relationships.get(incoming.key());
        if (existing == null) {
            relationships.put(incoming.key(), incoming);
            return incoming;
        }
        List<Evidence> evidence = new ArrayList<>(existing.evidence());
        incoming.evidence().stream().filter(e -> !evidence.contains(e)).forEach(evidence::add);
        Relationship merged = new Relationship(existing.from(), existing.type(), existing.to(),
                TraceStatus.weakest(existing.status(), incoming.status()), evidence);
        relationships.put(merged.key(), merged);
        return merged;
    }

    public void addWarning(AnalysisWarning warning) {
        warnings.add(warning);
    }

    public Optional<Entity> entity(EntityId id) {
        return Optional.ofNullable(entities.get(id));
    }

    public List<Entity> entitiesOfType(EntityType type) {
        return entities.values().stream().filter(e -> e.type() == type).toList();
    }

    public List<Entity> entities() {
        return List.copyOf(entities.values());
    }

    public List<Relationship> relationships() {
        return List.copyOf(relationships.values());
    }

    public List<Relationship> outgoing(EntityId from, RelationType type) {
        return relationships.values().stream()
                .filter(r -> r.from().equals(from) && r.type() == type).toList();
    }

    public List<Relationship> incoming(EntityId to, RelationType type) {
        return relationships.values().stream()
                .filter(r -> r.to().equals(to) && r.type() == type).toList();
    }

    public List<AnalysisWarning> warnings() {
        return List.copyOf(warnings);
    }

    /** Entities and relationships that carry no evidence. Every fact must have provenance. */
    public List<String> findProvenanceGaps() {
        List<String> gaps = new ArrayList<>();
        entities.values().stream().filter(e -> e.evidence().isEmpty())
                .forEach(e -> gaps.add("Entity without evidence: " + e.id()));
        relationships.values().stream().filter(r -> r.evidence().isEmpty())
                .forEach(r -> gaps.add("Relationship without evidence: " + r.key()));
        return gaps;
    }

    public ModelSnapshot snapshot() {
        return new ModelSnapshot(SCHEMA_VERSION, entities(), relationships(), warnings());
    }

    public static AnalysisModel fromSnapshot(ModelSnapshot snapshot) {
        if (snapshot.schemaVersion() != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported model schema version "
                    + snapshot.schemaVersion() + " (expected " + SCHEMA_VERSION + ")");
        }
        AnalysisModel model = new AnalysisModel();
        snapshot.entities().forEach(model::putEntity);
        snapshot.relationships().forEach(model::addRelationship);
        snapshot.warnings().forEach(model::addWarning);
        return model;
    }

    private void requireEntity(EntityId id) {
        if (!entities.containsKey(id)) {
            throw new IllegalArgumentException("Relationship references unknown entity: " + id);
        }
    }
}
