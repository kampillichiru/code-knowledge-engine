package com.chiru.engine.paths;

import com.chiru.engine.paths.PathTree.*;
import java.util.*;

/**
 * Which data-flow expressions of a unit may run before which others. Lineage uses it so a variable read takes only the
 * definitions that can reach it, instead of every definition anywhere in the method ("flow-insensitive").
 *
 * <p>A forward walk over the tree, sound but not exact: the branches of a decision are joined (an expression may have run if it
 * ran on any branch), a branch that ends in a return or throw does not continue, a {@code break}, {@code continue} or {@code yield}
 * hands what has run to the end of the construct it leaves, and a loop body is walked twice so a definition late in the body
 * reaches a use early in the next pass. It only ever adds possibilities, never removes one that can happen.
 */
final class PathOrder {

    private final Map<String, Set<String>> preceded = new LinkedHashMap<>();
    private final Deque<Set<String>> jumps = new ArrayDeque<>();

    private PathOrder() {}

    /** Expression ref to the refs that may run before it. A ref that was never reached (dead code) is absent. */
    static Map<String, Set<String>> compute(List<Item> body) {
        PathOrder order = new PathOrder();
        order.walk(body, Set.of());
        return order.preceded;
    }

    private Set<String> walk(List<Item> items, Set<String> before) {
        Set<String> seen = new LinkedHashSet<>(before);
        for (Item item : items) {
            if (item instanceof Effect e) {
                record(e.ref(), seen);
            } else if (item instanceof Exit x) {
                record(x.ref(), seen);
                if (x.kind().equals("BREAK") || x.kind().equals("CONTINUE")) {
                    Set<String> snapshot = new LinkedHashSet<>(seen);
                    jumps.forEach(j -> j.addAll(snapshot));       // what has run flows to the end of the construct it leaves
                }
                return seen;                                      // nothing after an exit in this list can follow it
            } else {
                seen = decision((Decision) item, seen);
            }
        }
        return seen;
    }

    private Set<String> decision(Decision d, Set<String> before) {
        boolean frame = d.kind().equals("LOOP") || d.kind().equals("SWITCH") || d.kind().equals("SWITCH_EXPR");
        Set<String> start = d.prologue().isEmpty() ? before : walk(d.prologue(), before);     // a do-while body runs first
        Set<String> collected = new LinkedHashSet<>();
        if (frame) {
            jumps.push(collected);
        }
        Set<String> out = new LinkedHashSet<>();
        Set<String> normalOut = null;
        for (Arm arm : d.arms()) {
            Set<String> armStart = start;
            if (d.kind().equals("TRY") && normalOut != null && !arm.outcome().equals("normal")) {
                armStart = union(start, normalOut);               // a catch can run after any part of the try body
            }
            Set<String> armOut = walk(arm.body(), armStart);
            if (d.kind().equals("TRY") && arm.outcome().equals("normal")) {
                normalOut = armOut;
            }
            if (d.kind().equals("LOOP") && arm.outcome().equals("enter") && d.prologue().isEmpty()) {
                armOut = walk(arm.body(), union(start, armOut));  // second pass: loop-carried definitions
            }
            if (PathTree.canFall(arm.body())) {
                out.addAll(armOut);
            }
        }
        if (frame) {
            jumps.pop();
            out.addAll(collected);
        }
        if (d.kind().equals("TRY") && !d.finallyBlock().isEmpty()) {
            out = walk(d.finallyBlock(), union(out, start));
        }
        if (out.isEmpty() && !d.arms().isEmpty()) {
            return start;                                         // every arm exits: flow after the decision is dead; keep going harmlessly
        }
        return out;
    }

    private void record(String ref, Set<String> seen) {
        if (ref != null) {
            preceded.computeIfAbsent(ref, k -> new LinkedHashSet<>()).addAll(seen);
            seen.add(ref);
        }
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> out = new LinkedHashSet<>(a);
        out.addAll(b);
        return out;
    }
}
