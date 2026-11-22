package com.chiru.engine.paths;

import com.chiru.engine.paths.PathTree.*;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;
import spoon.reflect.code.*;
import spoon.reflect.cu.SourcePosition;
import spoon.reflect.declaration.CtAnonymousExecutable;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.declaration.CtEnumValue;
import spoon.reflect.declaration.CtField;
import spoon.reflect.declaration.CtType;
import spoon.reflect.reference.CtTypeReference;
import spoon.reflect.visitor.filter.AbstractFilter;

/**
 * Builds the {@link PathTree} of one unit of code from the Spoon AST: a method or constructor body, a lambda, a field
 * initialiser or an initialiser block.
 *
 * <p>Modelled: {@code if}/{@code else} (an implicit else is an arm), old and arrow {@code switch} statements with
 * fall-through and pattern labels with {@code when} guards, switch expressions with {@code yield},
 * {@code for}/{@code foreach}/{@code while}/{@code do} (as zero or at-least-one passes), {@code try}/{@code catch}/{@code finally},
 * {@code assert}, {@code synchronized}, labeled {@code break}/{@code continue}, {@code return}/{@code throw}, lambdas (a boolean
 * expression body is a predicate decision), and the ternary and short-circuit operators inside expressions.
 *
 * <p>A switch expression used as a value ({@code return}, a declaration, an assignment) splits the paths one way per case and a
 * {@code yield} ends its case. In any other position (a call argument, an operand) it is recorded as an expression-level decision
 * with its outcomes and does not split paths. Not modelled yet (reported, never silent): local classes and other rare statements.
 */
final class PathBuilder {

    /** Package prefixes in front of a class name make text unreadable: {@code com.x.Foo.BAR} becomes {@code Foo.BAR}. */
    private static final Pattern QUALIFIER = Pattern.compile("\\b(?:[a-z_][a-z0-9_]*\\.)+(?=[A-Z])");
    private static final int TEXT_LIMIT = 160;

    /** Kinds of statement that were seen but not modelled, with how many. */
    final Map<String, Integer> opaque = new TreeMap<>();
    private int generated;
    /** What a {@code yield} means in the switch expression being built: set while its cases are built. */
    private final Deque<Function<String, List<Item>>> yieldHandlers = new ArrayDeque<>();
    /** Switch expressions already turned into a splitting decision by their statement, so the expression scan skips them. */
    private final Set<CtElement> handled = Collections.newSetFromMap(new IdentityHashMap<>());

    // ---------------------------------------------------------------- units

    List<Item> body(CtBlock<?> block) {
        return block == null ? List.of() : statements(block.getStatements());
    }

    /** A lambda: its block body, or its expression body (a boolean-shaped one is a predicate decision). */
    List<Item> lambdaBody(CtLambda<?> lambda) {
        CtExpression<?> expression = lambda.getExpression();
        if (expression == null) {
            return lambda.getBody() == null ? List.of() : statements(lambda.getBody().getStatements());
        }
        List<Item> out = new ArrayList<>(exprDecisions(expression));
        if (booleanShaped(expression)) {
            String cond = text(expression);
            out.add(decision("LAMBDA_PREDICATE", expression, cond, null, true, List.of(),
                    List.of(new Arm("true", cond, List.of()), new Arm("false", "not (" + cond + ")", List.of())), List.of()));
        }
        out.add(new Exit("RETURN", null, text(expression), line(expression)));
        return out;
    }

    /** A field initialiser: {@code field = value}, split per case when the value is a switch expression. */
    List<Item> fieldInit(CtField<?> field) {
        CtExpression<?> init = field.getDefaultExpression();
        return init == null ? List.of() : valueInto(init, field.getSimpleName(), init, "FIELD_INIT", field.getSimpleName() + " = " + text(init));
    }

    /** A static or instance initialiser block. */
    List<Item> initBlock(CtAnonymousExecutable block) {
        return body(block.getBody());
    }

    // ---------------------------------------------------------------- statements

    private List<Item> statements(List<? extends CtStatement> list) {
        List<Item> out = new ArrayList<>();
        for (CtStatement s : list) {
            if (!s.isImplicit() || s instanceof CtYieldStatement) {      // an arrow case's value is an implicit yield
                out.addAll(statement(s));
            }
        }
        return out;
    }

