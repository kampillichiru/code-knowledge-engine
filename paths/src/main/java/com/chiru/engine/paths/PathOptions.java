package com.chiru.engine.paths;

/**
 * @param maxPathsPerMethod most paths flattened per method. The number of paths grows like 2^n for n sequential decisions,
 *                          so flattening stops at this cap and the method is marked {@code pathsCapped}. The decisions and
 *                          their outcomes are never capped, only the list of paths.
 */
public record PathOptions(int maxPathsPerMethod) {

    public PathOptions {
        if (maxPathsPerMethod < 1) {
            throw new IllegalArgumentException("maxPathsPerMethod must be at least 1");
        }
    }

    public static PathOptions defaults() {
        return new PathOptions(128);
    }
}
