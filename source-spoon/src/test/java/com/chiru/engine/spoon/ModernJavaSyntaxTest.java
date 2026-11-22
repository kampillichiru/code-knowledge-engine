package com.chiru.engine.spoon;

import static org.junit.jupiter.api.Assertions.*;

import com.chiru.engine.core.AnalysisConfig;
import com.chiru.engine.core.Pipeline;
import com.chiru.engine.core.StageContext;
import com.chiru.engine.discovery.DiscoveryStage;
import com.chiru.engine.model.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Records what the pinned Spoon version can parse, so unsupported syntax is a known limit, not a surprise. */
class ModernJavaSyntaxTest {

    private static final String JAVA21 = """
            package m;
            public class Modern {
                sealed interface Shape permits Circle, Square {}
                record Circle(double r) implements Shape {}
                record Square(double s) implements Shape {}

                static double area(Shape shape) {
                    return switch (shape) {
                        case Circle c -> Math.PI * c.r() * c.r();
                        case Square(double s) -> s * s;
                    };
                }

                static String describe(Object o) {
                    var text = \"\"\"
                        hello
                        \"\"\";
                    if (o instanceof String s && !s.isEmpty()) {
                        return text + s;
                    }
                    return text;
                }
            }
            """;

    private AnalysisModel analyse(Path dir, String source, int release) throws Exception {
        Files.createDirectories(dir.resolve("src/main/java/m"));
        Files.writeString(dir.resolve("src/main/java/m/Modern.java"), source);
        AnalysisModel m = new AnalysisModel();
        AnalysisConfig cfg = new AnalysisConfig(List.of(dir.toString()), "out", release, List.of(), false);
        Pipeline.Result r = new Pipeline(List.of(new DiscoveryStage(), new SpoonSourceStage()))
                .run(new StageContext(cfg, m));
        assertTrue(r.succeeded(), r.toString());
        return m;
    }

    @Test
    void parsesJava21RecordsSealedTypesSwitchPatternsAndTextBlocks(@TempDir Path dir) throws Exception {
        AnalysisModel m = analyse(dir, JAVA21, 21);

        assertEquals("record", m.entity(EntityId.of(EntityType.CLASS, "m.Modern$Circle")).orElseThrow()
                .attributes().get("kind"));
        assertTrue(m.entity(EntityId.of(EntityType.METHOD, "m.Modern#area(m.Modern$Shape)")).isPresent());
        assertTrue(m.entitiesOfType(EntityType.BRANCH).stream()
                .anyMatch(b -> b.attributes().get("kind").equals("switch-expression")));
        assertTrue(m.entitiesOfType(EntityType.BRANCH).stream()
                .anyMatch(b -> b.attributes().get("kind").equals("if")));
    }

    @Test
    void java21SourceParsesAtComplianceLevel25(@TempDir Path dir) throws Exception {
        AnalysisModel m = analyse(dir, JAVA21, 25);
        assertTrue(m.entity(EntityId.of(EntityType.METHOD, "m.Modern#describe(java.lang.Object)")).isPresent());
    }
}
