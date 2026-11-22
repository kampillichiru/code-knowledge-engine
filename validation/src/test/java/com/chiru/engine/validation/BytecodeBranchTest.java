package com.chiru.engine.validation;

import static org.junit.jupiter.api.Assertions.*;

import com.chiru.engine.model.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** V017: bytecode branches in a class with no decision in the source model. */
class BytecodeBranchTest {

    private static AnalysisModel model(String bytecodeBranches, boolean decisionInClass) {
        AnalysisModel m = new AnalysisModel();
        m.putEntity(new Entity(EntityId.of(EntityType.CLASS, "p.A"), "A", Map.of("bytecodeBranches", bytecodeBranches), List.of()));
        String owner = decisionInClass ? "method:p.A#f()" : "method:p.B#f()";
        m.putEntity(new Entity(EntityId.of(EntityType.PATH_DECISION, "p.B#f()@d1"), "IF x",
                Map.of("kind", "IF", "method", owner, "line", "3"), List.of()));
        return m;
    }

    @Test
    void aClassThatBranchesInBytecodeButHasNoDecisionInTheModelIsAWarning() {
        Finding f = ModelValidator.validate(model("2", false), List.of()).stream().filter(x -> x.code().equals("V017")).findFirst().orElseThrow();
        assertEquals(AnalysisWarning.Severity.WARNING, f.severity());
        assertTrue(f.examples().get(0).contains("A (2 branch(es) in bytecode)"), f.examples().toString());
    }

    @Test
    void noFindingWhenTheSourceModelHasADecisionInTheClassOrTheBytecodeDoesNotBranch() {
        assertTrue(ModelValidator.validate(model("2", true), List.of()).stream().noneMatch(x -> x.code().equals("V017")));
        assertTrue(ModelValidator.validate(model("0", false), List.of()).stream().noneMatch(x -> x.code().equals("V017")));
    }

    @Test
    void withoutThePathStageThereIsNothingToCompareWith() {
        AnalysisModel m = new AnalysisModel();
        m.putEntity(new Entity(EntityId.of(EntityType.CLASS, "p.A"), "A", Map.of("bytecodeBranches", "5"), List.of()));
        assertTrue(ModelValidator.validate(m, List.of()).stream().noneMatch(x -> x.code().equals("V017")));
    }
}
