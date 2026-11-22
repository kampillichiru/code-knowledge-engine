package com.chiru.engine.model;

import java.util.Objects;

/**
 * Stable, deterministic identity of an entity: {@code <type prefix>:<natural key>}, for example
 * {@code method:com.x.Foo#bar(int)}. The same source always yields the same ID.
 */
public record EntityId(String value) {

    public EntityId {
        Objects.requireNonNull(value, "value");
        int colon = value.indexOf(':');
        if (colon < 1 || colon == value.length() - 1) {
            throw new IllegalArgumentException("EntityId must look like 'type:key' but was '" + value + "'");
        }
    }

    public static EntityId of(EntityType type, String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key must not be blank");
        }
        return new EntityId(type.prefix() + ":" + key);
    }

    public EntityType type() {
        String prefix = value.substring(0, value.indexOf(':'));
        for (EntityType t : EntityType.values()) {
            if (t.prefix().equals(prefix)) {
                return t;
            }
        }
        throw new IllegalStateException("Unknown entity type prefix: " + prefix);
    }

    @Override
    public String toString() {
        return value;
    }
}
