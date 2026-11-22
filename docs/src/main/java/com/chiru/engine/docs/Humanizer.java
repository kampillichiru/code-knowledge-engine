package com.chiru.engine.docs;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Turns the Java expressions the engine extracted (conditions, assignments, returns) into plain English for people who do not read
 * code. It is deterministic and rule based: it parses the expression, so a condition such as
 * {@code shipment.getQuantity().signum() == 0} becomes "the shipment quantity is zero" and
 * {@code not (x > 5)} becomes "x is at most 5". It never invents meaning: names are only split into words
 * ({@code newQuantity} to "new quantity", {@code rate_pct} to "rate percent"), and an expression it cannot parse is shown as written, in code
 * format, so nothing is silently dropped. A glossary maps a name (or {@code table.column}) to the business term to use instead.
 */
final class Humanizer {

    /** Common abbreviations in column and variable names. Used only to spell a word out, never to explain it. */
    private static final Map<String, String> ABBREVIATIONS = Map.ofEntries(
            Map.entry("pct", "percent"), Map.entry("amt", "amount"), Map.entry("qty", "quantity"), Map.entry("num", "number"),
            Map.entry("dt", "date"), Map.entry("cd", "code"), Map.entry("desc", "description"), Map.entry("addr", "address"),
            Map.entry("txn", "transaction"),
            Map.entry("cnt", "count"), Map.entry("flg", "flag"), Map.entry("max", "maximum"), Map.entry("min", "minimum"),
            Map.entry("ts", "timestamp"), Map.entry("ref", "reference"), Map.entry("seq", "sequence"));

    private static final Pattern NOT = Pattern.compile("^not \\((.*)\\)$", Pattern.DOTALL);
    private static final Pattern DECLARATION = Pattern.compile("^(?:final\\s+)?[\\w.<>\\[\\]?, ]+?\\s+(\\w+) = (.*)$", Pattern.DOTALL);

    private final Map<String, String> glossary;
    private int unparsed;

    Humanizer(Map<String, String> glossary) {
        this.glossary = glossary == null ? Map.of() : glossary;
    }

    /** Expressions that could not be parsed and were shown as code. */
    boolean hasTerm(String key) {
        return glossary.containsKey(key);
    }

    int unparsed() {
        return unparsed;
    }

    // ================================================================ names

    /** {@code newQuantity}, {@code rate_pct}, {@code SHIP_CAP} as words: "new quantity", "rate percent", "ship cap". */
    String words(String name) {
        String override = glossary.get(name);
        if (override != null) {
            return override;
        }
        String spaced = name.replace('_', ' ').replace('$', ' ')
                .replaceAll("([a-z0-9])([A-Z])", "$1 $2").replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2").trim();
        List<String> out = new ArrayList<>();
        for (String w : spaced.split("\\s+")) {
            String lower = w.toLowerCase(Locale.ROOT);
            out.add(ABBREVIATIONS.getOrDefault(lower, lower.equals("id") ? "ID" : lower));
        }
        return String.join(" ", out);
    }

    /** A column as a business would say it: "shipment quantity" for {@code shipment.quantity}. */
    String column(String table, String column) {
        String override = glossary.get(table + "." + column);
        return override != null ? override : words(table) + " " + words(column);
    }

    // ================================================================ public entry points

    /** A condition as a clause: "the shipment quantity is zero". */
    String condition(String javaCondition) {
        if (javaCondition == null || javaCondition.isBlank()) {
            return "always";
        }
        String text = javaCondition.trim();
        for (String[] split : new String[][] {{" OR ", " or "}, {" AND ", " and "}}) {          // the engine joins conditions with AND and alternatives with OR
            List<String> parts = splitTopLevel(text, split[0]);
            if (parts.size() > 1) {
                return String.join(split[1], parts.stream().map(p -> {
                    String inner = stripParens(p);
                    String said = condition(inner);
                    return parts.size() > 1 && (inner.contains(" OR ") || inner.contains(" AND ")) ? "(" + said + ")" : said;
                }).toList());
            }
        }
        text = text.replace(" matches no other case", " == <other>");
        if (text.contains(" is one of ")) {
            int at = text.indexOf(" is one of ");
            return render(text.substring(0, at)) + " is one of " + text.substring(at + 11);
        }
        Optional<Node> node = parse(text);
        if (node.isEmpty()) {
            return unparsed(javaCondition);
        }
        return clause(node.get());
    }

