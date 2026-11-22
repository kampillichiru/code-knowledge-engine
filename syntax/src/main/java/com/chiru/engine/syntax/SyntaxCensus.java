package com.chiru.engine.syntax;

import com.sun.source.tree.*;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/**
 * A parse-only census of Java source: every statement, every decision point and the outcomes each decision can have.
 * It uses the JDK's own parser (the javac Compiler Tree API), so it needs no classpath, no dependencies and no
 * compilation, and it is independent of Spoon. That independence is the point: it is the syntax truth the rest of the
 * engine is measured against.
 *
 * <p>Adapted from {@code JavaFacts} (the author's earlier parse-only extractor), with these changes: switch expressions,
 * pattern labels and {@code when} guards; lambdas as their own units; short-circuit operators, assert and catch clauses as
 * decisions; and a line ledger that says which code lines no record accounts for.
 *
 * <h2>What counts as a decision, and its outcomes</h2>
 * <table>
 *   <caption>Decision kinds</caption>
 *   <tr><td>IF</td><td>then, else (an implicit else is an outcome)</td></tr>
 *   <tr><td>TERNARY</td><td>then, else</td></tr>
 *   <tr><td>LOOP</td><td>enter, exit (only enter for {@code while (true)} and {@code for (;;)})</td></tr>
 *   <tr><td>SWITCH</td><td>one per case label, plus an implicit default for a statement without a default or patterns</td></tr>
 *   <tr><td>SWITCH_EXPR</td><td>one per case label (an expression is exhaustive, so there is no implicit default)</td></tr>
 *   <tr><td>PATTERN_GUARD</td><td>guard true, guard false (a {@code when} clause)</td></tr>
 *   <tr><td>TRY</td><td>normal, plus one per catch clause</td></tr>
 *   <tr><td>SHORT_CIRCUIT</td><td>evaluate right, short-circuit (each {@code &&} and {@code ||})</td></tr>
 *   <tr><td>ASSERT</td><td>holds, fails</td></tr>
 *   <tr><td>LAMBDA_PREDICATE</td><td>true, false (an expression lambda whose body is boolean-shaped)</td></tr>
 * </table>
 */
public final class SyntaxCensus {

    public record Statement(String kind, int line, int headerEndLine) {}

    public record Decision(String kind, int line, int column, String condition, List<String> outcomes) {}

    /**
     * A body of code that runs on its own: a method, constructor, initialiser, field initialiser or lambda.
     *
     * @param owner binary-style class name ({@code com.x.Outer$Inner}, anonymous classes {@code Outer$1})
     */
    public record Unit(String file, String kind, String owner, String name, int paramCount, String params, int line,
                       int endLine, List<Statement> statements, List<Decision> decisions) {

        public Unit {
            statements = List.copyOf(statements);
            decisions = List.copyOf(decisions);
        }

        public String key() {
            return owner + "#" + name + "/" + paramCount + "@" + line;
        }

        public int outcomes() {
            return decisions.stream().mapToInt(d -> d.outcomes().size()).sum();
        }
    }

    /** What the census found in one file. {@code unaccountedLines} are code lines that no record accounts for. */
    public record FileResult(String file, int lines, int codeLines, List<Integer> unaccountedLines, List<Unit> units,
                             List<String> parseErrors) {}

    private SyntaxCensus() {}

