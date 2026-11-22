package com.chiru.engine.core;

import static org.junit.jupiter.api.Assertions.*;

import com.chiru.engine.model.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CallGraphMermaidTest {

    private static final Evidence EV = Evidence.extracted("test", new SourceLocation("A.java", 1, 1));

    private static EntityId method(AnalysisModel m, String key, boolean external) {
        EntityId id = EntityId.of(EntityType.METHOD, key);
        m.putEntity(new Entity(id, key, external ? Map.of("external", "true") : Map.of(), List.of(EV)));
        return id;
    }

    private static void call(AnalysisModel m, EntityId a, EntityId b, TraceStatus s) {
        m.addRelationship(new Relationship(a, RelationType.CALLS, b, s, List.of(EV)));
    }

    @Test
    void rendersStatusesAndHidesExternalByDefault() {
        AnalysisModel m = new AnalysisModel();
        EntityId main = method(m, "p.Main#main(java.lang.String[])", false);
        EntityId svc = method(m, "p.Svc#run()", false);
        EntityId impl = method(m, "p.Impl#<init>()", false);
        EntityId lib = method(m, "java.math.BigDecimal#add(java.math.BigDecimal)", true);
        call(m, main, svc, TraceStatus.VERIFIED);
        call(m, svc, impl, TraceStatus.PARTIAL);
        call(m, svc, lib, TraceStatus.PARTIAL);

        String d = CallGraphMermaid.render(m, main, 5, false);
        assertTrue(d.startsWith("flowchart TD"));
        assertTrue(d.contains("n0[\"Main.main\"]"));
        assertTrue(d.contains("n0 --> n1"), d);
        assertTrue(d.contains("n1 -.-> n2"), d);
        assertTrue(d.contains("Impl.constructor"));
        assertFalse(d.contains("BigDecimal"));

        String withExternal = CallGraphMermaid.render(m, main, 5, true);
        assertTrue(withExternal.contains("([\"BigDecimal.add\"])"), withExternal);
    }

    @Test
    void respectsDepthLimit() {
        AnalysisModel m = new AnalysisModel();
        EntityId a = method(m, "p.A#a()", false);
        EntityId b = method(m, "p.B#b()", false);
        EntityId c = method(m, "p.C#c()", false);
        call(m, a, b, TraceStatus.VERIFIED);
        call(m, b, c, TraceStatus.VERIFIED);

        String d = CallGraphMermaid.render(m, a, 1, false);
        assertTrue(d.contains("B.b"));
        assertFalse(d.contains("C.c"));
    }
}
