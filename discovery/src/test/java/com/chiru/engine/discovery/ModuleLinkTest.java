package com.chiru.engine.discovery;

import static org.junit.jupiter.api.Assertions.*;

import com.chiru.engine.core.AnalysisConfig;
import com.chiru.engine.core.StageContext;
import com.chiru.engine.model.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Several modules analysed together: inherited coordinates, module links, a warning for a module that is missing. */
class ModuleLinkTest {

    private static final Path MULTI = Path.of("..", "conformance", "multimodule").toAbsolutePath().normalize();

    private static AnalysisModel discover(Path... roots) throws IOException {
        AnalysisModel model = new AnalysisModel();
        AnalysisConfig cfg = new AnalysisConfig(java.util.Arrays.stream(roots).map(Path::toString).toList(), "out", 21, List.of(), false);
        new DiscoveryStage().run(new StageContext(cfg, model));
        return model;
    }

    private static Entity module(AnalysisModel m, String artifactId) {
        return m.entitiesOfType(EntityType.MODULE).stream().filter(e -> artifactId.equals(e.attributes().get("artifactId")))
                .findFirst().orElseThrow();
    }

    private static List<AnalysisWarning> missingModuleWarnings(AnalysisModel m) {
        return m.warnings().stream().filter(w -> w.stage().equals("discovery") && w.message().contains("was not analysed")).toList();
    }

    // ---------------------------------------------------------------- coordinates

    @Test
    void groupIdAndVersionAreInheritedFromTheParent() throws IOException {
        AnalysisModel m = discover(MULTI);
        Entity common = module(m, "common");
        assertEquals("com.acme", common.attributes().get("groupId"));
        assertEquals("1.0", common.attributes().get("version"));
        assertEquals("true", common.attributes().get("coordinatesInherited"));
        assertEquals("com.acme:acme-parent:1.0", common.attributes().get("parent"));
        assertEquals("pom", module(m, "acme-parent").attributes().get("packaging"));
        assertNull(module(m, "acme-parent").attributes().get("coordinatesInherited"));
    }

    @Test
    void projectPlaceholdersInDependenciesAreResolved(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion><groupId>g</groupId><artifactId>app</artifactId><version>2.5</version>
                <dependencies><dependency><groupId>${project.groupId}</groupId><artifactId>lib</artifactId>
                <version>${project.version}</version></dependency></dependencies></project>""");
        assertEquals("g:lib:2.5", module(discover(dir), "app").attributes().get("dependencies"));
    }

    // ---------------------------------------------------------------- links

    @Test
    void aModuleThatDependsOnAnotherAnalysedModuleIsLinkedToIt() throws IOException {
        AnalysisModel m = discover(MULTI);
        Entity app = module(m, "module-1");
        Entity common = module(m, "common");
        List<Relationship> links = m.outgoing(app.id(), RelationType.DEPENDS_ON);
        assertEquals(1, links.size());
        assertEquals(common.id(), links.get(0).to());
        assertEquals(TraceStatus.VERIFIED, links.get(0).status());
        assertTrue(links.get(0).evidence().get(0).note().contains("declares dependency com.acme:common:1.0"));
        assertTrue(m.outgoing(common.id(), RelationType.DEPENDS_ON).isEmpty(), "the link has a direction");
        assertTrue(missingModuleWarnings(m).isEmpty(), "everything the module needs was analysed");
    }

    @Test
    void passingTheModulesAsSeparateRootsGivesTheSameLinks() throws IOException {
        AnalysisModel m = discover(MULTI.resolve("common"), MULTI.resolve("module-1"));
        assertEquals(1, m.outgoing(module(m, "module-1").id(), RelationType.DEPENDS_ON).size());
        assertTrue(missingModuleWarnings(m).isEmpty());
    }

    // ---------------------------------------------------------------- the missing module

    @Test
    void aProjectModuleThatWasNotAnalysedIsReportedWithWhatToDo() throws IOException {
        AnalysisModel m = discover(MULTI.resolve("module-1"));
        List<AnalysisWarning> warnings = missingModuleWarnings(m);
        assertEquals(1, warnings.size());
        AnalysisWarning w = warnings.get(0);
        assertEquals(AnalysisWarning.Severity.WARNING, w.severity());
        assertTrue(w.message().contains("module 'module-1'") && w.message().contains("com.acme:common:1.0"), w.message());
        assertTrue(w.message().contains("PARTIAL") && w.message().contains("--source"), "it says why it matters and what to do");
        assertNotNull(w.location(), "it points at the pom that declares the dependency");
        assertTrue(m.outgoing(module(m, "module-1").id(), RelationType.DEPENDS_ON).isEmpty());
    }

    @Test
    void thirdPartyAndTestScopedDependenciesAreNotReported(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion><groupId>com.acme</groupId><artifactId>app</artifactId><version>1</version>
                <dependencies>
                  <dependency><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId><version>2.0.0</version></dependency>
                  <dependency><groupId>com.acme</groupId><artifactId>test-support</artifactId><version>1</version><scope>test</scope></dependency>
                </dependencies></project>""");
        AnalysisModel m = discover(dir);
        assertTrue(missingModuleWarnings(m).isEmpty(), "slf4j is not ours and test-support is only used by tests");
        Entity app = module(m, "app");
        assertEquals("org.slf4j:slf4j-api:2.0.0", app.attributes().get("dependencies"));
        assertEquals("com.acme:test-support:1", app.attributes().get("testDependencies"));
    }

    // ---------------------------------------------------------------- root names

    @Test
    void rootsWhoseFoldersHaveTheSameNameKeepTheirModuleInTheirPaths() throws IOException {
        AnalysisModel m = discover(MULTI.resolve("common/src"), MULTI.resolve("module-1/src"));
        List<String> repos = m.entitiesOfType(EntityType.REPOSITORY).stream().map(Entity::name).sorted().toList();
        assertEquals(List.of("common/src", "module-1/src"), repos);
        List<String> files = m.entitiesOfType(EntityType.FILE).stream().map(Entity::name).sorted().toList();
        assertEquals(List.of("common/src/main/java/com/acme/common/Rates.java", "module-1/src/main/java/com/acme/app/SurchargeJob.java"), files);
        assertTrue(m.warnings().stream().noneMatch(w -> w.message().contains("Conflicting attribute")),
                "two roots must not be merged into one repository");
    }

    @Test
    void repositoryNamesAreExtendedOnlyAsFarAsNeeded(@TempDir Path dir) throws IOException {
        Path a = Files.createDirectories(dir.resolve("x/a/src"));
        Path b = Files.createDirectories(dir.resolve("y/a/src"));
        Path c = Files.createDirectories(dir.resolve("other"));
        Map<Path, String> names = DiscoveryStage.repositoryNames(List.of(a, b, c));
        assertEquals("x/a/src", names.get(a), "src and a/src both collide, so three segments are needed");
        assertEquals("y/a/src", names.get(b));
        assertEquals("other", names.get(c), "a name that is already unique is left alone");

        Map<Path, String> twice = DiscoveryStage.repositoryNames(List.of(a, a));
        assertEquals("src", twice.get(a), "the same root given twice is not a collision");
    }
}
