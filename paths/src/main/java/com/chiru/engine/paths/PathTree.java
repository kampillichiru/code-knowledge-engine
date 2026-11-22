package com.chiru.engine.paths;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The structure of one method body as a tree of effects, exits and decisions, in source order. It is faithful to the
 * source (nothing is merged or dropped) so every decision and every outcome is present exactly once; the flattening into
 * paths happens afterwards and may be capped without losing any decision.
 */
final class PathTree {

    private PathTree() {}

    sealed interface Item permits Effect, Exit, Decision {}

    /**
     * Something the method does: an assignment, a call, a declaration with a value, an increment. {@code ref} is the data-flow
     * expression this effect corresponds to ({@code line:col-end:kind}, the suffix of its EXPRESSION id), or null.
     */
    record Effect(String text, int line, String kind, String ref) implements Item {
        Effect(String text, int line, String kind) {
            this(text, line, kind, null);
        }
    }

    /** Leaves the current flow: return, throw, break or continue (a label, when the source gives one). */
    record Exit(String kind, String label, String value, int line, String ref) implements Item {
        Exit(String kind, String label, String value, int line) {
            this(kind, label, value, line, null);
        }
    }

    /** One outcome of a decision. {@code atom} is the readable condition of that outcome ("x < 0", "not (x < 0)"). */
    record Arm(String outcome, String atom, List<Item> body) {}

    /**
     * A decision point. {@code exprLevel} decisions (a ternary or a short-circuit operator inside an expression) are
     * recorded with their outcomes but do not split the paths of the method. {@code prologue} is the body of a do-while,
     * which runs before the loop decision. {@code finallyBlock} belongs to a try.
     */
    record Decision(String id, String kind, int line, int col, int end, String condition, String label, boolean exprLevel,
                    List<Item> prologue, List<Arm> arms, List<Item> finallyBlock) implements Item {}

    /** Every decision in the tree, nested ones included, each once (a finally block copied into several arms counts once). */
    static Map<String, Decision> decisions(List<Item> items) {
        Map<String, Decision> all = new LinkedHashMap<>();
        collect(items, all);
        return all;
    }

    private static void collect(List<Item> items, Map<String, Decision> all) {
        for (Item item : items) {
            if (item instanceof Decision d) {
                if (all.putIfAbsent(d.id(), d) == null) {
                    collect(d.prologue(), all);
                    d.arms().forEach(a -> collect(a.body(), all));
                    collect(d.finallyBlock(), all);
                }
            }
        }
    }

    /** True if control can reach the end of these items (an unconditional return, throw, break or continue stops it). */
    static boolean canFall(List<Item> items) {
        for (Item item : items) {
            if (item instanceof Exit) {
                return false;
            }
            if (item instanceof Decision d && !d.exprLevel()) {
                boolean splits = d.kind().equals("IF") || d.kind().equals("TRY") || d.kind().equals("ASSERT");
                if (splits && d.arms().stream().noneMatch(a -> canFall(a.body()))) {
                    return false;
                }
            }
        }
        return true;
    }
}
