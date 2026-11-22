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
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class SqlStageSampleTest {

    private static final Path SAMPLE = Path.of("..", "sample-app").toAbsolutePath().normalize();
    private static final String REPO_JAVA = "sample-app/src/main/java/com/sample/shipping/ShipmentRepository.java";
    private static AnalysisModel model;
    private static Entity select;
    private static Entity update;

    @BeforeAll
    static void analyse() {
        model = new AnalysisModel();
        AnalysisConfig cfg = new AnalysisConfig(List.of(SAMPLE.toString()), "out", 21, List.of(), false);
        Pipeline.Result r = new Pipeline(List.of(new DiscoveryStage(), new SpoonSourceStage(), new SqlStage()))
                .run(new StageContext(cfg, model));
        assertTrue(r.succeeded(), r.toString());
        select = statement("SELECT");
        update = statement("UPDATE");
    }

    private static Entity statement(String type) {
        return model.entitiesOfType(EntityType.SQL_STATEMENT).stream()
                .filter(e -> type.equals(e.attributes().get("type"))).findFirst().orElseThrow();
    }

    private static EntityId table(String n) { return EntityId.of(EntityType.TABLE, n); }
    private static EntityId column(String t, String c) { return EntityId.of(EntityType.COLUMN, t + "." + c); }

    private static Optional<Relationship> edge(EntityId from, RelationType type, EntityId to) {
        return model.outgoing(from, type).stream().filter(r -> r.to().equals(to)).findFirst();
    }

    private static Entity param(Entity sql, String direction, String keyAttr, String value) {
        return model.outgoing(sql.id(), RelationType.CONTAINS).stream()
                .map(r -> model.entity(r.to()).orElseThrow())
                .filter(e -> e.type() == EntityType.PARAMETER && direction.equals(e.attributes().get("direction"))
                        && value.equals(e.attributes().get(keyAttr)))
                .findFirst().orElseThrow(() -> new AssertionError(direction + " " + keyAttr + "=" + value));
    }

    private static int lineOf(String file, String text) throws Exception {
        List<String> lines = Files.readAllLines(SAMPLE.resolve("src/main/java/com/sample/shipping/" + file));
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(text)) return i + 1;
        }
        throw new AssertionError(text + " not found in " + file);
    }

    @Test
    void findsExactlyTheTwoStatementsAndTheDdlSchema() {
        assertEquals(2, model.entitiesOfType(EntityType.SQL_STATEMENT).size());
        Entity shipment = model.entity(table("shipment")).orElseThrow();
        assertEquals("ddl", shipment.attributes().get("source"));
        Entity quantity = model.entity(column("shipment", "quantity")).orElseThrow();
        assertEquals("DECIMAL (15, 2)", quantity.attributes().get("type"));
        assertEquals("sample-app/src/main/resources/schema.sql", quantity.evidence().get(0).location().file());
    }

    @Test
    void selectReadsTableAndColumnsWithEvidenceOnTheRealLine() throws Exception {
        assertEquals("true", select.attributes().get("parsed"));
        assertTrue(edge(select.id(), RelationType.READS_TABLE, table("shipment")).isPresent());
        for (String c : List.of("id", "status", "quantity", "rate_pct", "due_date")) {
            Relationship r = edge(select.id(), RelationType.READS_COLUMN, column("shipment", c)).orElseThrow(
                    () -> new AssertionError(c));
            assertEquals(TraceStatus.VERIFIED, r.status(), c);
        }
        SourceLocation loc = select.evidence().get(0).location();
        assertEquals(REPO_JAVA, loc.file());
        assertEquals(lineOf("ShipmentRepository.java", "\"SELECT a.id"), loc.startLine());
        assertEquals("shipment.id", model.entity(column("shipment", "id")).map(e -> "shipment." + e.name()).orElseThrow());
    }

    @Test
    void updateWritesColumnsAndReadsItsPredicateColumn() {
        assertTrue(edge(update.id(), RelationType.UPDATES_TABLE, table("shipment")).isPresent());
        assertTrue(edge(update.id(), RelationType.UPDATES_COLUMN, column("shipment", "quantity")).isPresent());
        assertTrue(edge(update.id(), RelationType.UPDATES_COLUMN, column("shipment", "status")).isPresent());
        assertTrue(edge(update.id(), RelationType.READS_COLUMN, column("shipment", "id")).isPresent());
        assertTrue(edge(update.id(), RelationType.UPDATES_COLUMN, column("shipment", "id")).isEmpty(),
                "WHERE id is a read, not a write");
    }

    @Test
    void executingMethodsAreLinked() {
        EntityId findDue = EntityId.of(EntityType.METHOD, "com.sample.shipping.ShipmentRepository#findDue(java.time.LocalDate)");
        EntityId upd = EntityId.of(EntityType.METHOD,
                "com.sample.shipping.ShipmentRepository#update(com.sample.shipping.ManifestRecord)");
        assertTrue(edge(findDue, RelationType.EXECUTES, select.id()).isPresent());
        assertTrue(edge(upd, RelationType.EXECUTES, update.id()).isPresent());
        assertTrue(edge(findDue, RelationType.EXECUTES, update.id()).isEmpty());
    }

    @Test
    void selectParameterIsBoundToItsJavaArgumentAndItsColumn() throws Exception {
        Entity p = param(select, "IN", "index", "1");
        assertEquals("shipment.due_date", p.attributes().get("column"));
        assertEquals("<=", p.attributes().get("operator"));
        assertEquals("java.sql.Date.valueOf(asOf)", p.attributes().get("javaExpression"));
        assertEquals("asOf", p.attributes().get("javaVariables"));
        assertEquals("setDate", p.attributes().get("javaSetter"));
        int line = lineOf("ShipmentRepository.java", "ps.setDate(1,");
        assertTrue(p.evidence().stream().anyMatch(e -> e.location() != null && e.location().startLine() == line));

        Relationship binds = edge(p.id(), RelationType.BINDS_PARAMETER, column("shipment", "due_date")).orElseThrow();
        assertEquals(TraceStatus.VERIFIED, binds.status());
    }

    @Test
    void updateParametersMapJavaGettersToColumns() {
        assertEquals("record.getQuantity()", param(update, "IN", "index", "1").attributes().get("javaExpression"));
        assertEquals("record.getStatus()", param(update, "IN", "index", "2").attributes().get("javaExpression"));
        assertEquals("record.getShipmentId()", param(update, "IN", "index", "3").attributes().get("javaExpression"));
        assertEquals("shipment.quantity", param(update, "IN", "index", "1").attributes().get("column"));
        assertEquals("shipment.id", param(update, "IN", "index", "3").attributes().get("column"));
        assertEquals("record", param(update, "IN", "index", "1").attributes().get("javaVariables"));
    }

    @Test
    void resultColumnsAreTiedToTheirSourceColumnAndWhereTheValueGoes() {
        Entity out = param(select, "OUT", "label", "due_date");
        assertEquals("getDate", out.attributes().get("javaGetter"));
        assertEquals("rs.getDate(\"due_date\").toLocalDate()", out.attributes().get("javaExpression"));
        assertEquals("method:com.sample.shipping.Shipment#<init>(long,java.lang.String,java.math.BigDecimal,"
                + "java.math.BigDecimal,java.time.LocalDate)#arg5", out.attributes().get("flowsTo"));
        assertEquals(TraceStatus.VERIFIED,
                edge(out.id(), RelationType.DERIVED_FROM, column("shipment", "due_date")).orElseThrow().status());

        Entity id = param(select, "OUT", "label", "id");
        assertTrue(id.attributes().get("flowsTo").endsWith("#arg1"), id.attributes().get("flowsTo"));
    }

    @Test
    void cleanSampleProducesNoSqlWarningsAboveInfo() {
        List<AnalysisWarning> mine = model.warnings().stream().filter(w -> w.stage().equals("sql")).toList();
        assertTrue(mine.stream().noneMatch(w -> w.severity() != AnalysisWarning.Severity.INFO), mine.toString());
        assertTrue(model.findProvenanceGaps().isEmpty(), model.findProvenanceGaps().toString());
        assertTrue(model.entitiesOfType(EntityType.COLUMN).stream().noneMatch(e -> e.attributes().containsKey("notInSchema")));
    }
}
