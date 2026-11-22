package com.chiru.engine.paths;

import com.chiru.engine.paths.PathTree.*;
import java.util.*;

/**
 * Turns a {@link PathTree} into the list of paths through it. Continuation-passing, depth first, arms in source order:
 * a decision splits the state, an arm that completes normally continues with whatever follows the decision, and an
 * exit ends the path (return, throw) or jumps to the end of the construct it targets (break, continue).
 *
 * <p>Loops are abstracted to "zero iterations" or "at least one pass through the body, then on". A {@code continue}
 * ends the pass and a {@code break} leaves the loop, so both continue after it. A {@code finally} block runs on every way
 * out of its try (normal completion, return, throw, break, continue); only its effects are listed.
 *
 * <p>The number of paths can grow like 2^n for n sequential decisions, so it is capped: when the cap is reached
 * flattening stops and {@link #capped} is set. The tree itself is never capped.
 */
final class PathFlattener {

    /**
     * One path: what selects it ({@code when}), what it does in order ({@code effects}), how it ends.
     * {@code refs} and {@code lines} are parallel to {@code effects} (a ref is null when the effect has no data-flow expression,
     * a line 0 when it has no position); {@code exitRef} is the same for the exit. {@code branches} lists
     * {@code decisionId|outcome} for every outcome the path takes, so a rule can find the decision it is conditioned by.
     */
    record Row(List<String> when, List<String> effects, String exit, String value, int line, List<String> outcomes,
               List<String> refs, List<Integer> lines, String exitRef, List<String> branches) {}

    /** An effect on a path, with where it is and which data-flow expression it is. */
    private record Eff(String text, int line, String ref) {}

    private record Cons<T>(T head, Cons<T> tail) {
        static <T> List<T> list(Cons<T> c) {
            ArrayList<T> out = new ArrayList<>();
            for (Cons<T> p = c; p != null; p = p.tail) {
                out.add(p.head);
            }
            Collections.reverse(out);
            return out;
        }
    }

    private record State(Cons<String> when, Cons<Eff> then, Cons<String> outcomes, Cons<String> branches) {
        static final State EMPTY = new State(null, null, null, null);

        State when(String atom) {
            return atom == null || atom.isBlank() ? this : new State(new Cons<>(atom, when), then, outcomes, branches);
        }

        State then(Eff effect) {
            return new State(when, new Cons<>(effect, then), outcomes, branches);
        }

        State outcome(String outcome, String branch) {
            return new State(when, then, new Cons<>(outcome, outcomes), new Cons<>(branch, branches));
        }
    }

    private interface K {
        void run(State s);
    }

    /** A loop or switch a break or continue can target. {@code base} is the context size before it was pushed. */
    private record Frame(String label, String kind, K next, int base) {}

    private record Finally(List<Item> block) {}

    private static final class Stop extends RuntimeException {
        Stop() {
            super(null, null, false, false);
        }
    }

    final List<Row> rows = new ArrayList<>();
    /** "decision id|outcome" of every outcome some flow reached. An outcome in dead code is never reached. */
    final Set<String> reached = new LinkedHashSet<>();
    boolean capped;

    private final int cap;
    private final Deque<Object> context = new ArrayDeque<>();

    PathFlattener(int cap) {
        this.cap = cap;
    }

    List<Row> run(List<Item> body) {
        try {
            items(body, 0, State.EMPTY, s -> emit(s, "FALL", null, 0, null));
        } catch (Stop e) {
            capped = true;
        }
        return rows;
    }

    // ---------------------------------------------------------------- walking

    private void items(List<Item> list, int from, State state, K k) {
        State s = state;
        for (int i = from; i < list.size(); i++) {
            Item item = list.get(i);
            if (item instanceof Effect e) {
                s = s.then(new Eff(e.text(), e.line(), e.ref()));
            } else if (item instanceof Exit x) {
                exit(x, s);
                return;
            } else {
                Decision d = (Decision) item;
                int next = i + 1;
                decision(d, s, st -> items(list, next, st, k));
                return;
            }
        }
        k.run(s);
    }

