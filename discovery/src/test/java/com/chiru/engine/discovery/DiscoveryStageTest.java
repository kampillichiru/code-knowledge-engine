package com.chiru.engine.discovery;

import static org.junit.jupiter.api.Assertions.*;

import com.chiru.engine.core.AnalysisConfig;
import com.chiru.engine.core.StageContext;
import com.chiru.engine.model.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DiscoveryStageTest {

    private static final Path SAMPLE = Path.of("..", "sample-app").toAbsolutePath().normalize();

    private static AnalysisModel run(Path root, List<String> excludes) throws Exception {
        AnalysisModel model = new AnalysisModel();
        AnalysisConfig cfg = new AnalysisConfig(List.of(root.toString()), "out", 21, excludes, false);
        new DiscoveryStage().run(new StageContext(cfg, model));
        return model;
    }

    @Test
    void inventoriesSampleApp() throws Exception {
        AnalysisModel m = run(SAMPLE, List.of());

        assertTrue(m.entity(EntityId.of(EntityType.REPOSITORY, "sample-app")).isPresent());
        Entity module = m.entity(EntityId.of(EntityType.MODULE, "sample-app/.")).orElseThrow();
        assertEquals("MAVEN", module.attributes().get("buildSystem"));
        assertEquals("sample-app", module.attributes().get("artifactId"));

        assertEquals(12, DiscoveryStage.filesOfKind(m, "JAVA").size());
        assertEquals(1, DiscoveryStage.filesOfKind(m, "SQL").size());
        assertEquals(1, DiscoveryStage.filesOfKind(m, "PROPERTIES").size());
        assertEquals(1, DiscoveryStage.filesOfKind(m, "BUILD").size());

        // build output must never be inventoried
        assertTrue(m.entitiesOfType(EntityType.FILE).stream().noneMatch(e -> e.name().contains("/target/")));
        // every file is owned by a module and carries evidence
        assertTrue(m.findProvenanceGaps().isEmpty());
        Entity shipment = m.entity(EntityId.of(EntityType.FILE, "sample-app/src/main/java/com/sample/shipping/Shipment.java"))
                .orElseThrow();
        assertEquals("main", shipment.attributes().get("scope"));
        assertEquals(1, m.incoming(shipment.id(), RelationType.CONTAINS).size());
    }

    @Test
    void excludeGlobsRemoveFiles() throws Exception {
        AnalysisModel m = run(SAMPLE, List.of("**/Shipment*.java"));
        // Shipment.java and ShipmentRepository.java excluded
        assertEquals(10, DiscoveryStage.filesOfKind(m, "JAVA").size());
    }

    @Test
    void createsDefaultModuleWhenNoBuildFileAndParsesDependencies(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("loose"));
        Files.writeString(dir.resolve("loose").resolve("A.java"), "class A {}");
        Files.createDirectories(dir.resolve("svc/src/test/java"));
        Files.writeString(dir.resolve("svc/pom.xml"), """
                <project><groupId>g</groupId><artifactId>svc</artifactId>
                <dependencies><dependency><groupId>x</groupId><artifactId>y</artifactId><version>1.0</version></dependency>
                </dependencies></project>""");
        Files.writeString(dir.resolve("svc/src/test/java/T.java"), "class T {}");

        AnalysisModel m = run(dir, List.of());
        String repo = dir.getFileName().toString();

        Entity def = m.entity(EntityId.of(EntityType.MODULE, repo + "/.")).orElseThrow();
        assertEquals("NONE", def.attributes().get("buildSystem"));
        Entity svc = m.entity(EntityId.of(EntityType.MODULE, repo + "/svc")).orElseThrow();
        assertEquals("x:y:1.0", svc.attributes().get("dependencies"));

        Entity test = m.entity(EntityId.of(EntityType.FILE, repo + "/svc/src/test/java/T.java")).orElseThrow();
        assertEquals("test", test.attributes().get("scope"));
        // nested file belongs to nearest module, not the default one
        assertEquals(svc.id(), m.incoming(test.id(), RelationType.CONTAINS).get(0).from());
    }

    @Test
    void missingRootFails(@TempDir Path dir) {
        assertThrows(IllegalArgumentException.class, () -> run(dir.resolve("nope"), List.of()));
    }
}
