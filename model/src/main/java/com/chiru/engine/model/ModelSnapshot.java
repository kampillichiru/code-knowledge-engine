package com.chiru.engine.model;

import java.util.List;

/** Serializable, versioned form of an {@link AnalysisModel}. */
public record ModelSnapshot(
        int schemaVersion,
        List<Entity> entities,
        List<Relationship> relationships,
        List<AnalysisWarning> warnings) {
}