    private List<Item> block(CtStatement s) {
        if (s == null) {
            return List.of();
        }
        return s instanceof CtBlock<?> b ? statements(b.getStatements()) : statement(s);
    }

    private List<Item> statement(CtStatement s) {
        if (s instanceof CtBlock<?> b) {
            return statements(b.getStatements());
        }
        if (s instanceof CtIf i) {
            return ifStatement(i);
        }
        if (s instanceof CtFor f) {
            return forLoop(f);
        }
        if (s instanceof CtForEach f) {
            return forEach(f);
        }
        if (s instanceof CtWhile w) {
            return loop(w, w.getLoopingExpression(), List.of(), block(w.getBody()));
        }
        if (s instanceof CtDo d) {
            return loop(d, d.getLoopingExpression(), block(d.getBody()), List.of());
        }
        if (s instanceof CtSwitch<?> sw) {
            return switchStatement(sw);
        }
        if (s instanceof CtTry t) {
            return tryStatement(t);
        }
        if (s instanceof CtYieldStatement y) {
            List<Item> out = new ArrayList<>(exprDecisions(y.getExpression()));
            Function<String, List<Item>> handler = yieldHandlers.peek();
            String value = text(y.getExpression());
            out.addAll(handler != null ? handler.apply(value) : List.of(new Effect("yield " + value, line(y), "YIELD")));
            return out;
        }
        if (s instanceof CtReturn<?> r) {
            CtExpression<?> value = r.getReturnedExpression();
            List<Item> out = new ArrayList<>();
            if (value instanceof CtSwitchExpression<?, ?> se) {
                handled.add(se);
                out.addAll(exprDecisions(value));
                out.add(switchExpression(se, false, v -> List.of(new Exit("RETURN", null, v, line(r), ref(r, "return"))), true));
                return out;
            }
            out.addAll(value == null ? List.of() : exprDecisions(value));
            out.add(new Exit("RETURN", null, value == null ? null : text(value), line(r), ref(r, "return")));
            return out;
        }
        if (s instanceof CtThrow t) {
            List<Item> out = new ArrayList<>(exprDecisions(t.getThrownExpression()));
            out.add(new Exit("THROW", null, text(t.getThrownExpression()), line(t)));
            return out;
        }
        if (s instanceof CtBreak b) {
            return List.of(new Exit("BREAK", b.getTargetLabel(), null, line(b)));
        }
        if (s instanceof CtContinue c) {
            return List.of(new Exit("CONTINUE", c.getTargetLabel(), null, line(c)));
        }
        if (s instanceof CtAssert<?> a) {
            return assertStatement(a);
        }
        if (s instanceof CtSynchronized sy) {
            List<Item> out = new ArrayList<>(exprDecisions(sy.getExpression()));
            out.addAll(statements(sy.getBlock().getStatements()));
            return out;
        }
        if (s instanceof CtLocalVariable<?> v) {
            if (v.getDefaultExpression() == null) {
                return List.of();
            }
            return valueInto(v, v.getSimpleName(), v.getDefaultExpression(), "DECL", v.getSimpleName() + " = " + text(v.getDefaultExpression()));
        }
        if (s instanceof CtOperatorAssignment<?, ?> a) {                       // +=, -=, ...: never split
            List<Item> out = new ArrayList<>(exprDecisions(a));
            out.add(new Effect(text(a), line(a), "ASSIGN", ref(a, "compound")));
            return out;
        }
        if (s instanceof CtAssignment<?, ?> a) {
            return valueInto(a, text(a.getAssigned()), a.getAssignment(), "ASSIGN", text(a));
        }
        if (s instanceof CtUnaryOperator<?> u) {
            List<Item> out = new ArrayList<>(exprDecisions(u));
            out.add(new Effect(text(u), line(u), "INCDEC"));
            return out;
        }
        if (s instanceof CtInvocation<?> || s instanceof CtConstructorCall<?>) {
            List<Item> out = new ArrayList<>(exprDecisions(s));
            out.add(new Effect(text(s), line(s), s instanceof CtInvocation<?> ? "CALL" : "NEW"));
            return out;
        }
        if (s instanceof CtComment) {
            return List.of();
        }
        opaque.merge(s.getClass().getSimpleName().replaceFirst("Impl$", "").replaceFirst("^Ct", ""), 1, Integer::sum);
        return List.of(new Effect("(not modelled: " + s.getClass().getSimpleName().replaceFirst("Impl$", "") + ")", line(s), "OPAQUE"));
    }

