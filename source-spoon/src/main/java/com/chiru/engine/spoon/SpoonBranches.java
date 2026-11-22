package com.chiru.engine.spoon;

import com.chiru.engine.model.EntityId;
import com.chiru.engine.model.EntityType;
import java.util.stream.Collectors;
import spoon.reflect.code.*;
import spoon.reflect.cu.SourcePosition;
import spoon.reflect.declaration.CtConstructor;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtMethod;
import spoon.reflect.declaration.CtTypeMember;

/**
 * Branch identity and nesting, shared so every stage computes exactly the BRANCH IDs the source stage wrote.
 * A branch is an if, switch, loop, try or ternary; its "arm" says which part of it a nested element sits in.
 */
public final class SpoonBranches {

    /** The innermost enclosing branch (or the method when there is none) and the arm of that branch. */
    public record Placement(EntityId parent, String arm) {}

    private SpoonBranches() {}

    public static String branchKind(CtElement e) {
        if (e instanceof CtIf) return "if";
        if (e instanceof CtSwitchExpression<?, ?>) return "switch-expression";
        if (e instanceof CtSwitch<?>) return "switch";
        if (e instanceof CtForEach) return "foreach";
        if (e instanceof CtFor) return "for";
        if (e instanceof CtWhile) return "while";
        if (e instanceof CtDo) return "do-while";
        if (e instanceof CtTry) return "try";
        if (e instanceof CtConditional<?>) return "ternary";
        return null;
    }

    /** {@code branch:<method key>@line:col-end:kind}, or null outside a method or without a source position. */
    public static EntityId branchId(CtElement e, String kind) {
        CtTypeMember member = SpoonIds.enclosingMember(e);
        SourcePosition pos = e.getPosition();
        if (member == null || pos == null || !pos.isValidPosition()) {
            return null;
        }
        return EntityId.of(EntityType.BRANCH, SpoonIds.methodKey(member) + "@" + pos.getLine() + ":" + pos.getColumn()
                + "-" + pos.getSourceEnd() + ":" + kind);
    }

    public static EntityId branchId(CtElement e) {
        String kind = branchKind(e);
        return kind == null ? null : branchId(e, kind);
    }

    public static Placement placement(CtElement e, EntityId memberId) {
        CtElement prev = e;
        while (prev.isParentInitialized()) {
            CtElement p = prev.getParent();
            if (p instanceof CtMethod<?> || p instanceof CtConstructor<?>) {
                break;
            }
            String kind = branchKind(p);
            if (kind != null) {
                EntityId pid = branchId(p, kind);
                if (pid != null) {
                    return new Placement(pid, arm(p, prev));
                }
            }
            prev = p;
        }
        return new Placement(memberId, null);
    }

    public static String arm(CtElement parent, CtElement child) {
        if (parent instanceof CtIf i) {
            return child == i.getThenStatement() ? "then" : child == i.getElseStatement() ? "else" : "condition";
        }
        if (parent instanceof CtConditional<?> c) {
            return child == c.getThenExpression() ? "then" : child == c.getElseExpression() ? "else" : "condition";
        }
        if (parent instanceof CtAbstractSwitch<?>) {
            return child instanceof CtCase<?> c ? caseLabel(c) : "selector";
        }
        if (parent instanceof CtLoop l) {
            return child == l.getBody() ? "body" : "condition";
        }
        if (parent instanceof CtTry t) {
            if (child == t.getBody()) return "body";
            if (child == t.getFinalizer()) return "finally";
            if (child instanceof CtCatch c) return "catch " + SpoonIds.typeName(c.getParameter().getType());
            return "resources";
        }
        return null;
    }

    public static CtElement conditionOf(CtElement e) {
        if (e instanceof CtIf i) return i.getCondition();
        if (e instanceof CtConditional<?> c) return c.getCondition();
        if (e instanceof CtWhile w) return w.getLoopingExpression();
        if (e instanceof CtDo d) return d.getLoopingExpression();
        if (e instanceof CtFor f) return f.getExpression();
        if (e instanceof CtForEach f) return f.getExpression();
        if (e instanceof CtAbstractSwitch<?> s) return s.getSelector();
        return null;
    }

    public static String caseLabel(CtCase<?> c) {
        return c.getCaseExpressions().isEmpty() ? "default"
                : c.getCaseExpressions().stream().map(Object::toString).collect(Collectors.joining(" | "));
    }
}
