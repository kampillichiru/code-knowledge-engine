package com.chiru.engine.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Read-only adjacency index over a model, for stages and generators that walk edges a lot. {@link AnalysisModel}
 * scans every relationship for each {@code outgoing}/{@code incoming} call; this index is built once.
 * It is a snapshot: edges added to the model afterwards are not seen.
 */
public final class ModelIndex {

    private final AnalysisModel model;
    private final Map<EntityId, List<Relationship>> outgoing = new HashMap<>();
    private final Map<EntityId, List<Relationship>> incoming = new HashMap<>();

    public ModelIndex(AnalysisModel model) {
        this.model = model;
        for (Relationship r : model.relationships()) {
            outgoing.computeIfAbsent(r.from(), k -> new ArrayList<>()).add(r);
            incoming.computeIfAbsent(r.to(), k -> new ArrayList<>()).add(r);
        }
    }

    public AnalysisModel model() {
        return model;
    }

    public List<Relationship> out(EntityId id, RelationType type) {
        return outgoing.getOrDefault(id, List.of()).stream().filter(r -> r.type() == type).toList();
    }

    public List<Relationship> in(EntityId id, RelationType type) {
        return incoming.getOrDefault(id, List.of()).stream().filter(r -> r.type() == type).toList();
    }

    public Optional<Entity> find(EntityId id) {
        return model.entity(id);
    }

    public Entity entity(EntityId id) {
        return model.entity(id).orElseThrow(() -> new IllegalStateException("unknown entity " + id));
    }

    /** Value of an attribute, or null. */
    public static String attr(Entity e, String key) {
        return e.attributes().get(key);
    }

    /** Value of an attribute, or {@code fallback}. */
    public static String attr(Entity e, String key, String fallback) {
        return e.attributes().getOrDefault(key, fallback);
    }

    /** The natural key of an ID: {@code method:a.B#c()} gives {@code a.B#c()}. */
    public static String key(EntityId id) {
        return id.value().substring(id.value().indexOf(':') + 1);
    }

    /** The first evidence location of an entity as {@code file:start-end}, or null when it has none. */
    public static SourceLocation location(Entity e) {
        return e.evidence().stream().map(Evidence::location).filter(l -> l != null).findFirst().orElse(null);
    }

    /** The method that (transitively) contains {@code id}: arguments and parts sit inside expressions and calls. */
    public EntityId ownerMethod(EntityId id) {
        Deque<EntityId> queue = new ArrayDeque<>(List.of(id));
        Set<EntityId> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            EntityId cur = queue.poll();
            if (!seen.add(cur)) {
                continue;
            }
            if (cur.type() == EntityType.METHOD) {
                return cur;
            }
            in(cur, RelationType.CONTAINS).stream().map(Relationship::from)
                    .filter(f -> f.type() == EntityType.METHOD || f.type() == EntityType.EXPRESSION
                            || f.type() == EntityType.INVOCATION).forEach(queue::add);
        }
        return null;
    }

    /** {@code method:com.x.Foo#bar(int)} as {@code Foo.bar}; other IDs as their key. */
    public static String shortName(EntityId id) {
        String key = key(id);
        int hash = key.indexOf('#');
        if (hash < 0) {
            return key.substring(key.lastIndexOf('.') + 1);
        }
        String owner = key.substring(0, hash);
        int paren = key.indexOf('(', hash);
        String member = key.substring(hash + 1, paren < 0 ? key.length() : paren);
        return owner.substring(owner.lastIndexOf('.') + 1) + "." + member.replace("<init>", "constructor");
    }
}