    private static List<String> splitTopLevel(String text, String separator) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int start = 0;
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '"') {
                quoted = !quoted;
            } else if (!quoted && ch == '(') {
                depth++;
            } else if (!quoted && ch == ')') {
                depth--;
            } else if (!quoted && depth == 0 && text.startsWith(separator, i)) {
                out.add(text.substring(start, i));
                start = i + separator.length();
                i = start - 1;
            }
        }
        out.add(text.substring(start));
        return out;
    }

    private static String stripParens(String p) {
        String t = p.trim();
        while (t.startsWith("(") && t.endsWith(")") && balanced(t.substring(1, t.length() - 1))) {
            t = t.substring(1, t.length() - 1).trim();
        }
        return t;
    }

    /** A value as a noun phrase: "the smaller of the shipment quantity and the ship cap". */
    String value(String javaExpression) {
        if (javaExpression == null || javaExpression.isBlank()) {
            return "nothing";
        }
        Optional<Node> node = parse(javaExpression.trim());
        return node.map(this::phrase).orElseGet(() -> unparsed(javaExpression));
    }

    /** An effect: "status = X" becomes "sets the status to X", "return X" becomes "returns X". */
    String effect(String effectText) {
        String t = effectText.trim();
        boolean afterFinally = false;
        if (t.startsWith("(finally) ")) {
            t = t.substring(10);
            afterFinally = true;
        }
        String prefix = afterFinally ? "in every case, " : "";
        int bracket = t.indexOf("   [in:");
        if (bracket > 0) {
            t = t.substring(0, bracket);
        }
        if (t.startsWith("return ")) {
            return prefix + "returns " + value(t.substring(7));
        }
        if (t.equals("return")) {
            return prefix + "ends";
        }
        if (t.startsWith("throw ")) {
            return prefix + "stops with an error: " + value(t.substring(6));
        }
        int compound = t.indexOf(" (compound) ");
        if (compound > 0) {
            String target = t.substring(0, compound);
            String rest = t.substring(compound + 12).trim();
            for (String op : new String[] {"+=", "-=", "*=", "/="}) {
                int at = rest.indexOf(op);
                if (at >= 0) {
                    String amount = value(rest.substring(at + 2).replaceAll(";$", ""));
                    return prefix + switch (op) {
                        case "+=" -> "increases " + render(target) + " by " + amount;
                        case "-=" -> "decreases " + render(target) + " by " + amount;
                        case "*=" -> "multiplies " + render(target) + " by " + amount;
                        default -> "divides " + render(target) + " by " + amount;
                    };
                }
            }
            return prefix + "updates " + render(target) + " (" + unparsed(rest) + ")";
        }
        var declaration = DECLARATION.matcher(t);
        int eq = t.indexOf(" = ");
        if (eq > 0 && !t.substring(0, eq).contains("(")) {
            String target = t.substring(0, eq);
            if (declaration.matches() && !target.matches("[\\w.\\[\\]]+")) {
                target = declaration.group(1);
            }
            return prefix + "sets " + render(target) + " to " + value(t.substring(eq + 3));
        }
        return prefix + "performs " + value(t);
    }

    private String render(String simple) {
        Optional<Node> n = parse(simple);
        return n.map(this::phrase).orElse(simple);
    }

    private String unparsed(String raw) {
        unparsed++;
        return "`" + raw.replaceAll("\\s+", " ").trim() + "`";
    }

    // ================================================================ parsing

    private sealed interface Node permits Lit, Name, Member, Call, New, Un, Bin, Cond, Index {}

    private record Lit(String text, boolean string) implements Node {}

    private record Name(String id) implements Node {}

    private record Member(Node target, String id) implements Node {}

    private record Call(Node target, String id, List<Node> args) implements Node {}

    private record New(String type, List<Node> args) implements Node {}

    private record Un(String op, Node operand) implements Node {}

    private record Bin(String op, Node left, Node right) implements Node {}

    private record Cond(Node test, Node yes, Node no) implements Node {}

    private record Index(Node target, Node index) implements Node {}

    private Optional<Node> parse(String text) {
        var m = NOT.matcher(text);
        if (m.matches() && balanced(m.group(1))) {
            return parse(m.group(1)).map(n -> new Un("!", n));
        }
        try {
            Parser p = new Parser(tokens(text));
            Node n = p.expression();
            return p.atEnd() ? Optional.of(n) : Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static boolean balanced(String s) {
        int depth = 0;
        for (char c : s.toCharArray()) {
            depth += c == '(' ? 1 : c == ')' ? -1 : 0;
            if (depth < 0) {
                return false;
            }
        }
        return depth == 0;
    }

    private static List<String> tokens(String s) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < s.length() && s.charAt(j) != c) {
                    j += s.charAt(j) == '\\' ? 2 : 1;
                }
                if (j >= s.length()) {
                    throw new IllegalArgumentException("unterminated literal");
                }
                out.add(s.substring(i, j + 1));
                i = j + 1;
            } else if (Character.isLetter(c) || c == '_' || c == '$') {
                int j = i;
                while (j < s.length() && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '_' || s.charAt(j) == '$')) {
                    j++;
                }
                out.add(s.substring(i, j));
                i = j;
            } else if (Character.isDigit(c)) {
                int j = i;
                while (j < s.length() && (Character.isLetterOrDigit(s.charAt(j)) || s.charAt(j) == '.' || s.charAt(j) == '_')) {
                    j++;
                }
                out.add(s.substring(i, j));
                i = j;
            } else {
                String two = i + 1 < s.length() ? s.substring(i, i + 2) : "";
                if (List.of("==", "!=", "<=", ">=", "&&", "||", "->", "::").contains(two)) {
                    out.add(two);
                    i += 2;
                } else if ("+-*/%<>!?:(),.[]&|^".indexOf(c) >= 0) {
                    out.add(String.valueOf(c));
                    i++;
                } else {
                    throw new IllegalArgumentException("unexpected " + c);
                }
            }
        }
        return out;
    }

    private static final class Parser {
        private final List<String> t;
        private int p;

        Parser(List<String> tokens) {
            this.t = tokens;
        }

        boolean atEnd() {
            return p >= t.size();
        }

        private String peek() {
            return p < t.size() ? t.get(p) : "";
        }

        private boolean eat(String s) {
            if (peek().equals(s)) {
                p++;
                return true;
            }
            return false;
        }

        private void expect(String s) {
            if (!eat(s)) {
                throw new IllegalArgumentException("expected " + s);
            }
        }

        Node expression() {
            Node test = binary(0);
            if (eat("?")) {
                Node yes = expression();
                expect(":");
                return new Cond(test, yes, expression());
            }
            return test;
        }

        private static final List<List<String>> LEVELS = List.of(List.of("||"), List.of("&&"), List.of("==", "!="),
                List.of("<", ">", "<=", ">=", "instanceof"), List.of("+", "-"), List.of("*", "/", "%"));

        private Node binary(int level) {
            if (level == LEVELS.size()) {
                return unary();
            }
            Node left = binary(level + 1);
            while (LEVELS.get(level).contains(peek())) {
                String op = t.get(p++);
                Node right = op.equals("instanceof") ? new Name(t.get(p++)) : binary(level + 1);
                left = new Bin(op, left, right);
            }
            return left;
        }

        private Node unary() {
            if (eat("!")) {
                return new Un("!", unary());
            }
            if (eat("-")) {
                return new Un("-", unary());
            }
            return postfix(primary());
        }

        private Node postfix(Node n) {
            while (true) {
                if (eat(".")) {
                    String id = t.get(p++);
                    if (Character.isUpperCase(id.charAt(0)) && packageChain(n)) {
                        n = new Name(id);                              // java.lang.System.x reads as System.x
                        continue;
                    }
                    n = eat("(") ? new Call(n, id, args()) : new Member(n, id);
                } else if (eat("[")) {
                    Node i = expression();
                    expect("]");
                    n = new Index(n, i);
                } else {
                    return n;
                }
            }
        }

        private static boolean packageChain(Node n) {
            return n instanceof Name x && Character.isLowerCase(x.id().charAt(0)) && x.id().length() > 1
                    || n instanceof Member m && Character.isLowerCase(m.id().charAt(0)) && packageChain(m.target());
        }

        private List<Node> args() {
            List<Node> out = new ArrayList<>();
            if (!eat(")")) {
                do {
                    out.add(expression());
                } while (eat(","));
                expect(")");
            }
            return out;
        }

        private Node primary() {
            String tok = t.get(p++);
            if (tok.equals("(")) {
                Node n = expression();
                expect(")");
                return n;
            }
            if (tok.startsWith("\"") || tok.startsWith("'")) {
                return new Lit(tok, true);
            }
            if (Character.isDigit(tok.charAt(0))) {
                return new Lit(tok.replaceAll("[LlFfDd]$", ""), false);
            }
            if (tok.equals("new")) {
                StringBuilder type = new StringBuilder(t.get(p++));
                while (eat(".")) {
                    type.append('.').append(t.get(p++));
                }
                if (eat("<")) {                                  // generics: skip
                    while (!eat(">")) {
                        p++;
                    }
                }
                expect("(");
                return new New(type.toString(), args());
            }
            if (tok.equals("true") || tok.equals("false") || tok.equals("null")) {
                return new Lit(tok, false);
            }
            if (eat("(")) {
                return new Call(null, tok, args());
            }
            if (!Character.isLetter(tok.charAt(0)) && tok.charAt(0) != '_' && tok.charAt(0) != '$') {
                throw new IllegalArgumentException("unexpected " + tok);
            }
            return new Name(tok);
        }
    }

    // ================================================================ rendering: conditions (clauses)

    private static final Map<String, String> COMPARE = Map.of("==", "is", "!=", "is not", "<", "is less than", ">", "is greater than",
            "<=", "is at most", ">=", "is at least");
    private static final Map<String, String> NEGATED = Map.of("==", "!=", "!=", "==", "<", ">=", ">=", "<", ">", "<=", "<=", ">");

    private String clause(Node n) {
        return switch (n) {
            case Un u when u.op().equals("!") -> negate(u.operand());
            case Bin b when b.op().equals("&&") -> joinClause(b, "and");
            case Bin b when b.op().equals("||") -> joinClause(b, "or");
            case Bin b when COMPARE.containsKey(b.op()) -> comparison(b);
            case Bin b when b.op().equals("instanceof") -> phrase(b.left()) + " is a " + words(((Name) b.right()).id());
            case Call c when c.target() != null && c.args().isEmpty() && (c.id().equals("isEmpty") || c.id().equals("isBlank")) ->
                    phrase(c.target()) + " is empty";
            case Call c when c.target() != null && c.args().isEmpty() && c.id().equals("isPresent") -> phrase(c.target()) + " is present";
            case Call c when c.target() != null && c.args().size() == 1 && (c.id().equals("isBefore") || c.id().equals("isAfter")) ->
                    phrase(c.target()) + (c.id().equals("isBefore") ? " is before " : " is after ") + phrase(c.args().get(0));
            case Call c when c.id().equals("equals") && c.target() != null && c.args().size() == 1 ->
                    phrase(c.target()) + " equals " + phrase(c.args().get(0));
            case Call c when c.id().equals("contains") && c.target() != null && c.args().size() == 1 ->
                    phrase(c.target()) + " contains " + phrase(c.args().get(0));
            case Call c when c.id().equals("startsWith") && c.target() != null && c.args().size() == 1 ->
                    phrase(c.target()) + " starts with " + phrase(c.args().get(0));
            case Call c when c.id().startsWith("is") && c.id().length() > 2 && c.args().isEmpty() && c.target() != null ->
                    phrase(c.target()) + " is " + words(c.id().substring(2));
            default -> phrase(n);
        };
    }

    private String joinClause(Bin b, String word) {
        return wrapIfMixed(b.left(), b.op(), clause(b.left())) + " " + word + " " + wrapIfMixed(b.right(), b.op(), clause(b.right()));
    }

    private static String wrapIfMixed(Node child, String op, String text) {
        boolean mixed = child instanceof Bin cb && (cb.op().equals("&&") || cb.op().equals("||")) && !cb.op().equals(op);
        return mixed ? "(" + text + ")" : text;
    }

    private String negate(Node n) {
        if (n instanceof Bin b && NEGATED.containsKey(b.op())) {
            return comparison(new Bin(NEGATED.get(b.op()), b.left(), b.right()));
        }
        if (n instanceof Un u && u.op().equals("!")) {
            return clause(u.operand());
        }
        if (n instanceof Bin b && (b.op().equals("&&") || b.op().equals("||"))) {
            return "it is not true that " + clause(n);
        }
        if (n instanceof Call c && c.target() != null && c.args().isEmpty() && (c.id().equals("isEmpty") || c.id().equals("isBlank"))) {
            return phrase(c.target()) + " is not empty";
        }
        if (n instanceof Call c && c.target() != null && c.args().isEmpty() && c.id().equals("isPresent")) {
            return phrase(c.target()) + " is missing";
        }
        if (n instanceof Call c && c.target() != null && c.args().size() == 1 && (c.id().equals("isBefore") || c.id().equals("isAfter"))) {
            return phrase(c.target()) + (c.id().equals("isBefore") ? " is not before " : " is not after ") + phrase(c.args().get(0));
        }
        if (n instanceof Call c && c.id().equals("equals") && c.target() != null && c.args().size() == 1) {
            return phrase(c.target()) + " does not equal " + phrase(c.args().get(0));
        }
        if (n instanceof Call c && c.id().equals("contains") && c.target() != null && c.args().size() == 1) {
            return phrase(c.target()) + " does not contain " + phrase(c.args().get(0));
        }
        if (n instanceof Call c && c.id().equals("startsWith") && c.target() != null && c.args().size() == 1) {
            return phrase(c.target()) + " does not start with " + phrase(c.args().get(0));
        }
        if (n instanceof Name || n instanceof Member) {
            return "not " + phrase(n);
        }
        return "not (" + clause(n) + ")";
    }

    private String comparison(Bin b) {
        String op = b.op();
        Node l = b.left();
        Node r = b.right();
        if (isNull(r) && (op.equals("==") || op.equals("!="))) {
            return phrase(l) + (op.equals("==") ? " is missing" : " is present");
        }
        // x.signum() <op> 0 and x.compareTo(y) <op> 0 read as comparisons of x
        if (l instanceof Call c && c.target() != null && isZero(r)) {
            if (c.id().equals("signum") && c.args().isEmpty()) {
                return phrase(c.target()) + " " + switch (op) {
                    case "==" -> "is zero";
                    case "!=" -> "is not zero";
                    case ">" -> "is positive";
                    case "<" -> "is negative";
                    case ">=" -> "is zero or positive";
                    default -> "is zero or negative";
                };
            }
            if (c.id().equals("compareTo") && c.args().size() == 1) {
                return phrase(c.target()) + " " + (op.equals("==") ? "equals" : COMPARE.get(op)) + " " + phrase(c.args().get(0));
            }
        }
        String verb = COMPARE.get(op);
        if ((op.equals("==") || op.equals("!=")) && !(r instanceof Lit) && !(l instanceof Lit)) {
            verb = op.equals("==") ? "equals" : "does not equal";
        }
        return phrase(l) + " " + verb + " " + phrase(r);
    }

    private static boolean isNull(Node n) {
        return n instanceof Lit l && l.text().equals("null");
    }

    private static boolean isZero(Node n) {
        return n instanceof Lit l && !l.string() && l.text().matches("0(\\.0+)?");
    }

    // ================================================================ rendering: values (noun phrases)

    private String phrase(Node n) {
        return switch (n) {
            case Lit l -> literal(l);
            case Name x -> constantOrWords(x.id());
            case Member m -> member(m);
            case Index i -> phrase(i.target()) + " item " + phrase(i.index());
            case New w -> construct(w);
            case Un u when u.op().equals("-") -> "minus " + phrase(u.operand());
            case Un u -> clause(u);
            case Cond c -> "if " + clause(c.test()) + " then " + phrase(c.yes()) + " otherwise " + phrase(c.no());
            case Bin b -> binary(b);
            case Call c -> call(c);
        };
    }

    private String literal(Lit l) {
        if (l.text().equals("null")) {
            return "empty";
        }
        return l.text();
    }

    private static boolean constantName(String id) {
        return id.length() > 1 && id.equals(id.toUpperCase(Locale.ROOT)) && id.chars().anyMatch(Character::isLetter);
    }

    private String constantOrWords(String id) {
        if (constantName(id)) {
            return switch (id) {
                case "ZERO" -> "zero";
                case "ONE" -> "one";
                case "TEN" -> "ten";
                default -> "the " + words(id);
            };
        }
        return words(id);
    }

    /** {@code shipment.quantity} reads "shipment quantity"; {@code Owner.CONSTANT} reads "the constant words". */
    private String member(Member m) {
        if (constantName(m.id())) {
            if (m.id().equals("ZERO") || m.id().equals("ONE") || m.id().equals("TEN")) {
                return constantOrWords(m.id());
            }
            if (m.target() instanceof Name || m.target() instanceof Member) {
                Optional<String> fromGlossary = Optional.ofNullable(glossary.get(m.id()));
                return "the " + fromGlossary.orElseGet(() -> words(m.id()));
            }
        }
        if (m.target() instanceof Name owner && Character.isUpperCase(owner.id().charAt(0)) && !constantName(m.id())) {
            return words(m.id());                              // an enum constant such as Status.OPEN reads as "open"
        }
        String own = glossaryFor(m);
        if (own != null) {
            return own;
        }
        return phrase(m.target()) + " " + words(m.id());
    }

    private String glossaryFor(Member m) {
        if (m.target() instanceof Name t) {
            return glossary.get(t.id() + "." + m.id());
        }
        return null;
    }

    private String construct(New w) {
        String type = w.type().substring(w.type().lastIndexOf('.') + 1);
        if (type.equals("BigDecimal") || type.equals("BigInteger") || type.equals("Integer") || type.equals("Long")) {
            return w.args().size() == 1 ? stripQuotes(phrase(w.args().get(0))) : "a number";
        }
        String args = w.args().isEmpty() ? "" : " with " + String.join(", ", w.args().stream().map(this::phrase).toList());
        return "a new " + words(type) + args;
    }

    private static String stripQuotes(String s) {
        return s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"") ? s.substring(1, s.length() - 1) : s;
    }

    private String binary(Bin b) {
        if (b.op().equals("+") && (stringy(b.left()) || stringy(b.right()))) {
            return phrase(b.left()) + " followed by " + phrase(b.right());
        }
        if (COMPARE.containsKey(b.op()) || b.op().equals("&&") || b.op().equals("||") || b.op().equals("instanceof")) {
            return clause(b);
        }
        String sign = switch (b.op()) {
            case "-" -> "−";
            case "*" -> "×";
            case "/" -> "÷";
            default -> b.op();
        };
        return "(" + phrase(b.left()) + " " + sign + " " + phrase(b.right()) + ")";
    }

    private static boolean stringy(Node n) {
        return n instanceof Lit l && l.string() || n instanceof Bin b && b.op().equals("+") && (stringy(b.left()) || stringy(b.right()));
    }

    private String call(Call c) {
        List<Node> a = c.args();
        String id = c.id();
        if (c.target() == null) {
            return words(id) + (a.isEmpty() ? "" : " (" + String.join(", ", a.stream().map(this::phrase).toList()) + ")");
        }
        String target = phrase(c.target());
        // static helpers on well known types
        if (c.target() instanceof Name owner && Character.isUpperCase(owner.id().charAt(0))) {
            if (id.equals("valueOf") && a.size() == 1) {
                return phrase(a.get(0));
            }
            if (owner.id().equals("System") && id.equals("getProperty") && !a.isEmpty()) {
                return "the system setting " + phrase(a.get(0)) + (a.size() > 1 ? " (default " + phrase(a.get(1)) + ")" : "");
            }
            if (owner.id().equals("LocalDate") && id.equals("now")) {
                return "today's date";
            }
        }
        if (id.startsWith("get") && id.length() > 3 && a.isEmpty() && Character.isUpperCase(id.charAt(3))) {
            return target + " " + words(Character.toLowerCase(id.charAt(3)) + id.substring(4));
        }
        if (id.startsWith("is") && id.length() > 2 && a.isEmpty() && Character.isUpperCase(id.charAt(2))) {
            return target + " is " + words(Character.toLowerCase(id.charAt(2)) + id.substring(3));
        }
        return switch (id) {
            case "add", "plus" -> a.size() == 1 ? "(" + target + " + " + phrase(a.get(0)) + ")" : generic(c, target);
            case "subtract", "minus" -> a.size() == 1 ? "(" + target + " − " + phrase(a.get(0)) + ")" : generic(c, target);
            case "multiply" -> a.size() == 1 ? "(" + target + " × " + phrase(a.get(0)) + ")" : generic(c, target);
            case "divide" -> a.isEmpty() ? generic(c, target) : "(" + target + " ÷ " + phrase(a.get(0)) + rounding(a, 1) + ")";
            case "min" -> a.size() == 1 ? "the smaller of " + target + " and " + phrase(a.get(0)) : generic(c, target);
            case "max" -> a.size() == 1 ? "the larger of " + target + " and " + phrase(a.get(0)) : generic(c, target);
            case "negate" -> "minus " + target;
            case "abs" -> "the absolute value of " + target;
            case "signum" -> "the sign of " + target;
            case "setScale" -> a.isEmpty() ? target : target + " rounded to " + phrase(a.get(0)) + " decimal places" + roundingMode(a, 1);
            case "movePointRight" -> a.size() == 1 ? "(" + target + " × 10^" + phrase(a.get(0)) + ")" : generic(c, target);
            case "movePointLeft" -> a.size() == 1 ? "(" + target + " ÷ 10^" + phrase(a.get(0)) + ")" : generic(c, target);
            case "longValue", "intValue", "doubleValue", "toString", "trim", "strip" -> target;
            case "toUpperCase" -> target + " in upper case";
            case "toLowerCase" -> target + " in lower case";
            case "size", "length" -> "the number of " + target;
            case "plusDays" -> a.size() == 1 ? target + " plus " + phrase(a.get(0)) + " days" : generic(c, target);
            default -> generic(c, target);
        };
    }

    private String rounding(List<Node> args, int from) {
        String scale = args.size() > from ? ", to " + phrase(args.get(from)) + " decimal places" : "";
        return scale + roundingMode(args, from + 1);
    }

    private String roundingMode(List<Node> args, int at) {
        if (args.size() > at) {
            String mode = phrase(args.get(at));
            return ", rounding " + mode.replace("the ", "").replace("half up", "half up");
        }
        return "";
    }

    private String generic(Call c, String target) {
        String args = c.args().isEmpty() ? "" : " (" + String.join(", ", c.args().stream().map(this::phrase).toList()) + ")";
        return words(c.id()) + args + " of " + target;
    }
}
