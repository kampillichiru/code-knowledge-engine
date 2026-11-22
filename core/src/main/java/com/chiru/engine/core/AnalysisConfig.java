package com.chiru.engine.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Analysis settings. Paths are plain strings so the config round-trips as JSON.
 *
 * @param sourceRoots     directories (or files) to analyse
 * @param outputDir       where the model JSON and generated documents are written
 * @param javaRelease     Java language level of the analysed code (8 or later)
 * @param excludeGlobs    glob patterns of files to skip
 * @param continueOnError keep running later stages after a stage fails (default: stop)
 * @param bytecodeRoots   directories or jars of compiled classes for the bytecode call graph (optional)
 */
public record AnalysisConfig(
        List<String> sourceRoots,
        String outputDir,
        int javaRelease,
        List<String> excludeGlobs,
        boolean continueOnError,
        List<String> bytecodeRoots) {

    public AnalysisConfig {
        if (sourceRoots == null || sourceRoots.isEmpty()) {
            throw new IllegalArgumentException("sourceRoots must not be empty");
        }
        if (outputDir == null || outputDir.isBlank()) {
            throw new IllegalArgumentException("outputDir must not be blank");
        }
        if (javaRelease < 8) {
            throw new IllegalArgumentException("javaRelease must be 8 or later");
        }
        sourceRoots = List.copyOf(sourceRoots);
        excludeGlobs = excludeGlobs == null ? List.of() : List.copyOf(excludeGlobs);
        bytecodeRoots = bytecodeRoots == null ? List.of() : List.copyOf(bytecodeRoots);
    }

    /** Source-only configuration (no bytecode roots). */
    public AnalysisConfig(List<String> sourceRoots, String outputDir, int javaRelease,
                          List<String> excludeGlobs, boolean continueOnError) {
        this(sourceRoots, outputDir, javaRelease, excludeGlobs, continueOnError, List.of());
    }

    public static AnalysisConfig load(Path file) throws IOException {
        return Json.mapper().readValue(file.toFile(), AnalysisConfig.class);
    }

    public AnalysisConfig withBytecodeRoots(List<String> roots) {
        return new AnalysisConfig(sourceRoots, outputDir, javaRelease, excludeGlobs, continueOnError, roots);
    }

    public List<Path> sourceRootPaths() {
        return sourceRoots.stream().map(Path::of).toList();
    }

    public List<Path> bytecodeRootPaths() {
        return bytecodeRoots.stream().map(Path::of).toList();
    }

    public Path outputPath() {
        return Path.of(outputDir);
    }
}