    /**
     * {@code target = value}. When the value is a switch expression the statement splits one way per case and the case's value
     * is what is assigned; otherwise it is one effect.
     */
    private List<Item> valueInto(CtElement whole, String target, CtExpression<?> value, String kind, String plainText) {
        List<Item> out = new ArrayList<>();
        if (value instanceof CtSwitchExpression<?, ?> se) {
            handled.add(se);
            out.addAll(exprDecisions(whole));
            out.add(switchExpression(se, false, v -> List.of(new Effect(target + " = " + v, line(whole), kind, ref(whole, kind))), false));
        } else {
            out.addAll(exprDecisions(whole));
            out.add(new Effect(plainText, line(whole), kind, ref(whole, kind)));
        }
        return out;
    }

    private List<Item> ifStatement(CtIf i) {
        List<Item> out = new ArrayList<>(exprDecisions(i.getCondition()));
        String cond = text(i.getCondition());
        CtStatement then = i.getThenStatement();
        CtStatement els = i.getElseStatement();
        out.add(decision("IF", i, cond, null, false, List.of(),
                List.of(new Arm("then", cond, block(then)), new Arm("else", "not (" + cond + ")", block(els))), List.of()));
        return out;
    }

    private List<Item> forLoop(CtFor f) {
        List<Item> out = new ArrayList<>(statements(f.getForInit()));
        List<Item> bodyAndUpdate = new ArrayList<>(block(f.getBody()));
        bodyAndUpdate.addAll(statements(f.getForUpdate()));
        out.addAll(loop(f, f.getExpression(), List.of(), bodyAndUpdate));
        return out;
    }

    private List<Item> forEach(CtForEach f) {
        List<Item> body = new ArrayList<>();
        body.add(new Effect(f.getVariable().getSimpleName() + " = next element of " + text(f.getExpression()), line(f), "ASSIGN"));
        body.addAll(block(f.getBody()));
        List<Item> out = new ArrayList<>(exprDecisions(f.getExpression()));
        out.add(decision("LOOP", f, text(f.getExpression()), f.getLabel(), false, List.of(),
                List.of(new Arm("enter", "loop over " + text(f.getExpression()) + " runs", body),
                        new Arm("exit", "loop over " + text(f.getExpression()) + " does not run", List.of())), List.of()));
        return out;
    }

    /** while, for and do: zero iterations or at least one; a condition that is always true leaves only "enter". */
    private List<Item> loop(CtStatement loop, CtExpression<?> condition, List<Item> prologue, List<Item> body) {
        List<Item> out = new ArrayList<>(condition == null ? List.of() : exprDecisions(condition));
        boolean endless = condition == null || (condition instanceof CtLiteral<?> l && Boolean.TRUE.equals(l.getValue()));
        String cond = condition == null ? "(no condition)" : text(condition);
        List<Arm> arms = new ArrayList<>();
        boolean doWhile = loop instanceof CtDo;
        arms.add(new Arm("enter", doWhile ? "loop repeats while " + cond : "loop while " + cond + " runs", doWhile ? List.of() : body));
        if (!endless) {
            arms.add(new Arm("exit", doWhile ? "loop stops repeating" : "loop while " + cond + " does not run", List.of()));
        }
        out.add(decision("LOOP", loop, cond, loop.getLabel(), false, prologue, arms, List.of()));
        return out;
    }

    private List<Item> switchStatement(CtSwitch<?> sw) {
        List<Item> out = new ArrayList<>(exprDecisions(sw.getSelector()));
        String selector = text(sw.getSelector());
        boolean patterns = sw.getCases().stream().anyMatch(c -> c.getCaseExpressions().stream().anyMatch(e -> e instanceof CtCasePattern));
        List<Arm> arms = caseArms(selector, sw.getCases(), !patterns);        // a switch over patterns is exhaustive
        out.add(decision("SWITCH", sw, selector, sw.getLabel(), false, List.of(), arms, List.of()));
        return out;
    }

