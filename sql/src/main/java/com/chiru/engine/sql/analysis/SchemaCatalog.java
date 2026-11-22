package com.chiru.engine.sql.analysis;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Tables and columns known from schema evidence (DDL or ORM mappings). Used to resolve unqualified columns;
 * when a table is not in the catalog the analyser does not pretend to know its columns.
 */
public final class SchemaCatalog {

    private final Map<String, Map<String, String>> tables = new LinkedHashMap<>();

    public void addColumn(String table, String column, String type) {
        tables.computeIfAbsent(Names.norm(table), k -> new LinkedHashMap<>()).putIfAbsent(Names.norm(column), type);
    }

    public void addTable(String table) {
        tables.computeIfAbsent(Names.norm(table), k -> new LinkedHashMap<>());
    }

    public boolean knowsTable(String table) {
        return tables.containsKey(Names.norm(table));
    }

    public boolean hasColumn(String table, String column) {
        Map<String, String> cols = tables.get(Names.norm(table));
        return cols != null && cols.containsKey(Names.norm(column));
    }

    /** Columns of a known table in declaration order (empty when unknown). */
    public Set<String> columns(String table) {
        Map<String, String> cols = tables.get(Names.norm(table));
        return cols == null ? Set.of() : new LinkedHashSet<>(cols.keySet());
    }
}
