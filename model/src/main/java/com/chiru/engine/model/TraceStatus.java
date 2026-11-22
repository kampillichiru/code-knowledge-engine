package com.chiru.engine.model;

import java.util.Collection;

/** Completeness of a lineage link or of a whole trace. */
public enum TraceStatus {
    VERIFIED,
    PARTIAL,
    UNRESOLVED;

    /** The weaker of two statuses (UNRESOLVED &lt; PARTIAL &lt; VERIFIED). */
    public static TraceStatus weakest(TraceStatus a, TraceStatus b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    /**
     * Status of a chain of links. VERIFIED only if every link is verified; UNRESOLVED if there are no
     * links or none is verified; otherwise PARTIAL. A missing link can never yield VERIFIED.
     */
    public static TraceStatus ofChain(Collection<TraceStatus> links) {
        if (links.isEmpty()) {
            return UNRESOLVED;
        }
        boolean allVerified = links.stream().allMatch(s -> s == VERIFIED);
        if (allVerified) {
            return VERIFIED;
        }
        boolean anyVerified = links.stream().anyMatch(s -> s == VERIFIED || s == PARTIAL);
        return anyVerified ? PARTIAL : UNRESOLVED;
    }
}