    /**
     * A switch used as a value: one arm per case label. {@code onValue} says what a yielded value means (assign it, return it);
     * unless that already leaves the method, a yield also ends its case.
     */
    private Decision switchExpression(CtSwitchExpression<?, ?> se, boolean exprLevel, Function<String, List<Item>> onValue, boolean leavesMethod) {
        String label = "yield@" + start(se);
        Function<String, List<Item>> handler = v -> {
            List<Item> out = new ArrayList<>(onValue.apply(v));
            if (!leavesMethod) {
                out.add(new Exit("BREAK", label, null, line(se)));
            }
            return out;
        };
        yieldHandlers.push(handler);
        List<Arm> arms;
        try {
            arms = caseArms(text(se.getSelector()), se.getCases(), false);       // exhaustive: no implicit default
        } finally {
            yieldHandlers.pop();
        }
        return decision("SWITCH_EXPR", se, text(se.getSelector()), label, exprLevel, List.of(), arms, List.of());
    }

    /**
     * One arm per case label, shared by switch statements and expressions. A case that does not end in a break, return, throw or
     * yield continues into the next case's statements (old-style fall-through); a {@code when} guard is part of its case's condition
     * and a decision of its own.
     */
    private List<Arm> caseArms(String selector, List<? extends CtCase<?>> cases, boolean implicitDefault) {
        int n = cases.size();
        List<List<Item>> own = new ArrayList<>();
        for (CtCase<?> c : cases) {
            List<Item> body = new ArrayList<>();
            if (c.getGuard() != null) {
                body.addAll(exprDecisions(c.getGuard()));
                String guard = text(c.getGuard());
                body.add(decision("PATTERN_GUARD", c.getGuard(), guard, null, true, List.of(),
                        List.of(new Arm("guard true", guard, List.of()), new Arm("guard false", "not (" + guard + ")", List.of())), List.of()));
            }
            body.addAll(statements(c.getStatements()));
            own.add(body);
        }
        List<List<Item>> full = new ArrayList<>(Collections.nCopies(n, (List<Item>) null));
        for (int i = n - 1; i >= 0; i--) {
            List<Item> body = new ArrayList<>(own.get(i));
            boolean arrow = cases.get(i).getCaseKind() == CaseKind.ARROW;
            if (!arrow && i + 1 < n && PathTree.canFall(own.get(i))) {
                body.addAll(full.get(i + 1));
            }
            full.set(i, body);
        }
        List<Arm> arms = new ArrayList<>();
        boolean hasDefault = false;
        for (int i = 0; i < n; i++) {
            CtCase<?> c = cases.get(i);
            String when = c.getGuard() == null ? "" : " when " + text(c.getGuard());
            for (CtExpression<?> label : c.getCaseExpressions()) {
                String outcome = caseLabel(label);
                String shown = label instanceof CtCasePattern ? outcome.substring("pattern ".length()) : outcome;
                arms.add(new Arm(outcome, selector + " == " + shown + when, full.get(i)));
            }
            if (c.getCaseExpressions().isEmpty() || c.getIncludesDefault()) {
                hasDefault = true;
                arms.add(new Arm("default", selector + " matches no other case", full.get(i)));
            }
        }
        if (implicitDefault && !hasDefault) {
            arms.add(new Arm("default (implicit)", selector + " matches no case", List.of()));
        }
        return arms;
    }

    /** A case label as the syntax census writes it: an enum constant by its simple name, a pattern as "pattern T x". */
    private static String caseLabel(CtExpression<?> label) {
        String t;
        if (label instanceof CtFieldRead<?> read && read.getVariable() != null && read.getVariable().getDeclaration() instanceof CtEnumValue<?>) {
            t = read.getVariable().getSimpleName();
        } else {
            t = text(label);
        }
        return label instanceof CtCasePattern ? "pattern " + t : t;
    }

