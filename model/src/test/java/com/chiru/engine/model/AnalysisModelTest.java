package com.chiru.engine.model;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AnalysisModelTest {

    private static final SourceLocation LOC = new SourceLocation("a/Foo.java", 10, 12);
    private static final Evidence EV = Evidence.extracted("test", LOC);

    private static Entity entity(EntityType type, String key, Map<String, String> attrs, Evidence... ev) {
        return new Entity(EntityId.of(type, key), key, attrs, List.of(ev));
    }

    @Test
    void entityIdIsStableAndTyped() {
        EntityId id = EntityId.of(EntityType.METHOD, "com.x.Foo#bar(int)");
        assertEquals("method:com.x.Foo#bar(int)", id.value());
        assertEquals(EntityType.METHOD, id.type());
        assertThrows(IllegalArgumentException.class, () -> new EntityId("nocolon"));
    }

    @Test
    void sourceLocationValidatesAndNormalizes() {
        assertEquals("a/B.java:3-4", new SourceLocation("a\\B.java", 3, 4).toString());
        assertThrows(IllegalArgumentException.class, () -> new SourceLocation("a", 5, 4));
    }

    @Test
    void mergingEntityAccumulatesEvidenceAndWarnsOnConflict() {
        AnalysisModel m = new AnalysisModel();
        Evidence ev2 = Evidence.extracted("other", new SourceLocation("a/Foo.java", 20, 20));
        m.putEntity(entity(EntityType.CLASS, "com.x.Foo", Map.of("kind", "class"), EV));
        m.putEntity(entity(EntityType.CLASS, "com.x.Foo", Map.of("kind", "interface", "pkg", "x"), ev2));

        Entity merged = m.entity(EntityId.of(EntityType.CLASS, "com.x.Foo")).orElseThrow();
        assertEquals(2, merged.evidence().size());
        assertEquals("class", merged.attributes().get("kind"));
        assertEquals("x", merged.attributes().get("pkg"));
        assertEquals(1, m.warnings().size());
        assertEquals(1, m.entities().size());
    }

    @Test
    void relationshipRequiresExistingEndpoints() {
        AnalysisModel m = new AnalysisModel();
        m.putEntity(entity(EntityType.METHOD, "A#a()", Map.of(), EV));
        Relationship dangling = new Relationship(EntityId.of(EntityType.METHOD, "A#a()"),
                RelationType.CALLS, EntityId.of(EntityType.METHOD, "B#b()"), TraceStatus.VERIFIED, List.of(EV));
        assertThrows(IllegalArgumentException.class, () -> m.addRelationship(dangling));
    }

    @Test
    void repeatedRelationshipTakesWeakerStatusAndNeverStrengthens() {
        AnalysisModel m = new AnalysisModel();
        m.putEntity(entity(EntityType.METHOD, "A#a()", Map.of(), EV));
        m.putEntity(entity(EntityType.METHOD, "B#b()", Map.of(), EV));
        EntityId a = EntityId.of(EntityType.METHOD, "A#a()");
        EntityId b = EntityId.of(EntityType.METHOD, "B#b()");

        m.addRelationship(new Relationship(a, RelationType.CALLS, b, TraceStatus.PARTIAL, List.of(EV)));
        Evidence ev2 = Evidence.extracted("sootup", new SourceLocation("A.java", 1, 1));
        Relationship merged = m.addRelationship(
                new Relationship(a, RelationType.CALLS, b, TraceStatus.VERIFIED, List.of(ev2)));

        assertEquals(TraceStatus.PARTIAL, merged.status());
        assertEquals(2, merged.evidence().size());
        assertEquals(1, m.relationships().size());
        assertEquals(1, m.outgoing(a, RelationType.CALLS).size());
        assertEquals(1, m.incoming(b, RelationType.CALLS).size());
    }

    @Test
    void chainStatusNeverClaimsVerifiedWithMissingLinks() {
        assertEquals(TraceStatus.VERIFIED, TraceStatus.ofChain(List.of(TraceStatus.VERIFIED, TraceStatus.VERIFIED)));
        assertEquals(TraceStatus.PARTIAL, TraceStatus.ofChain(List.of(TraceStatus.VERIFIED, TraceStatus.UNRESOLVED)));
        assertEquals(TraceStatus.UNRESOLVED, TraceStatus.ofChain(List.of(TraceStatus.UNRESOLVED)));
        assertEquals(TraceStatus.UNRESOLVED, TraceStatus.ofChain(List.of()));
    }

    @Test
    void provenanceGapsAreReported() {
        AnalysisModel m = new AnalysisModel();
        m.putEntity(entity(EntityType.TABLE, "SHIPMENT", Map.of()));
        assertEquals(1, m.findProvenanceGaps().size());
    }

    @Test
    void snapshotRoundTripPreservesContentAndChecksVersion() {
        AnalysisModel m = new AnalysisModel();
        m.putEntity(entity(EntityType.CLASS, "com.x.Foo", Map.of(), EV));
        m.addWarning(new AnalysisWarning("s", AnalysisWarning.Severity.INFO, "hi", null));

        AnalysisModel copy = AnalysisModel.fromSnapshot(m.snapshot());
        assertEquals(m.entities(), copy.entities());
        assertEquals(m.warnings(), copy.warnings());

        ModelSnapshot bad = new ModelSnapshot(99, List.of(), List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> AnalysisModel.fromSnapshot(bad));
    }
}
