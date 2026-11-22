package com.chiru.engine.sql;

import static org.junit.jupiter.api.Assertions.*;

import com.chiru.engine.core.AnalysisConfig;
import com.chiru.engine.core.Pipeline;
import com.chiru.engine.core.StageContext;
import com.chiru.engine.discovery.DiscoveryStage;
import com.chiru.engine.model.*;
import com.chiru.engine.spoon.SpoonSourceStage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SqlStageEdgeCasesTest {

    private static AnalysisModel run(Path dir, Map<String, String> files) throws Exception {
        for (var e : files.entrySet()) {
            Path p = dir.resolve(e.getKey());
            Files.createDirectories(p.getParent());
            Files.writeString(p, e.getValue());
        }
        AnalysisModel m = new AnalysisModel();
        AnalysisConfig cfg = new AnalysisConfig(List.of(dir.toString()), "out", 21, List.of(), false);
        Pipeline.Result r = new Pipeline(List.of(new DiscoveryStage(), new SpoonSourceStage(), new SqlStage()))
                .run(new StageContext(cfg, m));
        assertTrue(r.succeeded(), r.toString());
        return m;
    }

    private static List<Entity> statements(AnalysisModel m) {
        return m.entitiesOfType(EntityType.SQL_STATEMENT);
    }

    private static Optional<Relationship> edge(AnalysisModel m, EntityId from, RelationType t, String to) {
        return m.outgoing(from, t).stream().filter(r -> r.to().value().equals(to)).findFirst();
    }

    private static List<AnalysisWarning> sqlWarnings(AnalysisModel m) {
        return m.warnings().stream().filter(w -> w.stage().equals("sql")).toList();
    }

    @Test
    void dynamicJavaSqlIsFlaggedAndEveryDerivedEdgeIsPartial(@TempDir Path dir) throws Exception {
        AnalysisModel m = run(dir, Map.of("src/main/java/p/Dao.java", """
                package p;
                import java.sql.*;
                class Dao {
                    void find(Connection c, String col, int id) throws Exception {
                        String sql = "SELECT name FROM customer WHERE " + col + " = ? AND id = ?";
                        try (PreparedStatement ps = c.prepareStatement(sql)) {
                            ps.setString(1, "x");
                            ps.setInt(2, id);
                            ps.executeQuery();
                        }
                    }
                }
                """));
        Entity s = statements(m).get(0);
        assertEquals("true", s.attributes().get("dynamic"));
        assertTrue(s.attributes().get("text").contains("__DYN_1__"));
        assertTrue(s.attributes().get("notes").contains("dynamic part 1"), s.attributes().get("notes"));

        for (Relationship r : m.outgoing(s.id(), RelationType.READS_COLUMN)) {
            assertEquals(TraceStatus.PARTIAL, r.status(), r.to().toString());
        }
        assertTrue(m.entitiesOfType(EntityType.COLUMN).stream().noneMatch(e -> e.name().startsWith("__dyn")));
        assertTrue(sqlWarnings(m).stream().anyMatch(w -> w.message().contains("dynamic SQL")));
    }

    @Test
    void jpqlIsNeverAnalysedAsSql(@TempDir Path dir) throws Exception {
        AnalysisModel m = run(dir, Map.of("src/main/java/p/Repo.java", """
                package p;
                class Repo {
                    Object find(javax.persistence.EntityManager em) {
                        return em.createQuery("select u from User u where u.id = :id");
                    }
                }
                """));
        assertTrue(statements(m).isEmpty());
        assertTrue(m.entitiesOfType(EntityType.TABLE).isEmpty(), "User is an entity name, not a table");
        assertTrue(sqlWarnings(m).stream().anyMatch(w -> w.message().contains("JPQL/HQL")), sqlWarnings(m).toString());
    }

    @Test
    void unparsableSqlWithAKnownConsumerIsReportedNotDropped(@TempDir Path dir) throws Exception {
        AnalysisModel m = run(dir, Map.of("src/main/java/p/Bad.java", """
                package p;
                import java.sql.*;
                class Bad {
                    void run(Connection c) throws Exception {
                        c.prepareStatement("SELECT FROM WHERE oops").close();
                    }
                }
                """));
        Entity s = statements(m).get(0);
        assertEquals("false", s.attributes().get("parsed"));
        assertTrue(sqlWarnings(m).stream().anyMatch(w -> w.severity() == AnalysisWarning.Severity.WARNING
                && w.message().contains("could not be parsed")));
        EntityId method = EntityId.of(EntityType.METHOD, "p.Bad#run(java.sql.Connection)");
        assertEquals(TraceStatus.PARTIAL, edge(m, method, RelationType.EXECUTES, s.id().value()).orElseThrow().status());
    }

    @Test
    void sqlLikeTextWithoutAConsumerAndWithoutValidSyntaxIsIgnoredQuietly(@TempDir Path dir) throws Exception {
        AnalysisModel m = run(dir, Map.of("src/main/java/p/Log.java", """
                package p;
                class Log {
                    String msg() { return "delete from the queue when the job is done"; }
                }
                """));
        assertTrue(statements(m).isEmpty());
        assertTrue(sqlWarnings(m).stream().noneMatch(w -> w.severity() != AnalysisWarning.Severity.INFO));
    }

    @Test
    void ormAnnotationsProvideSchemaEvidenceWithInferredNamesMarkedPartial(@TempDir Path dir) throws Exception {
        AnalysisModel m = run(dir, Map.of(
                "src/main/java/p/Customer.java", """
                        package p;
                        import javax.persistence.*;
                        @Entity
                        @Table(name = "customers")
                        class Customer {
                            @Id Long id;
                            @Column(name = "full_name") String name;
                            String city;
                            @Transient String cache;
                        }
                        """,
                "src/main/java/p/Dao.java", """
                        package p;
                        class Dao {
                            static final String Q = "SELECT full_name, city FROM customers WHERE id = ?";
                            void run(java.sql.Connection c) throws Exception { c.prepareStatement(Q); }
                        }
                        """));
        EntityId customers = EntityId.of(EntityType.TABLE, "customers");
        assertEquals("orm", m.entity(customers).orElseThrow().attributes().get("source"));
        assertEquals(TraceStatus.VERIFIED,
                edge(m, customers, RelationType.CONTAINS, "column:customers.full_name").orElseThrow().status());
        assertEquals(TraceStatus.PARTIAL,
                edge(m, customers, RelationType.CONTAINS, "column:customers.city").orElseThrow().status(),
                "column name taken from the field name is an inference");
        assertTrue(m.entity(EntityId.of(EntityType.COLUMN, "customers.cache")).isEmpty(), "@Transient is not persisted");

        // the schema lets the unqualified SELECT columns resolve and nothing is flagged as undeclared
        Entity s = statements(m).get(0);
        assertTrue(edge(m, s.id(), RelationType.READS_COLUMN, "column:customers.full_name").isPresent());
        assertTrue(m.entitiesOfType(EntityType.COLUMN).stream().noneMatch(e -> e.attributes().containsKey("notInSchema")));
        // the constant that holds the SQL text is recorded as where the statement is defined
        assertEquals("field:p.Dao#Q", s.attributes().get("definedIn"));
    }

    @Test
    void columnMissingFromAKnownSchemaIsFlagged(@TempDir Path dir) throws Exception {
        AnalysisModel m = run(dir, Map.of(
                "src/main/resources/schema.sql", "CREATE TABLE t (id BIGINT, name VARCHAR(10));\n",
                "src/main/resources/q.sql", "SELECT id, nmae FROM t;\n"));
        Entity typo = m.entity(EntityId.of(EntityType.COLUMN, "t.nmae")).orElseThrow();
        assertEquals("true", typo.attributes().get("notInSchema"));
        assertTrue(sqlWarnings(m).stream().anyMatch(w -> w.message().contains("not declared in the known schema")));
        Relationship contains = edge(m, EntityId.of(EntityType.TABLE, "t"), RelationType.CONTAINS, "column:t.nmae").orElseThrow();
        assertEquals(TraceStatus.PARTIAL, contains.status());
        assertEquals(TraceStatus.VERIFIED,
                edge(m, EntityId.of(EntityType.TABLE, "t"), RelationType.CONTAINS, "column:t.id").orElseThrow().status());
    }

    @Test
    void ambiguousColumnBecomesAnUnresolvedEdgeNotAGuess(@TempDir Path dir) throws Exception {
        AnalysisModel m = run(dir, Map.of("src/main/resources/q.sql",
                "SELECT name FROM customer c JOIN orders o ON o.customer_id = c.id;\n"));
        Entity s = statements(m).get(0);
        Relationship r = edge(m, s.id(), RelationType.READS_COLUMN, "column:(unresolved).name").orElseThrow();
        assertEquals(TraceStatus.UNRESOLVED, r.status());
        assertTrue(r.evidence().get(0).note().contains("ambiguous"));
        assertTrue(sqlWarnings(m).stream().anyMatch(w -> w.message().contains("could not be attributed to a table")));
        assertTrue(m.entity(EntityId.of(EntityType.COLUMN, "customer.name")).isEmpty());
    }

    @Test
    void sqlFilesGiveLineEvidenceAndInsertSelectColumnMapping(@TempDir Path dir) throws Exception {
        AnalysisModel m = run(dir, Map.of("src/main/resources/jobs.sql", """
                -- nightly archive; the ';' in this comment must not split anything
                INSERT INTO archive (id, quantity)
                SELECT id, quantity FROM shipment WHERE status = 'ARCHIVED';

                /* second statement */
                DELETE FROM shipment WHERE status = 'ARCHIVED';
                """));
        assertEquals(2, statements(m).size());
        Entity insert = statements(m).stream().filter(e -> "INSERT".equals(e.attributes().get("type"))).findFirst().orElseThrow();
        assertEquals(2, insert.evidence().get(0).location().startLine());
        assertEquals(3, insert.evidence().get(0).location().endLine());
        Entity delete = statements(m).stream().filter(e -> "DELETE".equals(e.attributes().get("type"))).findFirst().orElseThrow();
        assertEquals(6, delete.evidence().get(0).location().startLine());
        assertTrue(edge(m, delete.id(), RelationType.DELETES_FROM, "table:shipment").isPresent());

        Relationship mapping = edge(m, EntityId.of(EntityType.COLUMN, "archive.quantity"), RelationType.DERIVED_FROM,
                "column:shipment.quantity").orElseThrow();
        assertEquals(TraceStatus.VERIFIED, mapping.status());
    }

    @Test
    void mapperXmlDynamicTagsMakeTheStatementPartialAndKeepParameterNames(@TempDir Path dir) throws Exception {
        AnalysisModel m = run(dir, Map.of("src/main/resources/CustomerMapper.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="p.CustomerMapper">
                  <select id="byId">
                    SELECT id, name FROM customer WHERE id = #{id}
                  </select>
                  <select id="search">
                    SELECT id FROM customer
                    <where>
                      <if test="name != null">AND name = #{name}</if>
                      <if test="city != null">AND city = #{city}</if>
                    </where>
                  </select>
                  <update id="rename">UPDATE customer SET name = #{name} WHERE id = #{id}</update>
                </mapper>
                """));
        assertEquals(3, statements(m).size());
        Entity byId = statements(m).stream().filter(e -> e.id().value().endsWith("p.CustomerMapper.byId")).findFirst().orElseThrow();
        assertNull(byId.attributes().get("dynamic"));
        assertEquals(TraceStatus.VERIFIED, edge(m, byId.id(), RelationType.READS_COLUMN, "column:customer.name").orElseThrow().status());
        Entity p = m.outgoing(byId.id(), RelationType.CONTAINS).stream().map(r -> m.entity(r.to()).orElseThrow())
                .filter(e -> "IN".equals(e.attributes().get("direction"))).findFirst().orElseThrow();
        assertEquals("id", p.attributes().get("javaParameter"));
        assertEquals("customer.id", p.attributes().get("column"));

        Entity search = statements(m).stream().filter(e -> e.id().value().endsWith("p.CustomerMapper.search")).findFirst().orElseThrow();
        assertEquals("true", search.attributes().get("dynamic"));
        assertEquals("parsed", "true".equals(search.attributes().get("parsed")) ? "parsed" : "not parsed");
        for (Relationship r : m.outgoing(search.id(), RelationType.READS_COLUMN)) {
            assertEquals(TraceStatus.PARTIAL, r.status(), r.to().toString());
        }
        assertTrue(search.evidence().get(0).location().startLine() < search.evidence().get(0).location().endLine());
    }

    @Test
    void unknownStatementKindsAreReportedAsUnsupported(@TempDir Path dir) throws Exception {
        AnalysisModel m = run(dir, Map.of("src/main/resources/m.sql",
                "MERGE INTO t USING s ON (t.id = s.id) WHEN MATCHED THEN UPDATE SET t.a = s.a;\n"));
        assertEquals(1, statements(m).size());
        assertTrue(sqlWarnings(m).stream().anyMatch(w -> w.message().contains("not analysed")), sqlWarnings(m).toString());
    }
}
