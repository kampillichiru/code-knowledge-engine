package com.chiru.engine.sql.analysis;

import java.util.Locale;

/** Identifier normalisation: unquoted and lower case. */
public final class Names {

    /** Prefix of the neutral token that stands in for a runtime-built part of a SQL string. */
    public static final String DYNAMIC_PREFIX = "__dyn";

    private Names() {}

    public static String norm(String name) {
        if (name == null) {
            return null;
        }
        String n = name.trim();
        if (n.length() >= 2) {
            char first = n.charAt(0);
            char last = n.charAt(n.length() - 1);
            if ((first == '"' && last == '"') || (first == '`' && last == '`') || (first == '[' && last == ']')) {
                n = n.substring(1, n.length() - 1);
            }
        }
        return n.toLowerCase(Locale.ROOT);
    }

    public static boolean isDynamic(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).startsWith(DYNAMIC_PREFIX);
    }
}
