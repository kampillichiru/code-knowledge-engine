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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Phase 6a: variables, defining expressions and DERIVED_FROM / GUARDED_BY edges on the sample app. */
class DataFlowTest {

    private static final Path SAMPLE = Path.of("..", "sample-app").toAbsolutePath().normalize();
    private static final String PKG = "com.sample.shipping.";
    private static final String STORAGE = PKG + "StorageService#handle(" + PKG + "Shipment)";
    private static final String FEE = PKG + "FeeCalculator#feeFor(java.math.BigDecimal,java.math.BigDecimal)";
    private static final String RATE = PKG + "ExternalRateClient#fetchAdjustment(long)";
    private static AnalysisModel model;

    @BeforeAll
    static void analyseSample() {
        model = new AnalysisModel();
        AnalysisConfig cfg = new AnalysisConfig(List.of(SAMPLE.toString()), "out", 21, List.of(), false);
        Pipeline.Result r = new Pipeline(List.of(new DiscoveryStage(), new SpoonSourceStage()))
                .run(new StageContext(cfg, model));
        assertTrue(r.succeeded(), r.toString());
    }

    private static EntityId variable(String methodKey, String name) {
        List<Entity> found = model.entitiesOfType(EntityType.VARIABLE).stream()
                .filter(e -> e.id().value().startsWith("variable:" + methodKey + "#" + name + "@")).toList();
        assertEquals(1, found.size(), "variable " + name + " in " + methodKey);
        return found.get(0).id();
    }

    private static EntityId field(String type, String name) {
        return EntityId.of(EntityType.FIELD, PKG + type + "#" + name);
    }

    private static List<Entity> definitions(EntityId target) {
        return model.outgoing(target, RelationType.DERIVED_FROM).stream()
                .map(r -> model.entity(r.to()).orElseThrow())
                .filter(e -> e.type() == EntityType.EXPRESSION).toList();
    }

    private static boolean derivesFrom(EntityId from, EntityId to) {
        return model.outgoing(from, RelationType.DERIVED_FROM).stream().anyMatch(r -> r.to().equals(to));
    }

