package com.chiru.engine.docs;

import static org.junit.jupiter.api.Assertions.*;

import com.chiru.engine.model.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocsGeneratorTest {

    private static final Evidence EV = Evidence.extracted("t", new SourceLocation("x/A.java", 1, 2));

    @Test
    void emptyModelStillGivesEveryDocument() {
        Map<String, String> docs = DocsGenerator.render(new AnalysisModel());
        assertEquals(22, docs.size());
        assertTrue(docs.keySet().containsAll(DocsGenerator.DOCUMENTS.keySet()));
        docs.forEach((file, text) -> assertTrue(text.startsWith("# "), file));
        assertTrue(docs.get("09_Output_File_Specification.md").contains("No fixed-width output file was identified"));
        assertTrue(docs.get("04_Business_Rules.md").contains("No business rule was extracted"));
    }

    @Test
    void incompleteEntitiesDoNotBreakGeneration() {
        AnalysisModel m = new AnalysisModel();
        m.putEntity(new Entity(EntityId.of(EntityType.OUTPUT_FIELD, "f"), "f", Map.of(), List.of(EV)));
        m.putEntity(new Entity(EntityId.of(EntityType.OUTPUT_FILE, "w"), "w", Map.of(), List.of(EV)));
        m.putEntity(new Entity(EntityId.of(EntityType.BUSINESS_RULE, "r"), "r", Map.of(), List.of(EV)));
        m.putEntity(new Entity(EntityId.of(EntityType.SCENARIO, "s"), "s", Map.of(), List.of(EV)));
        m.putEntity(new Entity(EntityId.of(EntityType.SQL_STATEMENT, "q"), "q", Map.of(), List.of(EV)));
        m.putEntity(new Entity(EntityId.of(EntityType.METHOD, "A#m()"), "m", Map.of("entryPoint", "main"), List.of(EV)));
        assertDoesNotThrow(() -> DocsGenerator.render(m), "a hand-edited or older model must still render");
    }


    private static AnalysisModel twoModules(boolean second) {
        AnalysisModel m = new AnalysisModel();
        Evidence mod = Evidence.extracted("t", new SourceLocation("r", 1, 1));
        EntityId a = EntityId.of(EntityType.MODULE, "r/a");
        EntityId b = EntityId.of(EntityType.MODULE, "r/b");
        m.putEntity(new Entity(a, "a", Map.of("artifactId", "mod-a", "groupId", "g", "dependencies", "g:mod-b:1, g:ghost:1, org.x:lib:2"), List.of(mod)));
        String[] files = {"r/a/A.java", "r/b/B.java"};
        EntityId[] owners = {a, b};
        for (int i = 0; i < (second ? 2 : 1); i++) {
            if (i == 1) {
                m.putEntity(new Entity(b, "b", Map.of("artifactId", "mod-b", "groupId", "g"), List.of(mod)));
            }
            EntityId f = EntityId.of(EntityType.FILE, files[i]);
            m.putEntity(new Entity(f, files[i], Map.of("kind", "JAVA", "scope", "main"), List.of(mod)));
            m.addRelationship(new Relationship(owners[i], RelationType.CONTAINS, f, TraceStatus.VERIFIED, List.of(mod)));
            Evidence at = Evidence.extracted("t", new SourceLocation(files[i], 3, 9));
            m.putEntity(new Entity(EntityId.of(EntityType.CLASS, "p.C" + i), "C" + i, Map.of("kind", "class"), List.of(at)));
        }
        if (second) {
            m.addRelationship(new Relationship(a, RelationType.DEPENDS_ON, b, TraceStatus.VERIFIED, List.of(mod)));
        }
        return m;
    }

    @Test
    void theModuleColumnAndTheMissingModuleAppearOnlyWhenTheyMeanSomething() {
        Map<String, String> two = DocsGenerator.render(twoModules(true));
        assertTrue(two.get("02_Functionality_Inventory.md").contains("| Class | Module | Kind |"));
        assertTrue(two.get("02_Functionality_Inventory.md").contains("| p.C0 | mod-a |") && two.get("02_Functionality_Inventory.md").contains("| p.C1 | mod-b |"));
        String overview = two.get("01_Module_Overview.md");
        assertTrue(overview.contains("| mod-a | NONE") || overview.contains("| mod-a |"), overview);
        assertTrue(overview.contains("mod-b") && overview.contains("**g:ghost:1**"), "ghost is a project module (same groupId) that was not analysed");
        assertTrue(overview.contains("org.x:lib:2") && !overview.contains("**org.x:lib:2**"), "a third-party dependency is not flagged");
        assertTrue(two.get("10_Class_Method_Dependencies.md").contains("| mod-a | mod-b | yes | 0 | declared, but no call between them was found |"));

        Map<String, String> one = DocsGenerator.render(twoModules(false));
        assertTrue(one.get("02_Functionality_Inventory.md").contains("| Class | Kind |"), "one analysed module: no Module column");
        assertTrue(one.get("10_Class_Method_Dependencies.md").contains("single module"));
    }

    @Test
    void textCannotBreakTables() {
        assertEquals("a\\|b c", Md.cell("a|b\nc"));
        assertEquals(Md.NONE, Md.cell(null));
        assertEquals(Md.NONE, Md.cell("  "));
        assertTrue(Md.cell("x".repeat(1000)).length() < 500);
        assertEquals("&lt;init&gt;", Md.cell("<init>"));
        String table = Md.table(List.of("A", "B"), List.of(List.of("1", "x|y")));
        assertEquals(3, table.lines().filter(l -> l.startsWith("|")).count());
        assertTrue(table.contains("x\\|y"));
        assertEquals("_None found._\n\n", Md.table(List.of("A"), List.of()));
        assertTrue(Md.block("sql", "select ``` x").startsWith("````sql"), "the fence must be longer than any backtick run inside");
    }

    @Test
    void generationIsDeterministic() {
        AnalysisModel m = new AnalysisModel();
        m.putEntity(new Entity(EntityId.of(EntityType.CLASS, "a.B"), "B", Map.of("kind", "class"), List.of(EV)));
        assertEquals(DocsGenerator.render(m), DocsGenerator.render(m));
    }

    @Test
    void writesFilesWithUnixLineEndings(@TempDir Path dir) throws IOException {
        List<Path> files = DocsGenerator.generate(new AnalysisModel(), dir.resolve("nested/out"));
        assertEquals(24, files.size());
        for (Path f : files) {
            assertFalse(Files.readString(f).contains("\r"), f.toString());
        }
    }
}
