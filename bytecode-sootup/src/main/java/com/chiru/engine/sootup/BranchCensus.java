package com.chiru.engine.sootup;

import com.chiru.engine.model.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import sootup.core.jimple.common.stmt.Stmt;
import sootup.core.model.SootMethod;
import sootup.java.core.JavaSootClass;
import sootup.java.core.views.JavaView;

/**
 * An independent count of the branches the compiler produced, per class: every conditional jump and every switch in the method
 * bodies (lambdas and other synthetic methods included, because their code belongs to the class). The count is written to the CLASS
 * entity as {@code bytecodeBranches} so validation can compare it with the decisions the source analysis found: a class that
 * branches in bytecode but has no decision in the model has had a decision missed (V017). It never changes any other fact.
 *
 * <p>It is a cross-check by existence, not by number: one source decision can compile to several jumps (a loop, {@code &&}, a
 * string switch) and a constant condition can compile to none.
 */
final class BranchCensus {

    private BranchCensus() {}

    static int write(AnalysisModel model, JavaView view) {
        Map<String, Integer> perClass = new LinkedHashMap<>();
        for (JavaSootClass cls : view.getClasses().toList()) {
            int branches = 0;
            for (SootMethod m : cls.getMethods()) {
                if (!m.hasBody()) {
                    continue;
                }
                for (Stmt s : m.getBody().getStmts()) {
                    if (s.getExpectedSuccessorCount() > 1) {          // a conditional jump or a switch
                        branches++;
                    }
                }
            }
            perClass.put(cls.getType().getFullyQualifiedName(), branches);
        }
        int written = 0;
        for (Map.Entry<String, Integer> e : perClass.entrySet()) {
            EntityId id = EntityId.of(EntityType.CLASS, e.getKey());
            if (model.entity(id).isPresent()) {
                model.putEntity(new Entity(id, model.entity(id).get().name(), Map.of("bytecodeBranches", String.valueOf(e.getValue())), List.of()));
                written++;
            }
        }
        return written;
    }
}