    private void decision(Decision d, State s, K next) {
        if (d.exprLevel()) {
            markReached(d);                       // outcomes and anything nested in a case body, without splitting paths
            next.run(s);
            return;
        }
        int base = context.size();
        boolean isTry = d.kind().equals("TRY");
        if (d.kind().equals("LOOP") || d.kind().equals("SWITCH") || d.kind().equals("SWITCH_EXPR")) {      // a yield leaves its switch expression like a break
            context.push(new Frame(d.label(), d.kind(), next, base));
        }
        if (isTry) {
            context.push(new Finally(d.finallyBlock()));
        }
        K leave = st -> {                                  // an arm completed normally: leave the construct, go on
            List<Object> cut = cutTo(base);
            try {
                next.run(isTry ? thenAll(st, effects(d.finallyBlock())) : st);
            } finally {
                restore(cut);
            }
        };
        try {
            if (d.prologue().isEmpty()) {
                arms(d, s, leave);
            } else {
                items(d.prologue(), 0, s, st -> arms(d, st, leave));     // a do-while body runs before its decision
            }
        } finally {
            cutTo(base);
        }
    }

    private void markReached(Decision d) {
        for (Arm a : d.arms()) {
            reached.add(d.id() + "|" + a.outcome());
            for (Item i : a.body()) {
                if (i instanceof Decision nested) {
                    markReached(nested);
                }
            }
        }
    }

    private void arms(Decision d, State s, K leave) {
        for (Arm a : d.arms()) {
            reached.add(d.id() + "|" + a.outcome());
            State armState = s.when(a.atom()).outcome(d.kind() + "@" + d.line() + ":" + a.outcome(), d.id() + "|" + a.outcome());
            items(a.body(), 0, armState, leave);
        }
    }

    private void exit(Exit x, State s) {
        if (x.kind().equals("RETURN") || x.kind().equals("THROW")) {
            State out = s;
            for (Object c : context) {                              // innermost first
                if (c instanceof Finally f) {
                    out = thenAll(out, effects(f.block()));
                }
            }
            emit(out, x.kind(), x.value(), x.line(), x.ref());
            return;
        }
        boolean isBreak = x.kind().equals("BREAK");
        State out = s;
        Frame target = null;
        for (Object c : context) {
            if (c instanceof Finally f) {
                out = thenAll(out, effects(f.block()));
            } else if (c instanceof Frame f) {
                boolean matches = x.label() != null ? x.label().equals(f.label())
                        : (isBreak || f.kind().equals("LOOP"));         // an unlabeled continue targets a loop, never a switch
                if (matches) {
                    target = f;
                    break;
                }
            }
        }
        if (target == null) {
            emit(out, x.kind(), x.label(), x.line(), null);                   // a jump with no target: keep it visible
            return;
        }
        List<Object> cut = cutTo(target.base());
        try {
            target.next().run(out);
        } finally {
            restore(cut);
        }
    }

    // ---------------------------------------------------------------- helpers

    private void emit(State s, String exit, String value, int line, String exitRef) {
        if (rows.size() >= cap) {
            throw new Stop();
        }
        List<Eff> effects = Cons.list(s.then);
        rows.add(new Row(Cons.list(s.when), effects.stream().map(Eff::text).toList(), exit, value, line, Cons.list(s.outcomes),
                effects.stream().map(Eff::ref).collect(java.util.stream.Collectors.toList()), effects.stream().map(Eff::line).toList(),
                exitRef, Cons.list(s.branches)));
    }

    private List<Object> cutTo(int size) {
        List<Object> cut = new ArrayList<>();
        while (context.size() > size) {
            cut.add(context.pop());
        }
        return cut;
    }

    private void restore(List<Object> cut) {
        for (int i = cut.size() - 1; i >= 0; i--) {
            context.push(cut.get(i));
        }
    }

    private static List<Eff> effects(List<Item> block) {
        List<Eff> out = new ArrayList<>();
        for (Item i : block) {
            if (i instanceof Effect e) {
                out.add(new Eff("(finally) " + e.text(), 0, null));          // a finally effect is not a data-flow expression of the path
            }
        }
        return out;
    }

    private static State thenAll(State s, List<Eff> effects) {
        State out = s;
        for (Eff e : effects) {
            out = out.then(e);
        }
        return out;
    }
}