    /** @param files absolute path to the name used for it in the model; iteration order is the output order */
    public static List<FileResult> analyse(Map<Path, String> files) throws IOException {
        JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
        if (jc == null) {
            throw new IllegalStateException("no Java compiler found: the syntax census needs a JDK, not only a JRE");
        }
        List<FileResult> results = new ArrayList<>();
        if (files.isEmpty()) {
            return results;
        }
        DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm = jc.getStandardFileManager(diags, null, StandardCharsets.UTF_8)) {
            JavacTask task = (JavacTask) jc.getTask(null, fm, diags, List.of("-proc:none", "-encoding", "UTF-8"), null,
                    fm.getJavaFileObjectsFromFiles(files.keySet().stream().map(Path::toFile).toList()));
            Trees trees = Trees.instance(task);
            Map<String, List<String>> errors = new HashMap<>();
            Map<String, FileResult> byFile = new HashMap<>();
            for (CompilationUnitTree cu : task.parse()) {
                Path path = Path.of(cu.getSourceFile().toUri()).toAbsolutePath().normalize();
                String display = display(files, path);
                byFile.put(display, new Walker(cu, trees.getSourcePositions(), display).run());
            }
            for (Diagnostic<? extends JavaFileObject> d : diags.getDiagnostics()) {
                if (d.getKind() == Diagnostic.Kind.ERROR && d.getSource() != null) {
                    String display = display(files, Path.of(d.getSource().toUri()).toAbsolutePath().normalize());
                    errors.computeIfAbsent(display, k -> new ArrayList<>())
                            .add("line " + d.getLineNumber() + ": " + d.getMessage(Locale.ENGLISH));
                }
            }
            for (String display : files.values()) {
                FileResult r = byFile.get(display);
                if (r != null) {
                    results.add(new FileResult(r.file(), r.lines(), r.codeLines(), r.unaccountedLines(), r.units(),
                            errors.getOrDefault(display, List.of())));
                }
            }
        }
        return results;
    }

    private static String display(Map<Path, String> files, Path p) {
        for (Map.Entry<Path, String> e : files.entrySet()) {
            if (e.getKey().toAbsolutePath().normalize().equals(p)) {
                return e.getValue();
            }
        }
        return p.toString().replace('\\', '/');
    }

    // ---------------------------------------------------------------- the walker

    private static final class Builder {
        final String kind, owner, name, params;
        final int paramCount, line, endLine;
        final List<Statement> statements = new ArrayList<>();
        final List<Decision> decisions = new ArrayList<>();

        Builder(String kind, String owner, String name, int paramCount, String params, int line, int endLine) {
            this.kind = kind;
            this.owner = owner;
            this.name = name;
            this.paramCount = paramCount;
            this.params = params;
            this.line = line;
            this.endLine = endLine;
        }
    }

    private static final class Walker extends TreeScanner<Void, Void> {
        final CompilationUnitTree cu;
        final SourcePositions sp;
        final LineMap lm;
        final String src;
        final String file;
        final List<Unit> units = new ArrayList<>();
        final BitSet covered = new BitSet();
        final Deque<String> classes = new ArrayDeque<>();
        final Map<String, Integer> anonymous = new HashMap<>();
        String pkg = "";
        Builder cur;

        Walker(CompilationUnitTree cu, SourcePositions sp, String file) throws IOException {
            this.cu = cu;
            this.sp = sp;
            this.lm = cu.getLineMap();
            this.src = cu.getSourceFile().getCharContent(true).toString();
            this.file = file;
        }

        FileResult run() {
            if (cu.getPackage() != null) {
                pkg = cu.getPackage().getPackageName().toString();
                cover(start(cu.getPackage()), end(cu.getPackage()));
            }
            for (ImportTree i : cu.getImports()) {
                cover(start(i), end(i));
            }
            for (Tree t : cu.getTypeDecls()) {
                scan(t, null);
            }
            List<String> lines = src.lines().toList();
            List<Integer> missing = new ArrayList<>();
            int code = 0;
            boolean inBlock = false;
            for (int i = 1; i <= lines.size(); i++) {
                String raw = lines.get(i - 1);
                StringBuilder kept = new StringBuilder();
                for (int p = 0; p < raw.length(); p++) {            // drop comments, keep everything else
                    if (inBlock) {
                        int close = raw.indexOf("*/", p);
                        if (close < 0) { p = raw.length(); } else { p = close + 1; inBlock = false; }
                    } else if (raw.startsWith("//", p)) {
                        break;
                    } else if (raw.startsWith("/*", p)) {
                        inBlock = true;
                        p++;
                    } else {
                        kept.append(raw.charAt(p));
                    }
                }
                String t = kept.toString().replaceAll("\\s+", "");
                if (t.isEmpty() || t.matches("[{}();,]*")) {       // blank, comment, or only brackets and separators
                    continue;
                }
                code++;
                if (!covered.get(i)) {
                    missing.add(i);
                }
            }
            return new FileResult(file, lines.size(), code, missing, units, List.of());
        }

        // ---- positions and text

        long start(Tree t) { return t == null ? -1 : sp.getStartPosition(cu, t); }

        long end(Tree t) { return t == null ? -1 : sp.getEndPosition(cu, t); }

        int line(long pos) {
            return pos < 0 ? 1 : (int) Math.max(1, lm.getLineNumber(Math.min(pos, Math.max(0, src.length() - 1))));
        }

        int column(long pos) {
            return pos < 0 ? 1 : (int) lm.getColumnNumber(Math.min(pos, Math.max(0, src.length() - 1)));
        }

        String text(long s, long e) {
            if (s < 0 || e <= s || s >= src.length()) {
                return "";
            }
            String t = src.substring((int) s, (int) Math.min(e, src.length())).replaceAll("\\s+", " ").trim();
            return t.length() > 300 ? t.substring(0, 300) + "..." : t;
        }

        String text(Tree t) {
            Tree x = t;
            while (x instanceof ParenthesizedTree p) {
                x = p.getExpression();
            }
            return text(start(x), end(x));
        }

        void cover(long s, long e) {
            if (s < 0) {
                return;
            }
            int from = line(s);
            int to = e <= s ? from : line(e - 1);
            covered.set(from, to + 1);
        }

        void statement(String kind, Tree t, long headerEnd) {
            cover(start(t), headerEnd);
            if (cur != null) {
                cur.statements.add(new Statement(kind, line(start(t)), line(Math.max(start(t) + 1, headerEnd) - 1)));
            }
        }

        void decision(String kind, Tree at, String condition, List<String> outcomes) {
            if (cur != null) {
                cur.decisions.add(new Decision(kind, line(start(at)), column(start(at)), condition, List.copyOf(outcomes)));
            }
        }

        // ---- units

        private void unit(Builder b, Runnable body) {
            Builder saved = cur;
            cur = b;
            try {
                body.run();
            } finally {
                cur = saved;
                units.add(new Unit(file, b.kind, b.owner, b.name, b.paramCount, b.params, b.line, b.endLine,
                        b.statements, b.decisions));
            }
        }

        String owner() {
            return classes.isEmpty() ? "" : classes.peek();
        }

        @Override
        public Void visitClass(ClassTree t, Void v) {
            String simple = t.getSimpleName().toString();
            String enclosing = classes.peek();
            String qn;
            if (simple.isEmpty()) {
                int n = anonymous.merge(enclosing == null ? "" : enclosing, 1, Integer::sum);
                qn = enclosing + "$" + n;
            } else if (enclosing == null) {
                qn = pkg.isEmpty() ? simple : pkg + "." + simple;
            } else {
                qn = enclosing + "$" + simple;
            }
            int brace = src.indexOf('{', (int) Math.max(0, start(t)));
            cover(start(t), brace < 0 ? end(t) : brace + 1);
            Builder saved = cur;
            cur = null;
            classes.push(qn);
            for (Tree member : t.getMembers()) {
                if (member instanceof VariableTree f) {
                    cover(start(f), end(f));
                    if (f.getInitializer() != null) {
                        Builder b = new Builder("FIELD_INIT", qn, f.getName().toString(), 0, "", line(start(f)), line(end(f) - 1));
                        unit(b, () -> {
                            statement("FIELD_INIT", f, end(f));
                            scan(f.getInitializer(), null);
                        });
                    }
                } else if (member instanceof BlockTree b) {
                    String name = b.isStatic() ? "<static-init>" : "<init-block>";
                    Builder ub = new Builder(b.isStatic() ? "STATIC_INIT" : "INIT_BLOCK", qn, name, 0, "", line(start(b)), line(end(b) - 1));
                    unit(ub, () -> {
                        cover(start(b), start(b) + text(start(b), end(b)).indexOf('{') + 1L);
                        scan(b, null);
                    });
                } else {
                    scan(member, null);
                }
            }
            classes.pop();
            cur = saved;
            return null;
        }

        @Override
        public Void visitMethod(MethodTree t, Void v) {
            String name = t.getName().toString();
            String params = t.getParameters().stream().map(p -> p.getType() == null ? "?" : p.getType().toString())
                    .collect(Collectors.joining(","));
            Builder b = new Builder(name.equals("<init>") ? "CONSTRUCTOR" : "METHOD", owner(), name, t.getParameters().size(),
                    params, line(start(t)), line(end(t) - 1));
            cover(start(t), t.getBody() != null ? start(t.getBody()) + 1 : end(t));
            if (t.getBody() != null) {
                unit(b, () -> scan(t.getBody(), null));
            } else {
                unit(b, () -> { });
            }
            return null;
        }

        @Override
        public Void visitLambdaExpression(LambdaExpressionTree t, Void v) {
            Builder b = new Builder("LAMBDA", owner(), "<lambda>", t.getParameters().size(), "", line(start(t)), line(end(t) - 1));
            unit(b, () -> {
                Tree body = t.getBody();
                if (t.getBodyKind() == LambdaExpressionTree.BodyKind.EXPRESSION) {
                    cur.statements.add(new Statement("LAMBDA_BODY", line(start(body)), line(end(body) - 1)));
                    cover(start(body), end(body));
                    if (booleanShaped((ExpressionTree) body)) {
                        decision("LAMBDA_PREDICATE", body, text(body), List.of("true", "false"));
                    }
                }
                scan(body, null);
            });
            return null;
        }

        private static boolean booleanShaped(ExpressionTree e) {
            ExpressionTree x = e;
            while (x instanceof ParenthesizedTree p) {
                x = p.getExpression();
            }
            if (x instanceof BinaryTree b) {
                return switch (b.getKind()) {
                    case LESS_THAN, GREATER_THAN, LESS_THAN_EQUAL, GREATER_THAN_EQUAL, EQUAL_TO, NOT_EQUAL_TO,
                         CONDITIONAL_AND, CONDITIONAL_OR -> true;
                    default -> false;
                };
            }
            return x instanceof InstanceOfTree || (x instanceof UnaryTree u && u.getKind() == Tree.Kind.LOGICAL_COMPLEMENT)
                    || (x instanceof LiteralTree l && l.getValue() instanceof Boolean);
        }

        // ---- simple statements

        @Override
        public Void visitVariable(VariableTree t, Void v) {
            statement("DECL", t, end(t));
            return super.visitVariable(t, v);
        }

        @Override
        public Void visitBindingPattern(BindingPatternTree t, Void v) {
            return null;                    // a pattern binding is not a statement
        }

        @Override
        public Void visitExpressionStatement(ExpressionStatementTree t, Void v) {
            ExpressionTree e = t.getExpression();
            String kind = e instanceof AssignmentTree || e instanceof CompoundAssignmentTree ? "ASSIGN"
                    : e instanceof UnaryTree ? "INCDEC" : e instanceof MethodInvocationTree ? "CALL"
                    : e instanceof NewClassTree ? "NEW" : "EXPR";
            statement(kind, t, end(t));
            return super.visitExpressionStatement(t, v);
        }

        @Override
        public Void visitReturn(ReturnTree t, Void v) {
            statement("RETURN", t, end(t));
            return super.visitReturn(t, v);
        }

        @Override
        public Void visitThrow(ThrowTree t, Void v) {
            statement("THROW", t, end(t));
            return super.visitThrow(t, v);
        }

        @Override
        public Void visitBreak(BreakTree t, Void v) {
            statement("BREAK", t, end(t));
            return null;
        }

        @Override
        public Void visitContinue(ContinueTree t, Void v) {
            statement("CONTINUE", t, end(t));
            return null;
        }

        @Override
        public Void visitEmptyStatement(EmptyStatementTree t, Void v) {
            statement("EMPTY", t, end(t));
            return null;
        }

        @Override
        public Void visitYield(YieldTree t, Void v) {
            statement("YIELD", t, end(t));
            return super.visitYield(t, v);
        }

        @Override
        public Void visitAssert(AssertTree t, Void v) {
            statement("ASSERT", t, end(t));
            decision("ASSERT", t, text(t.getCondition()), List.of("holds", "fails"));
            return super.visitAssert(t, v);
        }

        @Override
        public Void visitSynchronized(SynchronizedTree t, Void v) {
            statement("SYNC", t, start(t.getBlock()) + 1);
            return super.visitSynchronized(t, v);
        }

        @Override
        public Void visitLabeledStatement(LabeledStatementTree t, Void v) {
            statement("LABEL", t, start(t.getStatement()));
            return super.visitLabeledStatement(t, v);
        }

        // ---- decisions

        @Override
        public Void visitIf(IfTree t, Void v) {
            statement("IF", t, end(t.getCondition()));
            decision("IF", t, text(t.getCondition()), List.of("then", "else"));
            scan(t.getCondition(), null);
            scan(t.getThenStatement(), null);
            if (t.getElseStatement() != null) {
                int kw = src.lastIndexOf("else", (int) Math.max(0, start(t.getElseStatement())));
                if (kw > end(t.getThenStatement())) {
                    cover(kw, kw + 4L);              // an "else" on its own line
                }
                scan(t.getElseStatement(), null);
            }
            return null;
        }

        @Override
        public Void visitConditionalExpression(ConditionalExpressionTree t, Void v) {
            decision("TERNARY", t, text(t.getCondition()), List.of("then", "else"));
            return super.visitConditionalExpression(t, v);
        }

        @Override
        public Void visitBinary(BinaryTree t, Void v) {
            if (t.getKind() == Tree.Kind.CONDITIONAL_AND || t.getKind() == Tree.Kind.CONDITIONAL_OR) {
                String op = t.getKind() == Tree.Kind.CONDITIONAL_AND ? "&&" : "||";
                decision("SHORT_CIRCUIT", t, op + " " + text(t.getRightOperand()), List.of("evaluate right", "short-circuit"));
            }
            return super.visitBinary(t, v);
        }

        private void loop(String kind, Tree t, long headerEnd, ExpressionTree condition, Tree body, Runnable scanHeader) {
            statement(kind, t, headerEnd);
            boolean endless = condition == null || (text(condition).equals("true"));
            decision("LOOP", t, condition == null ? kind : text(condition), endless ? List.of("enter") : List.of("enter", "exit"));
            scanHeader.run();
            scan(body, null);
        }

        @Override
        public Void visitForLoop(ForLoopTree t, Void v) {
            loop("FOR", t, start(t.getStatement()), t.getCondition(), t.getStatement(), () -> {
                t.getInitializer().forEach(i -> scan(i, null));
                scan(t.getCondition(), null);
                t.getUpdate().forEach(u -> scan(u, null));
            });
            return null;
        }

        @Override
        public Void visitEnhancedForLoop(EnhancedForLoopTree t, Void v) {
            loop("FOREACH", t, start(t.getStatement()), t.getExpression(), t.getStatement(), () -> scan(t.getExpression(), null));
            return null;
        }

        @Override
        public Void visitWhileLoop(WhileLoopTree t, Void v) {
            loop("WHILE", t, end(t.getCondition()), t.getCondition(), t.getStatement(), () -> scan(t.getCondition(), null));
            return null;
        }

        @Override
        public Void visitDoWhileLoop(DoWhileLoopTree t, Void v) {
            loop("DO", t, start(t.getStatement()) + 1, t.getCondition(), t.getStatement(), () -> { });
            cover(end(t.getStatement()), end(t));          // the trailing "while (cond);"
            scan(t.getCondition(), null);
            return null;
        }

        @Override
        public Void visitSwitch(SwitchTree t, Void v) {
            cases("SWITCH", t, t.getExpression(), t.getCases(), false);
            return null;
        }

        @Override
        public Void visitSwitchExpression(SwitchExpressionTree t, Void v) {
            cases("SWITCH_EXPR", t, t.getExpression(), t.getCases(), true);
            return null;
        }

        /** Shared by switch statements and expressions: one outcome per label, a guard is its own decision. */
        private void cases(String kind, Tree sw, ExpressionTree selector, List<? extends CaseTree> cases, boolean expression) {
            if (!expression) {
                statement("SWITCH", sw, end(selector) + 1);
            } else {
                cover(start(sw), end(selector) + 1);
            }
            scan(selector, null);
            List<String> outcomes = new ArrayList<>();
            boolean hasDefault = false;
            boolean hasPattern = false;
            for (CaseTree c : cases) {
                for (CaseLabelTree label : c.getLabels()) {
                    if (label instanceof DefaultCaseLabelTree) {
                        hasDefault = true;
                        outcomes.add("default");
                    } else if (label instanceof PatternCaseLabelTree p) {
                        hasPattern = true;
                        outcomes.add("pattern " + text(p.getPattern()));
                    } else if (label instanceof ConstantCaseLabelTree k) {
                        outcomes.add(text(k.getConstantExpression()));
                    }
                }
            }
            if (!expression && !hasDefault && !hasPattern) {
                outcomes.add("default (implicit)");
            }
            decision(kind, sw, text(selector), outcomes);
            for (CaseTree c : cases) {
                boolean rule = c.getCaseKind() == CaseTree.CaseKind.RULE;
                List<? extends StatementTree> body = c.getStatements();
                long headerEnd = rule ? start(c.getBody()) : (body != null && !body.isEmpty() ? start(body.get(0)) : end(c));
                statement("CASE", c, Math.max(headerEnd, start(c) + 1));
                if (c.getGuard() != null) {
                    decision("PATTERN_GUARD", c.getGuard(), text(c.getGuard()), List.of("guard true", "guard false"));
                    scan(c.getGuard(), null);
                }
                if (rule) {
                    scan(c.getBody(), null);
                } else if (body != null) {
                    body.forEach(s -> scan(s, null));
                }
            }
        }

        @Override
        public Void visitTry(TryTree t, Void v) {
            statement("TRY", t, start(t.getBlock()) + 1);
            List<String> outcomes = new ArrayList<>(List.of("normal"));
            for (CatchTree c : t.getCatches()) {
                outcomes.add("catch " + text(c.getParameter().getType()));
            }
            decision("TRY", t, "try", outcomes);
            t.getResources().forEach(r -> scan(r, null));
            scan(t.getBlock(), null);
            for (CatchTree c : t.getCatches()) {
                statement("CATCH", c, start(c.getBlock()) + 1);
                scan(c.getBlock(), null);
            }
            if (t.getFinallyBlock() != null) {
                int kw = src.lastIndexOf("finally", (int) Math.max(0, start(t.getFinallyBlock())));
                if (kw >= 0) {
                    cover(kw, start(t.getFinallyBlock()) + 1);
                }
                statement("FINALLY", t.getFinallyBlock(), start(t.getFinallyBlock()) + 1);
                scan(t.getFinallyBlock(), null);
            }
            return null;
        }
    }
}
