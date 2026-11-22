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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Legacy, non-Spring code: SQL lives in .properties, .yml and XML, and Java looks it up by key. */
class ConfiguredSqlTest {

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

    private static Entity statement(AnalysisModel m, String origin) {
        return m.entitiesOfType(EntityType.SQL_STATEMENT).stream().filter(e -> origin.equals(e.attributes().get("origin")))
                .findFirst().orElseThrow(() -> new AssertionError("no " + origin + " statement in " + m.entitiesOfType(EntityType.SQL_STATEMENT)));
    }

    @Test
    void sqlInAPropertiesFileIsReadAndLinkedToTheMethodThatLooksItUp(@TempDir Path dir) throws Exception {
        AnalysisModel m = run(dir, Map.of(
                "src/main/resources/queries.properties", "# legacy queries\nacct.select = SELECT id, quantity FROM shipment \\\n   WHERE status = ?\nunrelated = hello\n",
                "src/main/java/p/Dao.java", """
                        package p;
                        import java.sql.*;
                        import java.util.Properties;
                        class Dao {
                            Properties queries = new Properties();
                            long find(Connection c) throws Exception {
                                PreparedStatement ps = c.prepareStatement(queries.getProperty("acct.select"));
                                ps.setString(1, "A");
                                ResultSet rs = ps.executeQuery();
                                return rs.getLong("quantity");
                            }
                        }
                        """));
        Entity s = statement(m, "CONFIG");
        assertEquals("true", s.attributes().get("parsed"));
        assertTrue(s.attributes().get("text").contains("WHERE status = ?"), s.attributes().get("text"));
        assertTrue(m.outgoing(s.id(), RelationType.READS_COLUMN).stream().anyMatch(r -> r.to().value().equals("column:shipment.quantity")),
                m.outgoing(s.id(), RelationType.READS_COLUMN).toString());
        assertTrue(m.incoming(s.id(), RelationType.EXECUTES).stream().anyMatch(r -> r.from().value().contains("p.Dao#find")),
                "the method that looks the key up is the executor");
        assertTrue(s.attributes().get("notes").contains("acct.select"));
        assertEquals(1, m.entitiesOfType(EntityType.SQL_STATEMENT).size(), "the plain text property is not SQL");
    }

    @Test
    void sqlInASpringStyleBeanFileIsReadWithoutSpring(@TempDir Path dir) throws Exception {
        AnalysisModel m = run(dir, Map.of(
                "src/main/resources/dao-context.xml", """
                        <beans>
                          <bean id="shipmentDao" class="p.Dao">
                            <property name="selectSql" value="SELECT id FROM shipment WHERE due_date &lt;= ?"/>
                          </bean>
                          <queries>
                            <query id="closeShipment"><![CDATA[UPDATE shipment SET status = 'ARCHIVED' WHERE id = ?]]></query>
                          </queries>
                        </beans>
                        """,
                "src/main/java/p/Dao.java", """
                        package p;
                        import java.sql.*;
                        class Dao {
                            void close(Connection c, long id) throws Exception {
                                PreparedStatement ps = c.prepareStatement(Catalog.get("closeShipment"));
                                ps.setLong(1, id);
                                ps.executeUpdate();
                            }
                        }
                        class Catalog { static String get(String k) { return k; } }
                        """));
        List<Entity> config = m.entitiesOfType(EntityType.SQL_STATEMENT).stream().filter(e -> "CONFIG".equals(e.attributes().get("origin"))).toList();
        assertEquals(2, config.size(), config.toString());
        Entity update = config.stream().filter(e -> "UPDATE".equals(e.attributes().get("type"))).findFirst().orElseThrow();
        assertTrue(m.outgoing(update.id(), RelationType.UPDATES_COLUMN).stream().anyMatch(r -> r.to().value().equals("column:shipment.status")));
        assertTrue(m.incoming(update.id(), RelationType.EXECUTES).stream().anyMatch(r -> r.from().value().contains("p.Dao#close")));
    }

    @Test
    void anIbatisSqlMapStatementIsLinkedByItsId(@TempDir Path dir) throws Exception {
        AnalysisModel m = run(dir, Map.of(
                "src/main/resources/Shipment.xml", """
                        <sqlMap namespace="Shipment">
                          <select id="getDue" resultClass="p.Acct">
                            SELECT id, quantity FROM shipment WHERE status = #status# AND due_date &lt;= #asOf#
                          </select>
                        </sqlMap>
                        """,
                "src/main/java/p/Dao.java", """
                        package p;
                        class Dao {
                            java.util.List load(Object sqlMap, Object p) {
                                return ((Client) sqlMap).queryForList("Shipment.getDue", p);
                            }
                            interface Client { java.util.List queryForList(String id, Object p); }
                        }
                        """));
        Entity s = statement(m, "XML");
        assertTrue(s.attributes().get("text").contains("status = ? AND due_date <= ?"), s.attributes().get("text"));
        assertTrue(m.incoming(s.id(), RelationType.EXECUTES).stream().anyMatch(r -> r.from().value().contains("p.Dao#load")),
                "queryForList(\"Shipment.getDue\") runs the statement");
    }

    @Test
    void yamlBlockScalarsAndAmbiguousKeysAreHandled(@TempDir Path dir) throws Exception {
        AnalysisModel m = run(dir, Map.of(
                "src/main/resources/sql.yml", "queries:\n  due: |\n    SELECT id FROM shipment\n    WHERE due_date <= ?\n  note: just text\n",
                "src/main/java/p/Dao.java", "package p;\nclass Dao { String k = \"queries.due\"; }\n"));
        Entity s = statement(m, "CONFIG");
        assertTrue(s.attributes().get("text").startsWith("SELECT id FROM shipment WHERE due_date <= ?"), s.attributes().get("text"));
        assertTrue(m.incoming(s.id(), RelationType.EXECUTES).isEmpty(), "a key that is never used by a call is not claimed as executed");
    }
}
