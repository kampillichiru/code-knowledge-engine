package com.chiru.engine.model;

public enum RelationType {
    CONTAINS, EXTENDS, IMPLEMENTS, DEPENDS_ON,
    CALLS, INVOKES,
    /** A method runs a SQL statement. */
    EXECUTES,
    /** SQL statement reads a table (FROM/JOIN) or a column (select list, predicate, join, group/order). */
    READS_TABLE, READS_COLUMN,
    /** SQL statement targets a table with INSERT, UPDATE or DELETE. */
    INSERTS_INTO, UPDATES_TABLE, DELETES_FROM,
    /** SQL statement writes a specific column (INSERT column list, UPDATE SET). */
    INSERTS_COLUMN, UPDATES_COLUMN,
    BINDS_PARAMETER,
    DERIVED_FROM, GUARDED_BY, APPLIES_RULE, PART_OF_SCENARIO, PRODUCES
}