    private static int lineOf(String file, String text) throws Exception {
        List<String> lines = Files.readAllLines(SAMPLE.resolve("src/main/java/com/sample/shipping/" + file));
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(text)) return i + 1;
        }
        throw new AssertionError(text + " not found in " + file);
    }

    @Test
    void declaresParametersAndLocals() {
        assertEquals("parameter", model.entity(variable(STORAGE, "shipment")).orElseThrow().attributes().get("kind"));
        assertEquals("local", model.entity(variable(STORAGE, "surcharge")).orElseThrow().attributes().get("kind"));
        assertEquals("java.math.BigDecimal",
                model.entity(variable(STORAGE, "newQuantity")).orElseThrow().attributes().get("type"));
        assertTrue(model.incoming(variable(STORAGE, "surcharge"), RelationType.CONTAINS).stream()
                .anyMatch(r -> r.from().equals(EntityId.of(EntityType.METHOD, STORAGE))));
    }

    @Test
    void keepsEveryDefinitionOfAVariableWithItsBranchArm() throws Exception {
        List<Entity> defs = definitions(variable(STORAGE, "surcharge"));
        assertEquals(3, defs.size(), "initialiser plus the two arms of the overdue rule");

        Entity penaltyMultiply = defs.stream().filter(e -> e.attributes().get("literals").contains("1.5"))
                .findFirst().orElseThrow();
        assertEquals("then", penaltyMultiply.attributes().get("parentArm"));
        assertEquals(lineOf("StorageService.java", "surcharge = surcharge.multiply"),
                penaltyMultiply.evidence().get(0).location().startLine());

        Entity penaltyAdd = defs.stream().filter(e -> e.attributes().get("literals").contains("1.00"))
                .findFirst().orElseThrow();
        assertEquals("else", penaltyAdd.attributes().get("parentArm"));

        Entity init = defs.stream().filter(e -> e.attributes().get("literals").contains("1200"))
                .findFirst().orElseThrow();
        assertNull(init.attributes().get("parentArm"));
        assertTrue(init.attributes().get("operators") == null || !init.attributes().get("operators").isBlank());
    }

    @Test
    void guardedByTheInnerLargeQuantityBranch() {
        Entity penaltyMultiply = definitions(variable(STORAGE, "surcharge")).stream()
                .filter(e -> e.attributes().get("literals").contains("1.5")).findFirst().orElseThrow();
        List<Relationship> guards = model.outgoing(penaltyMultiply.id(), RelationType.GUARDED_BY);
        assertEquals(1, guards.size());
        Entity branch = model.entity(guards.get(0).to()).orElseThrow();
        assertEquals("if", branch.attributes().get("kind"));
        assertTrue(branch.attributes().get("condition").contains("LARGE_QUANTITY"));
        assertTrue(derivesFrom(branch.id(), field("StorageService", "LARGE_QUANTITY")),
                "the condition reads the LARGE_QUANTITY constant");
        assertTrue(model.entity(field("StorageService", "LARGE_QUANTITY")).orElseThrow()
                .attributes().get("initializer").contains("1000"));
    }

    @Test
    void operandsOfAnExpressionAreVariablesFieldsAndCalls() {
        Entity newQuantity = definitions(variable(STORAGE, "newQuantity")).get(0);
        assertTrue(derivesFrom(newQuantity.id(), variable(STORAGE, "surcharge")));
        assertTrue(derivesFrom(newQuantity.id(), variable(STORAGE, "shipment")));
        assertTrue(model.outgoing(newQuantity.id(), RelationType.DERIVED_FROM).stream()
                .anyMatch(r -> r.to().type() == EntityType.INVOCATION), "shipment.getQuantity() is an operand");
    }

    @Test
    void feeCalculationTracksConstantsAndTheCap() {
        EntityId fee = variable(FEE, "fee");
        List<Entity> defs = definitions(fee);
        assertEquals(2, defs.size());

        Entity base = defs.stream().filter(e -> "init".equals(e.attributes().get("kind"))).findFirst().orElseThrow();
        assertTrue(derivesFrom(base.id(), field("FeeCalculator", "BASE_FEE")));
        assertTrue(derivesFrom(base.id(), variable(FEE, "amount")));
        assertTrue(derivesFrom(base.id(), variable(FEE, "ratePct")));
        assertTrue(base.attributes().get("literals").contains("100"));
        assertTrue(base.attributes().get("externalOperands").contains("HALF_UP"),
                "JDK constants are listed, not silently dropped");

        Entity cap = defs.stream().filter(e -> "assign".equals(e.attributes().get("kind"))).findFirst().orElseThrow();
        assertEquals("then", cap.attributes().get("parentArm"));
        assertTrue(derivesFrom(cap.id(), field("FeeCalculator", "FEE_CAP")));
    }

    @Test
    void returnStatementsBecomeExpressions() {
        List<Entity> returns = model.entitiesOfType(EntityType.EXPRESSION).stream()
                .filter(e -> e.id().value().startsWith("expression:" + FEE) && "return".equals(e.attributes().get("kind")))
                .toList();
        assertEquals(1, returns.size());
        assertTrue(derivesFrom(returns.get(0).id(), variable(FEE, "fee")));
    }

    @Test
    void valueBehindAnExternalCallStaysPartial() {
        List<Entity> returns = model.entitiesOfType(EntityType.EXPRESSION).stream()
                .filter(e -> e.id().value().startsWith("expression:" + RATE) && "return".equals(e.attributes().get("kind")))
                .toList();
        assertEquals(1, returns.size());
        List<Relationship> edges = model.outgoing(returns.get(0).id(), RelationType.DERIVED_FROM);
        assertTrue(edges.stream().anyMatch(r -> r.to().type() == EntityType.INVOCATION
                && r.status() == TraceStatus.PARTIAL), "System.getProperty and new BigDecimal are outside the sources");
        assertTrue(edges.stream().noneMatch(r -> r.to().type() == EntityType.INVOCATION
                && r.status() == TraceStatus.VERIFIED));
        assertTrue(derivesFrom(returns.get(0).id(), variable(RATE, "shipmentId")));
    }

    private static final String RECORD_CTOR = PKG + "ManifestRecord#<init>(long,java.lang.String,"
            + "java.math.BigDecimal,java.math.BigDecimal,java.math.BigDecimal)";

    @Test
    void parametersCarryTheirPosition() {
        assertEquals("3", model.entity(variable(RECORD_CTOR, "quantity")).orElseThrow().attributes().get("index"));
        assertEquals("1", model.entity(variable(RECORD_CTOR, "shipmentId")).orElseThrow().attributes().get("index"));
    }

    @Test
    void constructorArgumentFlowsIntoTheParameter() {
        EntityId quantityParam = variable(RECORD_CTOR, "quantity");
        List<Relationship> bound = model.outgoing(quantityParam, RelationType.DERIVED_FROM).stream()
                .filter(r -> r.to().type() == EntityType.EXPRESSION).toList();
        assertFalse(bound.isEmpty(), "every new ManifestRecord(...) call site binds argument 3");
        assertTrue(bound.stream().allMatch(r -> r.status() == TraceStatus.VERIFIED), "constructors bind statically");

        Entity storageArg = bound.stream().map(r -> model.entity(r.to()).orElseThrow())
                .filter(e -> e.evidence().get(0).location().file().endsWith("StorageService.java")).findFirst().orElseThrow();
        assertEquals("argument", storageArg.attributes().get("kind"));
        assertEquals("3", storageArg.attributes().get("index"));
        assertEquals("newQuantity", storageArg.attributes().get("text"));
        assertTrue(derivesFrom(storageArg.id(), variable(STORAGE, "newQuantity")));
    }

    @Test
    void dynamicDispatchBindsEveryPossibleImplementerAsPartial() {
        String route = PKG + "ScenarioRouter#route(" + PKG + "Shipment)";
        Entity arg = model.entitiesOfType(EntityType.EXPRESSION).stream()
                .filter(e -> "argument".equals(e.attributes().get("kind")) && e.id().value().startsWith("expression:" + route)
                        && e.attributes().get("invocation").contains("@") && e.attributes().get("text").equals("shipment"))
                .filter(e -> model.outgoing(e.id(), RelationType.GUARDED_BY).stream()
                        .anyMatch(g -> "\"STORAGE\"".equals(e.attributes().get("parentArm")))).findFirst().orElseThrow();
        List<EntityId> bound = model.incoming(arg.id(), RelationType.DERIVED_FROM).stream()
                .map(Relationship::from).filter(id -> id.type() == EntityType.VARIABLE).toList();
        assertTrue(bound.stream().anyMatch(id -> id.value().startsWith("variable:" + PKG + "StorageService#handle(")),
                "the storage handler may receive this argument");
        assertTrue(model.incoming(arg.id(), RelationType.DERIVED_FROM).stream()
                .allMatch(r -> r.status() == TraceStatus.PARTIAL), "interface dispatch is never VERIFIED");
    }

    @Test
    void callResultIsDerivedFromTheCalleesReturn() {
        String handle = PKG + "DispatchService#handle(" + PKG + "Shipment)";
        Entity call = model.entitiesOfType(EntityType.INVOCATION).stream()
                .filter(e -> e.id().value().startsWith("invocation:" + handle) && e.attributes().get("text").contains("feeFor("))
                .findFirst().orElseThrow();
        List<Relationship> results = model.outgoing(call.id(), RelationType.DERIVED_FROM);
        assertEquals(1, results.size());
        assertEquals(TraceStatus.VERIFIED, results.get(0).status());
        assertTrue(results.get(0).to().value().startsWith("expression:" + FEE));
        assertEquals("return", model.entity(results.get(0).to()).orElseThrow().attributes().get("kind"));
    }

    @Test
    void stringConcatenationIsKeptAsOrderedPartsWithWidths() {
        String format = PKG + "ManifestWriter#format(" + PKG + "ManifestRecord)";
        List<Entity> parts = model.entitiesOfType(EntityType.EXPRESSION).stream()
                .filter(e -> "part".equals(e.attributes().get("kind")) && e.id().value().startsWith("expression:" + format))
                .sorted(java.util.Comparator.comparingInt(e -> Integer.parseInt(e.attributes().get("index")))).toList();
        assertEquals(5, parts.size());
        assertEquals(List.of("10", "8", "15", "10", "10"), parts.stream().map(p -> p.attributes().get("width")).toList());
        assertTrue(parts.get(0).attributes().get("widthSource").contains("padLeft"));
        assertTrue(parts.get(2).attributes().get("text").contains("amount("));
        assertTrue(model.outgoing(parts.get(2).id(), RelationType.DERIVED_FROM).stream()
                .anyMatch(r -> r.to().type() == EntityType.INVOCATION), "a part keeps its operands");
        assertEquals(0, model.entitiesOfType(EntityType.EXPRESSION).stream()
                .filter(e -> "part".equals(e.attributes().get("kind"))
                        && e.id().value().startsWith("expression:" + RATE)).count(),
                "string building inside a call argument is not a layout");
    }

    @Test
    void callRecordsTheVariableItIsMadeOn() {
        String handle = PKG + "DispatchService#handle(" + PKG + "Shipment)";
        Entity call = model.entitiesOfType(EntityType.INVOCATION).stream()
                .filter(e -> e.id().value().startsWith("invocation:" + handle) && e.attributes().get("text").endsWith("getQuantity()"))
                .findFirst().orElseThrow();
        assertEquals(variable(handle, "shipment").value(), call.attributes().get("receiver"));
    }

    @Test
    void everyNewFactHasEvidence() {
        assertTrue(model.findProvenanceGaps().isEmpty(), model.findProvenanceGaps().toString());
        assertFalse(model.entitiesOfType(EntityType.EXPRESSION).isEmpty());
    }
}
