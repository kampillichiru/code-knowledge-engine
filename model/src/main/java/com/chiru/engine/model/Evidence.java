package com.chiru.engine.model;

import java.util.Objects;

/** Provenance of a fact: where it came from, which extractor produced it and how certain it is. */
public record Evidence(SourceLocation location, String extractor, Certainty certainty, String note) {

    public Evidence {
        Objects.requireNonNull(certainty, "certainty");
        if (extractor == null || extractor.isBlank()) {
            throw new IllegalArgumentException("extractor must not be blank");
        }
    }

    public static Evidence extracted(String extractor, SourceLocation location) {
        return new Evidence(location, extractor, Certainty.EXTRACTED, null);
    }
}
