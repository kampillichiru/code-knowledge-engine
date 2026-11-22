package com.chiru.engine.paths;

import static org.junit.jupiter.api.Assertions.*;

import com.chiru.engine.paths.PathTree.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Control-flow semantics of the flattener, on hand-built trees (no parser involved). */
class PathFlattenerTest {

    private static int ids;

    private static Effect fx(String text) {
        return new Effect(text, 1, "ASSIGN");
    }

    private static Exit ret(String value) {
        return new Exit("RETURN", null, value, 1);
    }

    private static Decision decision(String kind, String label, boolean exprLevel, List<Item> prologue, List<Arm> arms, List<Item> fin) {
        return new Decision(kind + "#" + (++ids), kind, 1, 1, 1, "c", label, exprLevel, prologue, arms, fin);
    }

    private static Decision iff(String cond, List<Item> then, List<Item> otherwise) {
        return decision("IF", null, false, List.of(), List.of(new Arm("then", cond, then), new Arm("else", "not (" + cond + ")", otherwise)), List.of());
    }

    private static Decision loop(String label, List<Item> body) {
        return decision("LOOP", label, false, List.of(), List.of(new Arm("enter", "runs", body), new Arm("exit", "skipped", List.of())), List.of());
    }

    private static PathFlattener flatten(int cap, Item... items) {
        PathFlattener f = new PathFlattener(cap);
        f.run(List.of(items));
        return f;
    }

    private static List<String> effects(PathFlattener f, int row) {
        return f.rows.get(row).effects();
    }

    // ---------------------------------------------------------------- splitting and the cap

    @Test
    void sequentialDecisionsMultiplyAndExitsEndPaths() {
        PathFlattener f = flatten(100, iff("a", List.of(fx("A")), List.of()), iff("b", List.of(fx("B")), List.of()), fx("end"));
        assertEquals(4, f.rows.size());
        assertEquals(List.of("A", "B", "end"), effects(f, 0));
        assertEquals(List.of("end"), effects(f, 3));
        assertFalse(f.capped);
        assertEquals("FALL", f.rows.get(0).exit());
    }

    @Test
    void anExitInOneArmEndsThatPathAndTheRestRunsOnlyOnTheOther() {
        PathFlattener f = flatten(100, iff("x < 0", List.of(ret("-1")), List.of()), fx("x = x + 5"), ret("x"));
        assertEquals(2, f.rows.size());
        assertEquals(List.of("x < 0"), f.rows.get(0).when());
        assertEquals(List.of(), effects(f, 0));
        assertEquals("-1", f.rows.get(0).value());
        assertEquals(List.of("not (x < 0)"), f.rows.get(1).when());
        assertEquals(List.of("x = x + 5"), effects(f, 1));
    }

