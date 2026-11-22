package com.chiru.engine.core;

import com.chiru.engine.model.AnalysisModel;
import com.chiru.engine.model.ModelSnapshot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Reads and writes the intermediate model as versioned JSON so later stages can run without re-analysis. */
public final class ModelStore {

    public static final String FILE_NAME = "analysis-model.json";

    private ModelStore() {}

    public static Path save(AnalysisModel model, Path outputDir) throws IOException {
        Files.createDirectories(outputDir);
        Path target = outputDir.resolve(FILE_NAME);
        Path tmp = outputDir.resolve(FILE_NAME + ".tmp");
        Json.mapper().writeValue(tmp.toFile(), model.snapshot());
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    public static AnalysisModel load(Path outputDir) throws IOException {
        Path file = outputDir.resolve(FILE_NAME);
        ModelSnapshot snapshot = Json.mapper().readValue(file.toFile(), ModelSnapshot.class);
        return AnalysisModel.fromSnapshot(snapshot);
    }
}
