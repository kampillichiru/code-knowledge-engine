package com.chiru.engine.model;

import java.util.Objects;

/** Something the engine could not analyse or had to approximate. Warnings are never silently dropped. */
public record AnalysisWarning(String stage, Severity severity, String message, SourceLocation location) {

    public enum Severity { INFO, WARNING, ERROR }

    public AnalysisWarning {
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(message, "message");
    }
}
