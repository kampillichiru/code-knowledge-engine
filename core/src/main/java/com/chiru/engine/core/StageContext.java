package com.chiru.engine.core;

import com.chiru.engine.model.AnalysisModel;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * What a stage receives: the settings, the shared model it reads from and writes to, and an in-memory cache for
 * expensive objects (such as a parsed Spoon model) that later stages in the same run can reuse. The cache is never
 * serialised; a stage run from a stored model simply rebuilds what it needs.
 */
public record StageContext(AnalysisConfig config, AnalysisModel model, Map<String, Object> shared) {

    public StageContext(AnalysisConfig config, AnalysisModel model) {
        this(config, model, new HashMap<>());
    }

    @SuppressWarnings("unchecked")
    public <T> T cached(String key, Supplier<T> loader) {
        return (T) shared.computeIfAbsent(key, k -> loader.get());
    }
}
