package com.chiru.engine.core;

import static org.junit.jupiter.api.Assertions.*;

import com.chiru.engine.model.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoreTest {

    private static AnalysisConfig config(boolean continueOnError, Path out) {
        return new AnalysisConfig(List.of("src"), out.toString(), 17, List.of(), continueOnError);
    }

    private static AnalysisStage stage(String name, List<String> log, boolean fail) {
        return new AnalysisStage() {
            public String name() { return name; }
            public void run(StageContext ctx) {
                log.add(name);
                if (fail) throw new IllegalStateException("boom");
            }
        };
    }

    @Test
    void configLoadsFromJsonAndValidates(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("config.json");
        Files.writeString(file, """
                {"sourceRoots":["a","b"],"outputDir":"out","javaRelease":11,"excludeGlobs":["**/test/**"]}
                """);
        AnalysisConfig c = AnalysisConfig.load(file);
        assertEquals(List.of("a", "b"), c.sourceRoots());
        assertEquals(11, c.javaRelease());
        assertFalse(c.continueOnError());

        assertThrows(IllegalArgumentException.class,
                () -> new AnalysisConfig(List.of(), "out", 11, List.of(), false));
        assertThrows(IllegalArgumentException.class,
                () -> new AnalysisConfig(List.of("a"), "out", 7, List.of(), false));
    }

    @Test
    void storeRoundTripsModelThroughJson(@TempDir Path dir) throws Exception {
        AnalysisModel m = new AnalysisModel();
        Evidence ev = Evidence.extracted("test", new SourceLocation("A.java", 1, 3));
        EntityId a = EntityId.of(EntityType.METHOD, "A#a()");
        EntityId b = EntityId.of(EntityType.METHOD, "B#b()");
        m.putEntity(new Entity(a, "a", Map.of("k", "v"), List.of(ev)));
        m.putEntity(new Entity(b, "b", Map.of(), List.of(ev)));
        m.addRelationship(new Relationship(a, RelationType.CALLS, b, TraceStatus.PARTIAL, List.of(ev)));
        m.addWarning(new AnalysisWarning("s", AnalysisWarning.Severity.WARNING, "gap", null));

        ModelStore.save(m, dir);
        AnalysisModel loaded = ModelStore.load(dir);

        assertEquals(m.entities(), loaded.entities());
        assertEquals(m.relationships(), loaded.relationships());
        assertEquals(m.warnings(), loaded.warnings());
        assertTrue(Files.readString(dir.resolve(ModelStore.FILE_NAME)).contains("\"schemaVersion\" : 1"));
    }

    @Test
    void pipelineStopsAtFirstFailureByDefault(@TempDir Path dir) {
        List<String> log = new ArrayList<>();
        AnalysisModel model = new AnalysisModel();
        Pipeline.Result r = new Pipeline(List.of(
                stage("one", log, false), stage("two", log, true), stage("three", log, false)))
                .run(new StageContext(config(false, dir), model));

        assertEquals(List.of("one", "two"), log);
        assertFalse(r.succeeded());
        assertEquals(2, r.stages().size());
        assertEquals(1, model.warnings().size());
        assertEquals(AnalysisWarning.Severity.ERROR, model.warnings().get(0).severity());
    }

    @Test
    void pipelineContinuesWhenConfigured(@TempDir Path dir) {
        List<String> log = new ArrayList<>();
        Pipeline.Result r = new Pipeline(List.of(
                stage("one", log, true), stage("two", log, false)))
                .run(new StageContext(config(true, dir), new AnalysisModel()));

        assertEquals(List.of("one", "two"), log);
        assertFalse(r.succeeded());
    }
}