    private List<Item> tryStatement(CtTry t) {
        List<Item> out = new ArrayList<>();
        if (t instanceof CtTryWithResource twr) {
            for (CtResource<?> r : twr.getResources()) {
                if (r instanceof CtStatement rs) {
                    out.addAll(statement(rs));
                }
            }
        }
        List<Arm> arms = new ArrayList<>();
        arms.add(new Arm("normal", t.getCatchers().isEmpty() ? "" : "no exception",          // nothing to say without a catch
                statements(t.getBody().getStatements())));
        for (CtCatch c : t.getCatchers()) {
            String types = catchTypes(c);
            arms.add(new Arm("catch " + types, "exception " + types, statements(c.getBody().getStatements())));
        }
        List<Item> fin = t.getFinalizer() == null ? List.of() : statements(t.getFinalizer().getStatements());
        out.add(decision("TRY", t, "try", null, false, List.of(), arms, fin));
        return out;
    }

    private static String catchTypes(CtCatch c) {
        List<CtTypeReference<?>> multi = c.getParameter().getMultiTypes();
        List<String> names = new ArrayList<>();
        if (multi == null || multi.isEmpty()) {
            names.add(c.getParameter().getType() == null ? "?" : c.getParameter().getType().getSimpleName());
        } else {
            multi.forEach(r -> names.add(r.getSimpleName()));
        }
        return String.join(" | ", names);
    }

    private List<Item> assertStatement(CtAssert<?> a) {
        List<Item> out = new ArrayList<>(exprDecisions(a.getAssertExpression()));
        String cond = text(a.getAssertExpression());
        out.add(decision("ASSERT", a, cond, null, false, List.of(), List.of(new Arm("holds", "assertion holds: " + cond, List.of()),
                new Arm("fails", "assertion fails: " + cond, List.of(new Exit("THROW", null, "AssertionError", line(a))))), List.of()));
        return out;
    }

    // ---------------------------------------------------------------- decisions inside expressions

    /**
     * Ternaries, short-circuit operators and switch expressions inside an expression. They are decisions with outcomes (the census
     * counts them) but they do not split the paths of the unit, so they are marked expression-level. Not collected here:
     * anything inside an anonymous class or a lambda (those are units of their own), anything inside the cases of a switch
     * expression (built with the cases), and a switch expression that its statement already splits.
     */
    private List<Item> exprDecisions(CtElement root) {
        if (root == null) {
            return List.of();
        }
        CtType<?> owner = root.getParent(CtType.class);
        CtLambda<?> lambda = root.getParent(CtLambda.class);
        List<CtElement> found = root.getElements(new AbstractFilter<CtElement>(CtElement.class) {
            @Override
            public boolean matches(CtElement e) {
                boolean candidate = e instanceof CtConditional<?> || e instanceof CtSwitchExpression<?, ?>
                        || (e instanceof CtBinaryOperator<?> b && (b.getKind() == BinaryOperatorKind.AND || b.getKind() == BinaryOperatorKind.OR));
                return candidate && !e.isImplicit() && !handled.contains(e)
                        && e.getParent(CtType.class) == owner && e.getParent(CtLambda.class) == lambda && !insideCase(e, root);
            }
        });
        found.sort(Comparator.comparingInt((CtElement e) -> start(e)).thenComparing(e -> -end(e)));
        List<Item> out = new ArrayList<>();
        for (CtElement e : found) {
            if (e instanceof CtConditional<?> c) {
                out.add(decision("TERNARY", c, text(c.getCondition()), null, true, List.of(), List.of(
                        new Arm("then", text(c.getCondition()), List.of()), new Arm("else", "not (" + text(c.getCondition()) + ")", List.of())), List.of()));
            } else if (e instanceof CtSwitchExpression<?, ?> se) {
                out.add(switchExpression(se, true, v -> List.of(new Effect("yields " + v, line(se), "YIELD")), false));
            } else if (e instanceof CtBinaryOperator<?> b) {
                String op = b.getKind() == BinaryOperatorKind.AND ? "&&" : "||";
                out.add(decision("SHORT_CIRCUIT", b, op + " " + text(b.getRightHandOperand()), null, true, List.of(), List.of(
                        new Arm("evaluate right", "left operand does not decide", List.of()),
                        new Arm("short-circuit", "left operand decides", List.of())), List.of()));
            }
        }
        return out;
    }

