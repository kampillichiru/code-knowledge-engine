package com.chiru.engine.core;

import java.util.List;

/**
 * Library types whose methods only transform their inputs and hide no source of data (no I/O, no clock reads that
 * matter to lineage, no randomness). A call to one is treated as a plain transformation, so it does not make a rule or
 * a trace PARTIAL. Everything else outside the analysed sources (JDBC, {@code System}, HTTP clients, ...) stays PARTIAL.
 * One list is shared by the rule extractor and the lineage tracer so they can never disagree about the same call.
 */
public final class PureLibraries {

    public static final List<String> DEFAULT_PREFIXES = List.of("java.math.", "java.lang.String", "java.lang.Math",
            "java.lang.Long", "java.lang.Integer", "java.lang.Double", "java.lang.Boolean", "java.lang.Character",
            "java.time.");

    private PureLibraries() {}

    public static boolean isPure(String ownerType, List<String> prefixes) {
        return ownerType != null && prefixes.stream().anyMatch(ownerType::startsWith);
    }

    public static boolean isPure(String ownerType) {
        return isPure(ownerType, DEFAULT_PREFIXES);
    }
}
