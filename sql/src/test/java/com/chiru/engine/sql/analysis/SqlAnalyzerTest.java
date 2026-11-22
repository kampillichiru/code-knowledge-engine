package com.chiru.engine.sql.analysis;

import static org.junit.jupiter.api.Assertions.*;

import com.chiru.engine.sql.analysis.SqlAnalysis.Access;
import com.chiru.engine.sql.analysis.SqlAnalysis.ColumnUse;
import com.chiru.engine.sql.analysis.SqlAnalysis.Param;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class SqlAnalyzerTest {

    private static SqlAnalysis analyze(String sql) throws Exception {
        return new SqlAnalyzer(new SchemaCatalog()).analyze(sql);
    }

    private static SqlAnalysis analyze(SchemaCatalog catalog, String sql) throws Exception {
        return new SqlAnalyzer(catalog).analyze(sql);
    }

    private static List<String> cols(SqlAnalysis a, Access access, String role) {
        return a.columns().stream().filter(c -> c.access() == access && c.role().equals(role))
                .map(c -> (c.table() == null ? "?" : c.table()) + "." + c.column()).toList();
    }

    @Test
    void aliasedSelectWithPredicateParameter() throws Exception {
        SqlAnalysis a = analyze("SELECT a.id, a.status FROM shipment a WHERE a.status IN ('X','Y') AND a.due_date <= ?");
        assertEquals("SELECT", a.type());
        assertEquals("shipment", a.tables().get(0).name());
        assertEquals("a", a.tables().get(0).alias());
        assertEquals(List.of("shipment.id", "shipment.status"), cols(a, Access.READ, "select list"));
        assertEquals(List.of("shipment.status", "shipment.due_date"), cols(a, Access.READ, "where"));

        assertEquals(1, a.params().size());
        Param p = a.params().get(0);
        assertEquals(1, p.index());
        assertEquals("shipment.due_date", p.column());
        assertEquals("<=", p.operator());
        assertTrue(p.direct());

        assertEquals(List.of("id", "status"), a.selectItems().stream().map(SqlAnalysis.SelectItemInfo::label).toList());
        assertEquals(List.of("shipment.id"), a.selectItems().get(0).sources());
    }

    @Test
    void parameterIndexFollowsTextOrderAcrossClauses() throws Exception {
        SqlAnalysis a = analyze("SELECT COALESCE(?, a.x) AS v FROM t a WHERE a.y = ? AND a.z BETWEEN ? AND ?");
        assertEquals(List.of(1, 2, 3, 4), a.params().stream().map(Param::index).toList());
        assertNull(a.params().get(0).column());
        assertEquals("t.y", a.params().get(1).column());
        assertEquals(">=", a.params().get(2).operator());
        assertEquals("<=", a.params().get(3).operator());
    }

    @Test
    void joinsKeepAliasesAndJoinConditions() throws Exception {
        SqlAnalysis a = analyze("SELECT c.name, SUM(o.total) AS spent FROM customer c "
                + "LEFT JOIN orders o ON o.customer_id = c.id WHERE o.status = ? GROUP BY c.name ORDER BY spent DESC");
        assertEquals(List.of("customer", "orders"), a.tables().stream().map(SqlAnalysis.TableUse::name).toList());
        assertEquals(List.of("orders.customer_id", "customer.id"), cols(a, Access.READ, "join condition"));
        assertEquals(List.of("customer.name"), cols(a, Access.READ, "group by"));
        assertTrue(a.joins().get(0).startsWith("LEFT JOIN orders o ON"), a.joins().toString());
        assertEquals(List.of("sum"), a.aggregates());
        assertEquals(List.of("customer.name"), a.selectItems().get(0).sources());
        assertEquals(List.of("orders.total"), a.selectItems().get(1).sources());
        assertEquals("spent", a.selectItems().get(1).label());
        // ORDER BY an output alias is not a column access
        assertTrue(cols(a, Access.READ, "order by").isEmpty());
        assertEquals("orders.status", a.params().get(0).column());
    }

    @Test
    void ambiguousUnqualifiedColumnIsNotGuessed() throws Exception {
        SqlAnalysis a = analyze("SELECT name FROM customer c JOIN orders o ON o.customer_id = c.id");
        ColumnUse name = a.columns().stream().filter(c -> c.column().equals("name")).findFirst().orElseThrow();
        assertFalse(name.resolved());
        assertTrue(name.note().contains("ambiguous"), name.note());
    }

    @Test
    void schemaResolvesUnqualifiedColumnOnlyWhenUnique() throws Exception {
        SchemaCatalog catalog = new SchemaCatalog();
        catalog.addColumn("customer", "id", "BIGINT");
        catalog.addColumn("customer", "name", "VARCHAR");
        catalog.addColumn("orders", "id", "BIGINT");
        catalog.addColumn("orders", "total", "DECIMAL");
        SqlAnalysis a = analyze(catalog, "SELECT name, total, id FROM customer c JOIN orders o ON o.id = c.id");
        List<ColumnUse> select = a.columns().stream().filter(c -> c.role().equals("select list")).toList();
        assertEquals("customer", select.get(0).table());
        assertEquals("orders", select.get(1).table());
        assertFalse(select.get(2).resolved(), "id exists in both tables");
    }

    @Test
    void columnMissingFromSchemaOfTheOnlyTableIsAttributedWithACaveat() throws Exception {
        SchemaCatalog catalog = new SchemaCatalog();
        catalog.addColumn("t", "id", "BIGINT");
        SqlAnalysis one = analyze(catalog, "SELECT id, nmae FROM t");
        ColumnUse typo = one.columns().stream().filter(c -> c.column().equals("nmae")).findFirst().orElseThrow();
        assertEquals("t", typo.table());
        assertTrue(typo.note().contains("not declared"), typo.note());

        // with several tables in scope nothing can be attributed
        catalog.addColumn("u", "id", "BIGINT");
        SqlAnalysis two = analyze(catalog, "SELECT nmae FROM t JOIN u ON t.id = u.id");
        assertFalse(two.columns().stream().filter(c -> c.column().equals("nmae")).findFirst().orElseThrow().resolved());
    }

    @Test
    void singleTableUnqualifiedColumnsResolveWithoutSchema() throws Exception {
        SqlAnalysis a = analyze("SELECT quantity FROM shipment WHERE id = ?");
        assertEquals(List.of("shipment.quantity"), cols(a, Access.READ, "select list"));
        assertEquals("shipment.id", a.params().get(0).column());
    }

    @Test
    void cteAndSubqueriesAreFollowedWithoutDoubleCounting() throws Exception {
        SqlAnalysis a = analyze("WITH big AS (SELECT id, quantity FROM shipment WHERE quantity > ?) "
                + "SELECT b.id, (SELECT MAX(t.amount) FROM txn t WHERE t.shipment_id = b.id) AS last_amt "
                + "FROM big b WHERE b.quantity > 10");
        assertEquals(List.of("big"), a.ctes());
        assertEquals(1, a.subqueries());
        assertTrue(a.tables().stream().anyMatch(t -> t.name().equals("shipment")));
        assertTrue(a.tables().stream().anyMatch(t -> t.name().equals("txn")));
        assertTrue(a.tables().stream().noneMatch(t -> t.name().equals("big")), "a CTE is not a base table");
        assertEquals("shipment.quantity", a.params().get(0).column());
        // b.id is read through the CTE, so only the CTE body records shipment.id
        assertEquals(1, a.columns().stream().filter(c -> "shipment".equals(c.table()) && c.column().equals("id")).count());
        // only the subquery's select-list column derives the value; its WHERE column merely filters rows
        assertEquals(List.of("txn.amount"), a.selectItems().get(1).sources());
        assertEquals(List.of(), a.selectItems().get(0).sources(), "b.id is read through the CTE");
    }

    @Test
    void derivedTableOutputIsTraceableToItsBaseColumns() throws Exception {
        SqlAnalysis a = analyze("SELECT d.total FROM (SELECT SUM(amount) AS total FROM txn) d");
        assertEquals(1, a.subqueries());
        assertEquals(List.of("txn.amount"), cols(a, Access.READ, "select list").stream().distinct().toList());
        assertEquals("total", a.selectItems().get(0).label());
    }

    @Test
    void updateRecordsWritesReadsAndSetParameters() throws Exception {
        SqlAnalysis a = analyze("UPDATE shipment SET quantity = ?, status = ? WHERE id = ?");
        assertEquals("UPDATE", a.type());
        assertEquals(Access.UPDATE, a.tables().get(0).access());
        assertEquals(List.of("shipment.quantity", "shipment.status"), cols(a, Access.UPDATE, "set clause"));
        assertEquals(List.of("shipment.id"), cols(a, Access.READ, "where"));
        assertEquals(List.of("shipment.quantity", "shipment.status", "shipment.id"),
                a.params().stream().map(Param::column).toList());
        assertTrue(a.params().stream().allMatch(Param::direct));
    }

    @Test
    void parameterInsideAnExpressionIsNotDirect() throws Exception {
        SqlAnalysis a = analyze("UPDATE shipment SET quantity = quantity + ? WHERE id = ?");
        assertEquals("shipment.quantity", a.params().get(0).column());
        assertFalse(a.params().get(0).direct());
        assertEquals(List.of("shipment.quantity"), cols(a, Access.READ, "set expression"));
    }

    @Test
    void insertValuesMapParametersByPosition() throws Exception {
        SqlAnalysis a = analyze("INSERT INTO shipment (id, status, quantity) VALUES (?, 'NEW', ?)");
        assertEquals(Access.INSERT, a.tables().get(0).access());
        assertEquals(List.of("shipment.id", "shipment.status", "shipment.quantity"), a.insertColumns());
        assertEquals(List.of("shipment.id", "shipment.quantity"), a.params().stream().map(Param::column).toList());
    }

    @Test
    void insertSelectKeepsTargetColumnsAndSelectItemsForMapping() throws Exception {
        SqlAnalysis a = analyze("INSERT INTO archive (id, quantity) SELECT id, quantity FROM shipment WHERE status = ?");
        assertEquals(List.of("archive.id", "archive.quantity"), a.insertColumns());
        assertEquals(List.of("shipment.id", "shipment.quantity"),
                a.selectItems().stream().flatMap(s -> s.sources().stream()).toList());
        assertEquals("shipment.status", a.params().get(0).column());
    }

    @Test
    void insertWithoutColumnListNeedsSchemaOrIsFlagged() throws Exception {
        SqlAnalysis flagged = analyze("INSERT INTO shipment VALUES (?, ?)");
        assertTrue(flagged.insertColumns().isEmpty());
        assertFalse(flagged.unsupported().isEmpty());

        SchemaCatalog catalog = new SchemaCatalog();
        catalog.addColumn("shipment", "id", "BIGINT");
        catalog.addColumn("shipment", "quantity", "DECIMAL");
        SqlAnalysis known = analyze(catalog, "INSERT INTO shipment VALUES (?, ?)");
        assertEquals(List.of("shipment.id", "shipment.quantity"), known.insertColumns());
        assertEquals(List.of("shipment.id", "shipment.quantity"), known.params().stream().map(Param::column).toList());
    }

    @Test
    void deleteTargetsTableAndReadsPredicateColumns() throws Exception {
        SqlAnalysis a = analyze("DELETE FROM shipment WHERE status = 'ARCHIVED' AND quantity = ?");
        assertEquals(Access.DELETE, a.tables().get(0).access());
        assertEquals(List.of("shipment.status", "shipment.quantity"), cols(a, Access.READ, "where"));
    }

    @Test
    void dynamicTokenIsFlaggedAndNeverBecomesATableOrColumn() throws Exception {
        SqlAnalysis a = analyze("SELECT id FROM shipment WHERE status = __DYN_1__ AND id = ?");
        assertTrue(a.dynamic());
        assertTrue(a.columns().stream().noneMatch(c -> c.column().startsWith("__dyn")));

        SqlAnalysis t = analyze("SELECT x FROM __DYN_1__ WHERE y = ?");
        assertTrue(t.dynamic());
        assertTrue(t.tables().isEmpty());
        assertFalse(t.columns().get(0).resolved());
    }

    @Test
    void jdbcCallEscapeAndExecAreRoutines() throws Exception {
        SqlAnalysis call = analyze("{? = call shipping.close_shipment(?, ?)}");
        assertEquals("CALL", call.type());
        assertEquals(List.of("shipping.close_shipment"), call.routines());
        assertEquals(3, call.params().size());

        SqlAnalysis exec = analyze("EXEC close_shipment ?, ?");
        assertEquals(List.of("close_shipment"), exec.routines());
        assertEquals(2, exec.params().size());
    }

    @Test
    void unsupportedStatementsAreReportedNotIgnored() throws Exception {
        SqlAnalysis a = analyze("MERGE INTO t USING s ON (t.id = s.id) WHEN MATCHED THEN UPDATE SET t.a = s.a");
        assertFalse(a.isDml());
        assertFalse(a.unsupported().isEmpty(), a.type());
    }

    @Test
    void createTableYieldsDefinition() throws Exception {
        var def = SqlAnalyzer.tableDefinition("CREATE TABLE Shipment (id BIGINT PRIMARY KEY, quantity DECIMAL(15, 2) NOT NULL)")
                .orElseThrow();
        assertEquals("shipment", def.table());
        assertEquals(List.of("id", "quantity"), def.columns().stream().map(SqlAnalyzer.ColumnDef::name).toList());
        assertTrue(def.columns().get(0).specs().contains("PRIMARY KEY"));
        assertEquals("DECIMAL (15, 2)", def.columns().get(1).type().replace("DECIMAL(", "DECIMAL ("));
        assertTrue(SqlAnalyzer.tableDefinition("SELECT 1 FROM t").isEmpty());
    }

    @Test
    void unparsableSqlThrows() {
        assertThrows(Exception.class, () -> analyze("SELEC nothing FROM"));
    }

    @Test
    void joinedTablesAreListedInOrderForAllJoinKinds() throws Exception {
        SqlAnalysis a = analyze("SELECT 1 FROM a, b INNER JOIN c ON c.x = b.x");
        assertEquals("a,b,c", a.tables().stream().map(SqlAnalysis.TableUse::name).collect(Collectors.joining(",")));
    }
}
