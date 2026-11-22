package com.chiru.engine.sql.analysis;

import java.util.List;

/**
 * Structured result of analysing one SQL statement. Names are normalised (unquoted, lower case).
 * Anything the analyser could not interpret is listed in {@code unsupported}; it is never dropped silently.
 */
public record SqlAnalysis(
        String type,
        List<TableUse> tables,
        List<ColumnUse> columns,
        List<Param> params,
        List<SelectItemInfo> selectItems,
        List<String> insertColumns,
        List<String> joins,
        List<String> ctes,
        int subqueries,
        List<String> functions,
        List<String> aggregates,
        List<String> routines,
        List<String> groupBy,
        List<String> orderBy,
        List<String> predicates,
        boolean dynamic,
        List<String> unsupported) {

    public enum Access { READ, INSERT, UPDATE, DELETE }

    /** A base table the statement touches. {@code alias} is null when none was written. */
    public record TableUse(String name, String alias, Access access) {}

    /** A column access. {@code table} is null when the owning table could not be established; see {@code note}. */
    public record ColumnUse(String table, String column, Access access, String role, String note) {
        public boolean resolved() {
            return table != null;
        }
    }

    /**
     * A bind parameter. {@code column} ("table.column") is the column it is compared with or assigned to, when the
     * SQL says so. {@code direct} is false when the parameter sits inside a larger expression.
     */
    public record Param(int index, String name, String column, String operator, String role, boolean direct) {}

    /** One output column of the statement. {@code sources} are the resolved "table.column" it is computed from. */
    public record SelectItemInfo(String label, String expression, List<String> sources, boolean star, String note) {}

    public boolean isDml() {
        return switch (type) {
            case "SELECT", "INSERT", "UPDATE", "DELETE", "CALL" -> true;
            default -> false;
        };
    }
}