    @Test
    void theCapStopsFlatteningAndSaysSo() {
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            items.add(iff("c" + i, List.of(fx("e" + i)), List.of()));
        }
        PathFlattener f = new PathFlattener(16);
        f.run(items);
        assertEquals(16, f.rows.size());
        assertTrue(f.capped, "2^10 paths do not fit");
    }

    // ---------------------------------------------------------------- break and continue

    @Test
    void breakLeavesTheLoopAndContinuesAfterIt() {
        PathFlattener f = flatten(100, loop(null, List.of(iff("done", List.of(new Exit("BREAK", null, null, 1)), List.of()), fx("work"))), fx("after"));
        // enter + break: after | enter + not done: work, after | exit: after
        assertEquals(3, f.rows.size());
        assertEquals(List.of("after"), effects(f, 0));
        assertEquals(List.of("work", "after"), effects(f, 1));
        assertEquals(List.of("after"), effects(f, 2));
    }

    @Test
    void anUnlabeledContinueTargetsTheLoopNotTheSwitchInsideIt() {
        Decision sw = decision("SWITCH", null, false, List.of(), List.of(
                new Arm("1", "k == 1", List.of(new Exit("CONTINUE", null, null, 1))),
                new Arm("default (implicit)", "other", List.of())), List.of());
        PathFlattener f = flatten(100, loop(null, List.of(sw, fx("tail"))), fx("after"));
        List<List<String>> all = f.rows.stream().map(PathFlattener.Row::effects).toList();
        assertTrue(all.contains(List.of("after")), "continue ends the pass: no tail");
        assertTrue(all.contains(List.of("tail", "after")), "the implicit default runs the tail");
    }

    @Test
    void anUnlabeledBreakInASwitchLeavesTheSwitchNotTheLoop() {
        Decision sw = decision("SWITCH", null, false, List.of(), List.of(
                new Arm("1", "k == 1", List.of(fx("one"), new Exit("BREAK", null, null, 1))),
                new Arm("default", "other", List.of(fx("other")))), List.of());
        PathFlattener f = flatten(100, loop(null, List.of(sw, fx("tail"))));
        assertTrue(f.rows.stream().anyMatch(r -> r.effects().equals(List.of("one", "tail"))), "break leaves the switch, then the loop body goes on");
    }

    @Test
    void aLabeledBreakLeavesTheOuterLoop() {
        Decision inner = loop(null, List.of(iff("bad", List.of(new Exit("BREAK", "outer", null, 1)), List.of()), fx("inner-work")));
        PathFlattener f = flatten(100, loop("outer", List.of(inner, fx("outer-work"))), fx("after"));
        assertTrue(f.rows.stream().anyMatch(r -> r.effects().equals(List.of("after"))), "break outer skips inner-work and outer-work");
        assertTrue(f.rows.stream().anyMatch(r -> r.effects().equals(List.of("inner-work", "outer-work", "after"))));
    }

    // ---------------------------------------------------------------- finally

    @Test
    void finallyRunsOnNormalCompletionAndOnReturn() {
        Decision tryDecision = decision("TRY", null, false, List.of(), List.of(
                new Arm("normal", "no exception", List.of(fx("body"))),
                new Arm("catch E", "exception E", List.of(ret("0")))), List.of(fx("cleanup")));
        PathFlattener f = flatten(100, tryDecision, fx("after"));
        assertEquals(2, f.rows.size());
        assertEquals(List.of("body", "(finally) cleanup", "after"), effects(f, 0));
        assertEquals(List.of("(finally) cleanup"), effects(f, 1), "the catch returns, but cleanup ran first");
        assertEquals("RETURN", f.rows.get(1).exit());
    }

    @Test
    void finallyAlsoRunsWhenABreakLeavesTheTry() {
        Decision tryDecision = decision("TRY", null, false, List.of(), List.of(
                new Arm("normal", "no exception", List.of(new Exit("BREAK", null, null, 1)))), List.of(fx("cleanup")));
        PathFlattener f = flatten(100, loop(null, List.of(tryDecision)), fx("after"));
        assertTrue(f.rows.stream().anyMatch(r -> r.effects().equals(List.of("(finally) cleanup", "after"))));
    }

    // ---------------------------------------------------------------- loops, dead code, expression-level

    @Test
    void aDoWhileBodyRunsBeforeItsDecision() {
        Decision doWhile = decision("LOOP", null, false, List.of(fx("body")), List.of(
                new Arm("enter", "repeats", List.of()), new Arm("exit", "stops", List.of())), List.of());
        PathFlattener f = flatten(100, doWhile, fx("after"));
        assertEquals(2, f.rows.size());
        assertTrue(f.rows.stream().allMatch(r -> r.effects().equals(List.of("body", "after"))), "both ways out ran the body first");
    }

    @Test
    void anEndlessLoopHasOnlyTheEnterArm() {
        Decision endless = decision("LOOP", null, false, List.of(), List.of(
                new Arm("enter", "runs", List.of(iff("done", List.of(new Exit("BREAK", null, null, 1)), List.of())))), List.of());
        PathFlattener f = flatten(100, endless, fx("after"));
        assertEquals(2, f.rows.size());
        assertTrue(f.rows.stream().noneMatch(r -> r.when().contains("skipped")));
    }

    @Test
    void aDecisionAfterAnExitIsNeverReached() {
        Decision dead = iff("never", List.of(fx("x")), List.of());
        PathFlattener f = flatten(100, ret("1"), dead);
        assertEquals(1, f.rows.size());
        assertFalse(f.reached.contains(dead.id() + "|then"), "dead code is not reached by any path");
    }

    @Test
    void expressionLevelDecisionsAreReachedButDoNotSplit() {
        Decision ternary = decision("TERNARY", null, true, List.of(), List.of(new Arm("then", "c", List.of()), new Arm("else", "not (c)", List.of())), List.of());
        PathFlattener f = flatten(100, ternary, fx("e"));
        assertEquals(1, f.rows.size());
        assertTrue(f.reached.contains(ternary.id() + "|then") && f.reached.contains(ternary.id() + "|else"));
    }

    // ---------------------------------------------------------------- tree helpers

    @Test
    void canFallFollowsExits() {
        assertTrue(PathTree.canFall(List.of()));
        assertFalse(PathTree.canFall(List.of(fx("a"), ret("1"))));
        assertFalse(PathTree.canFall(List.of(iff("c", List.of(ret("1")), List.of(ret("2"))))), "both arms exit");
        assertTrue(PathTree.canFall(List.of(iff("c", List.of(ret("1")), List.of()))), "the implicit else falls through");
        assertTrue(PathTree.canFall(List.of(loop(null, List.of(ret("1"))))), "a loop may run zero times");
    }

    @Test
    void aDecisionSharedBetweenArmsIsCountedOnce() {
        Decision inner = iff("shared", List.of(fx("s")), List.of());
        Decision outer = decision("SWITCH", null, false, List.of(), List.of(new Arm("1", "k==1", List.of(inner)), new Arm("2", "k==2", List.of(inner))), List.of());
        assertEquals(2, PathTree.decisions(List.of(outer)).size(), "a fall-through body appears in two arms but is one decision");
    }
}
