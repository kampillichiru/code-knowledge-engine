package com.chiru.engine.model;

import java.util.Objects;

/** A file path (relative to the analysed root, forward slashes) and 1-based line range. */
public record SourceLocation(String file, int startLine, int endLine) {

    public SourceLocation {
        Objects.requireNonNull(file, "file");
        if (file.isBlank()) {
            throw new IllegalArgumentException("file must not be blank");
        }
        if (startLine < 1 || endLine < startLine) {
            throw new IllegalArgumentException("invalid line range " + startLine + "-" + endLine);
        }
        file = file.replace('\\', '/');
    }

    @Override
    public String toString() {
        return startLine == endLine ? file + ":" + startLine : file + ":" + startLine + "-" + endLine;
    }
}