    /** True when {@code e} sits inside a case of a switch that is itself inside {@code root} (that case builds it). */
    private static boolean insideCase(CtElement e, CtElement root) {
        CtElement p = e.isParentInitialized() ? e.getParent() : null;
        while (p != null && p != root) {
            if (p instanceof CtCase<?>) {
                return true;
            }
            p = p.isParentInitialized() ? p.getParent() : null;
        }
        return false;
    }

    private static boolean booleanShaped(CtExpression<?> e) {
        if (e instanceof CtBinaryOperator<?> b) {
            return switch (b.getKind()) {
                case LT, GT, LE, GE, EQ, NE, AND, OR, INSTANCEOF -> true;
                default -> false;
            };
        }
        return (e instanceof CtUnaryOperator<?> u && u.getKind() == UnaryOperatorKind.NOT)
                || (e instanceof CtLiteral<?> l && l.getValue() instanceof Boolean);
    }

    private Decision decision(String kind, CtElement at, String condition, String label, boolean exprLevel, List<Item> prologue,
                              List<Arm> arms, List<Item> finallyBlock) {
        SourcePosition p = at.getPosition();
        boolean valid = p != null && p.isValidPosition();
        int line = valid ? p.getLine() : 0;
        int col = valid ? p.getColumn() : 0;
        int end = valid ? p.getSourceEnd() : 0;
        if (valid && at instanceof CtStatement st && st.getLabel() != null && p.getCompilationUnit() != null) {
            // Spoon starts a labeled statement at its label; the decision is at the keyword after "label:"
            String src = p.getCompilationUnit().getOriginalSourceCode();
            int colon = src == null ? -1 : src.indexOf(':', p.getSourceStart());
            if (colon >= 0) {
                int k = colon + 1;
                while (k < src.length() && Character.isWhitespace(src.charAt(k))) {
                    k++;
                }
                int lineStart = src.lastIndexOf('\n', k - 1) + 1;
                line = 1 + (int) src.substring(0, k).chars().filter(ch -> ch == '\n').count();
                col = k - lineStart + 1;
            }
        }
        String id = kind + "@" + (valid ? p.getSourceStart() + "-" + end : "generated" + (++generated));
        return new Decision(id, kind, line, col, end, condition, label, exprLevel, List.copyOf(prologue), List.copyOf(arms),
                List.copyOf(finallyBlock));
    }

    // ---------------------------------------------------------------- text and positions

    static String text(CtElement e) {
        String flat = e.toString().replaceAll("\\s+", " ").trim();
        String readable = QUALIFIER.matcher(flat).replaceAll("");
        return readable.length() <= TEXT_LIMIT ? readable : readable.substring(0, TEXT_LIMIT) + "...";
    }

    /**
     * The data-flow expression of a statement: {@code line:col-end:kind}, the part of its EXPRESSION id after the method key.
     * Only declarations with a value (DECL, kind init), assignments (ASSIGN, kind assign or compound) and returns have one.
     */
    private static String ref(CtElement statement, String kind) {
        String dataFlowKind = switch (kind) {
            case "DECL" -> "init";
            case "ASSIGN" -> "assign";
            default -> kind;
        };
        if (kind.equals("FIELD_INIT")) {
            return null;              // field initialisers have no data-flow expression
        }
        SourcePosition p = statement.getPosition();
        return p != null && p.isValidPosition() ? p.getLine() + ":" + p.getColumn() + "-" + p.getSourceEnd() + ":" + dataFlowKind : null;
    }

    private static int line(CtElement e) {
        SourcePosition p = e.getPosition();
        return p != null && p.isValidPosition() ? p.getLine() : 0;
    }

    private static int start(CtElement e) {
        SourcePosition p = e.getPosition();
        return p != null && p.isValidPosition() ? p.getSourceStart() : 0;
    }

    private static int end(CtElement e) {
        SourcePosition p = e.getPosition();
        return p != null && p.isValidPosition() ? p.getSourceEnd() : 0;
    }
}
