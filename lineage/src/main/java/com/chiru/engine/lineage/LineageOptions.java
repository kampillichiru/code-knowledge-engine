package com.chiru.engine.lineage;

import java.util.List;

/**
 * Settings of the lineage tracer.
 *
 * @param pureLibraryPrefixes owner types whose methods only transform their inputs (no hidden source), so a call
 *                            to them is transparent and keeps a trace VERIFIED. Anything else outside the analysed
 *                            sources stays PARTIAL.
 * @param maxDepth            longest path followed from an output field
 * @param maxPathsPerField    most raw paths collected per output field; reaching it is reported, never hidden
 */
public record LineageOptions(List<String> pureLibraryPrefixes, int maxDepth, int maxPathsPerField) {

    public LineageOptions {
        pureLibraryPrefixes = List.copyOf(pureLibraryPrefixes);
    }

    public static LineageOptions defaults() {
        return new LineageOptions(com.chiru.engine.core.PureLibraries.DEFAULT_PREFIXES, 80, 2000);
    }
}
