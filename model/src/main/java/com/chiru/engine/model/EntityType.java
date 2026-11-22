package com.chiru.engine.model;

import java.util.Locale;

public enum EntityType {
    REPOSITORY, MODULE, FILE, PACKAGE, CLASS, METHOD, INVOCATION, VARIABLE, FIELD, EXPRESSION, BRANCH,
    SQL_STATEMENT, TABLE, COLUMN, PARAMETER,
    /** A method, constructor, initialiser or lambda as seen by the syntax census (parse only). */
    SYNTAX_UNIT,
    /** One decision point in the source (if, switch, loop, catch, &&, ...) with its outcomes. */
    DECISION,
    /** One execution path through a method: the conditions that select it, its ordered effects and how it exits. */
    PATH,
    /** A decision point as the Spoon-based path model sees it (compared with the syntax census). */
    PATH_DECISION,
    BUSINESS_RULE, SCENARIO, TRANSFORMATION,
    OUTPUT_FILE, OUTPUT_FIELD;

    /** Prefix used in stable IDs, e.g. {@code method}. */
    public String prefix() {
        return name().toLowerCase(Locale.ROOT);
    }
}
