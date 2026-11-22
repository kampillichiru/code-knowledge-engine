package com.chiru.engine.docs;

import com.chiru.engine.model.Entity;
import com.chiru.engine.model.EntityType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Expands the formatting of one output field into numbered steps and works examples through them. The field's layout expression is read
 * the way the code writes it: the idioms {@code padLeft}, {@code padRight}, {@code String.valueOf}, {@code String.format("%010d")},
 * {@code movePointRight}, {@code setScale}, {@code longValue} and friends, and any helper method of the analysed code (such as
 * {@code amount(value, width)}) whose body is made of those idioms. A helper's parameters are bound to the arguments of the call, so the
 * width given at the call site is the width used in the steps.
 *
 * <p>When any part of the expression is not one of these shapes the result is "unresolved": no steps and no examples are shown rather than
 * a guess. The examples are computed from the formatting steps only, starting from a value the column can hold, and are labelled that way.
 */
final class FormatRecipe {

    private FormatRecipe() {}

    record Result(boolean resolved, List<String> steps, List<List<String>> examples, String layout, String widthCheck, boolean widthProblem,
                  List<String> exampleFacts) {
        static Result none() {
            return new Result(false, List.of(), List.of(), null, null, false, List.of());
        }
    }

    // ------------------------------------------------------------------ operations

    private interface Op {
        String words();

        Object apply(Object value);

        default boolean numeric() {
            return false;
        }
    }

    private record MovePoint(int places) implements Op {
        public String words() {
            return places >= 0 ? "Move the decimal point " + places + " place" + (places == 1 ? "" : "s") + " to the right (multiply by " + BigInteger.TEN.pow(places) + ")"
                    : "Move the decimal point " + -places + " places to the left (divide by " + BigInteger.TEN.pow(-places) + ")";
        }

        public Object apply(Object v) {
            return ((BigDecimal) v).movePointRight(places);
        }

        public boolean numeric() {
            return true;
        }
    }

    private record Whole() implements Op {
        public String words() {
            return "Drop everything after the decimal point (no rounding)";
        }

        public Object apply(Object v) {
            return new BigDecimal(((BigDecimal) v).toBigInteger());
        }

        public boolean numeric() {
            return true;
        }
    }

    private record Scale(int places) implements Op {
        public String words() {
            return "Fix the value to " + places + " decimal place" + (places == 1 ? "" : "s");
        }

        public Object apply(Object v) {
            return ((BigDecimal) v).setScale(places, RoundingMode.HALF_UP);
        }

        public boolean numeric() {
            return true;
        }
    }

    private record Abs() implements Op {
        public String words() {
            return "Use the value without its sign";
        }

        public Object apply(Object v) {
            return ((BigDecimal) v).abs();
        }

        public boolean numeric() {
            return true;
        }
    }

    private record Text() implements Op {
        public String words() {
            return "Convert the value to text";
        }

        public Object apply(Object v) {
            return v instanceof BigDecimal b ? b.toPlainString() : String.valueOf(v);
        }
    }

    private record Case(boolean upper) implements Op {
        public String words() {
            return upper ? "Change the text to capital letters" : "Change the text to lower case";
        }

        public Object apply(Object v) {
            return upper ? String.valueOf(v).toUpperCase() : String.valueOf(v).toLowerCase();
        }
    }

    private record Trim() implements Op {
        public String words() {
            return "Remove spaces at both ends";
        }

        public Object apply(Object v) {
            return String.valueOf(v).trim();
        }
    }

    private record Pad(boolean left, int width, char ch) implements Op {
        public String words() {
            String what = ch == ' ' ? "spaces" : "'" + ch + "'";
            return "Add " + what + " on the " + (left ? "left" : "right") + " until the text is " + width + " characters long (a longer value is left as it is)";
        }

        public Object apply(Object v) {
            String s = String.valueOf(v);
            StringBuilder sb = new StringBuilder();
            if (!left) {
                sb.append(s);
            }
            for (int i = s.length(); i < width; i++) {
                sb.append(ch);
            }
            if (left) {
                sb.append(s);
            }
            return sb.toString();
        }
    }

    private record Format(String spec) implements Op {
        public String words() {
            return "Format with the pattern " + Md.code(spec);
        }

        public Object apply(Object v) {
            char conv = spec.charAt(spec.length() - 1);
            Object arg = conv == 'd' ? (Object) ((BigDecimal) v).toBigInteger() : conv == 'f' ? v : String.valueOf(v);
            return String.format(spec, arg);
        }

        public boolean numeric() {
            return spec.endsWith("d") || spec.endsWith("f");
        }
    }

    // ------------------------------------------------------------------ resolving the expression

    private static final class Unresolved extends RuntimeException {
        Unresolved(String why) {
            super(why, null, false, false);
        }
    }

    private static final class Scope {
        final Map<String, String> binds = new HashMap<>();
        final Map<String, String> locals = new HashMap<>();
        final Scope parent;

        Scope(Scope parent) {
            this.parent = parent;
        }
    }

    private static final Pattern PAD_CALL = Pattern.compile("^(?:[\\w$]+\\.)*(padLeft|leftPad|padRight|rightPad)\\(");
    private static final Pattern TEXT_CALL = Pattern.compile("^(?:[\\w$]+\\.)*(?:String|Long|Integer|Double|BigDecimal|Objects)\\.(?:valueOf|toString)\\(");
    private static final Pattern FORMAT_CALL = Pattern.compile("^(?:[\\w$]+\\.)*String\\.format\\(");
    private static final Pattern GETTER = Pattern.compile("^[A-Za-z_][\\w$]*(?:\\(\\))?(?:\\.[A-Za-z_][\\w$]*(?:\\(\\))?)*$");
    private static final Pattern IDENT = Pattern.compile("^[A-Za-z_][\\w$]*$");
    private static final Pattern CALL = Pattern.compile("^((?:[\\w$]+\\.)*)([A-Za-z_][\\w$]*)\\(");

    private static final class Resolver {
        final DocContext c;
        final List<Op> ops = new ArrayList<>();
        String input;
        int depth;

        Resolver(DocContext c) {
            this.c = c;
        }

        void resolve(String expression, Scope scope) {
            if (++depth > 24) {
                throw new Unresolved("too deep");
            }
            String s = strip(expression);
            Matcher pad = PAD_CALL.matcher(s);
            if (pad.find() && closesAtEnd(s, pad.end() - 1)) {
                List<String> args = split(s.substring(pad.end(), s.length() - 1));
                if (args.size() < 2) {
                    throw new Unresolved("pad arguments");
                }
                resolve(args.get(0), scope);
                boolean left = pad.group(1).equals("padLeft") || pad.group(1).equals("leftPad");
                ops.add(new Pad(left, integer(args.get(1), scope), args.size() > 2 ? character(args.get(2), scope) : ' '));
                return;
            }
            Matcher text = TEXT_CALL.matcher(s);
            if (text.find() && closesAtEnd(s, text.end() - 1)) {
                List<String> args = split(s.substring(text.end(), s.length() - 1));
                resolve(args.get(0), scope);
                ops.add(new Text());
                return;
            }
            Matcher fmt = FORMAT_CALL.matcher(s);
            if (fmt.find() && closesAtEnd(s, fmt.end() - 1)) {
                List<String> args = split(s.substring(fmt.end(), s.length() - 1));
                String pattern = args.get(0).trim();
                Matcher spec = Pattern.compile("^\"(%-?0?\\d*(?:\\.\\d+)?[dsf])\"$").matcher(pattern);
                if (args.size() != 2 || !spec.matches()) {
                    throw new Unresolved("format pattern");
                }
                resolve(args.get(1), scope);
                ops.add(new Format(spec.group(1)));
                return;
            }
            if (s.endsWith(")")) {
                int open = matchingOpen(s);
                int dot = open < 0 ? -1 : s.lastIndexOf('.', open);
                if (open > 0 && dot > 0 && s.substring(dot + 1, open).matches("[A-Za-z_]\\w*")) {
                    String name = s.substring(dot + 1, open);
                    List<String> args = split(s.substring(open + 1, s.length() - 1));
                    Op op = chainOp(name, args, scope);
                    if (op != null) {
                        resolve(s.substring(0, dot), scope);
                        ops.add(op);
                        return;
                    }
                }
            }
            if (IDENT.matcher(s).matches()) {
                if (scope.locals.containsKey(s)) {
                    resolve(scope.locals.get(s), scope);
                } else if (scope.binds.containsKey(s) && scope.parent != null) {
                    resolve(scope.binds.get(s), scope.parent);
                } else {
                    setInput(s);
                }
                return;
            }
            Matcher call = CALL.matcher(s);
            if (call.find() && closesAtEnd(s, call.end() - 1)) {
                List<String> args = split(s.substring(call.end(), s.length() - 1));
                Scope inner = helper(call.group(2), args, scope);
                if (inner != null) {
                    resolve(returnOf(inner), inner);
                    return;
                }
            }
            if (GETTER.matcher(s).matches()) {
                setInput(s);
                return;
            }
            throw new Unresolved(s);
        }

        private void setInput(String s) {
            if (input != null && !input.equals(s)) {
                throw new Unresolved("more than one input");
            }
            input = s;
        }

        private Op chainOp(String name, List<String> args, Scope scope) {
            return switch (name) {
                case "movePointRight" -> new MovePoint(integer(args.get(0), scope));
                case "movePointLeft" -> new MovePoint(-integer(args.get(0), scope));
                case "setScale" -> new Scale(integer(args.get(0), scope));
                case "longValue", "intValue", "toBigInteger" -> new Whole();
                case "abs" -> new Abs();
                case "trim", "strip" -> new Trim();
                case "toUpperCase" -> new Case(true);
                case "toLowerCase" -> new Case(false);
                case "toString", "toPlainString" -> new Text();
                default -> null;
            };
        }

        private int integer(String expression, Scope scope) {
            String s = strip(expression);
            if (s.matches("-?\\d+")) {
                return Integer.parseInt(s);
            }
            if (IDENT.matcher(s).matches() && scope.binds.containsKey(s) && scope.parent != null) {
                return integer(scope.binds.get(s), scope.parent);
            }
            if (IDENT.matcher(s).matches() && scope.locals.containsKey(s)) {
                return integer(scope.locals.get(s), scope);
            }
            throw new Unresolved("number " + s);
        }

        private char character(String expression, Scope scope) {
            String s = strip(expression);
            Matcher m = Pattern.compile("^['\"](.)['\"]$").matcher(s);
            if (m.matches()) {
                return m.group(1).charAt(0);
            }
            if (IDENT.matcher(s).matches() && scope.binds.containsKey(s) && scope.parent != null) {
                return character(scope.binds.get(s), scope.parent);
            }
            throw new Unresolved("character " + s);
        }

        /** The analysed method called {@code name} with this many arguments, with its parameters bound to the arguments. */
        private Scope helper(String name, List<String> args, Scope caller) {
            for (Entity m : c.of(EntityType.METHOD)) {
                String params = c.attr(m, "parameters");
                if (!name.equals(m.name()) || params == null) {
                    continue;
                }
                List<String> names = new ArrayList<>();
                for (String p : params.split(",")) {
                    if (!p.isBlank()) {
                        String[] t = p.trim().split("\\s+");
                        names.add(t[t.length - 1]);
                    }
                }
                String key = m.id().value().substring("method:".length());
                if (names.size() != args.size() || bodyOf(key).isEmpty()) {
                    continue;
                }
                Scope s = new Scope(caller);
                for (int i = 0; i < names.size(); i++) {
                    s.binds.put(names.get(i), args.get(i));
                }
                for (Entity e : c.of(EntityType.EXPRESSION)) {
                    if (e.id().value().startsWith("expression:" + key + "@") && c.attr(e, "target") != null && c.attr(e, "text") != null) {
                        s.locals.put(c.attr(e, "target").replaceAll("^this\\.", ""), c.attr(e, "text"));
                    }
                }
                s.locals.put("$method", key);
                return s;
            }
            return null;
        }

        private List<Entity> bodyOf(String key) {
            return c.of(EntityType.INVOCATION).stream().filter(i -> i.id().value().startsWith("invocation:" + key + "@")).toList();
        }

        /** The expression the helper returns: its longest call that is not the right-hand side of a local variable. */
        private String returnOf(Scope helper) {
            String key = helper.locals.get("$method");
            helper.locals.remove("$method");
            return bodyOf(key).stream().map(i -> c.attr(i, "text")).filter(t -> t != null && !helper.locals.containsValue(t))
                    .max(Comparator.comparingInt(String::length)).orElseThrow(() -> new Unresolved("no return in " + key));
        }
    }

    private static String strip(String s) {
        String t = s.trim();
        while (t.startsWith("(") && t.endsWith(")") && matchingOpen(t) == 0) {
            t = t.substring(1, t.length() - 1).trim();
        }
        return t.replaceAll("^\\((?:int|long|double|String)\\)\\s*", "");
    }

    /** True when the bracket that opens at {@code open} is closed by the last character. */
    private static boolean closesAtEnd(String s, int open) {
        int level = 0;
        boolean quote = false;
        char q = 0;
        for (int i = open; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (quote) {
                quote = !(ch == q && s.charAt(i - 1) != '\\');
            } else if (ch == '"' || ch == '\'') {
                quote = true;
                q = ch;
            } else if (ch == '(') {
                level++;
            } else if (ch == ')' && --level == 0) {
                return i == s.length() - 1;
            }
        }
        return false;
    }

    /** The index of the bracket that matches the final ')'. */
    private static int matchingOpen(String s) {
        int level = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            char ch = s.charAt(i);
            if (ch == ')') {
                level++;
            } else if (ch == '(' && --level == 0) {
                return i;
            }
        }
        return -1;
    }

    static List<String> split(String args) {
        List<String> out = new ArrayList<>();
        int level = 0;
        int from = 0;
        boolean quote = false;
        char q = 0;
        for (int i = 0; i < args.length(); i++) {
            char ch = args.charAt(i);
            if (quote) {
                quote = !(ch == q && args.charAt(i - 1) != '\\');
            } else if (ch == '"' || ch == '\'') {
                quote = true;
                q = ch;
            } else if (ch == '(' || ch == '[' || ch == '{') {
                level++;
            } else if (ch == ')' || ch == ']' || ch == '}') {
                level--;
            } else if (ch == ',' && level == 0) {
                out.add(args.substring(from, i).trim());
                from = i + 1;
            }
        }
        if (!args.substring(from).isBlank()) {
            out.add(args.substring(from).trim());
        }
        return out;
    }

    // ------------------------------------------------------------------ examples and the width check

    private static final Pattern DECIMAL = Pattern.compile("(?i)(?:DECIMAL|NUMERIC|NUMBER)\\s*\\((\\d+)(?:\\s*,\\s*(\\d+))?\\)");
    private static final Pattern TEXT_TYPE = Pattern.compile("(?i)(?:VARCHAR2?|CHAR|NVARCHAR|NCHAR)\\s*\\((\\d+)\\)");

    /**
     * The formatting of one field, expanded. {@code columnType} is the data type of the column feeding the field (or null), used to pick
     * values the column can hold and to find the largest one.
     */
    static Result of(DocContext c, Humanizer h, Entity field, String columnType) {
        String expression = c.attr(field, "text");
        if (expression == null || expression.isBlank()) {
            return Result.none();
        }
        Resolver r = new Resolver(c);
        try {
            r.resolve(expression, new Scope(null));
        } catch (Unresolved | IndexOutOfBoundsException | NumberFormatException e) {
            return Result.none();
        }
        if (r.ops.isEmpty() || r.input == null) {
            return Result.none();
        }
        List<Op> ops = r.ops;
        Pad lastPad = null;
        for (Op op : ops) {
            if (op instanceof Pad p) {
                lastPad = p;
            }
        }
        boolean numeric = ops.get(0).numeric() || columnType != null && (DECIMAL.matcher(columnType).find() || columnType.toUpperCase().matches(".*\\b(INT|INTEGER|BIGINT|SMALLINT|LONG|NUMBER|FLOAT|DOUBLE)\\b.*"));
        if (ops.get(0).numeric()) {
            numeric = true;
        }

        List<String> steps = new ArrayList<>();
        steps.add("Take the value of **" + h.words(field.name()) + "** as calculated above");
        ops.forEach(op -> steps.add(op.words()));

        // values to work through
        int precision = 10;
        int scale = 0;
        Integer textLength = null;
        if (columnType != null) {
            Matcher d = DECIMAL.matcher(columnType);
            Matcher t = TEXT_TYPE.matcher(columnType);
            if (d.find()) {
                precision = Integer.parseInt(d.group(1));
                scale = d.group(2) == null ? 0 : Integer.parseInt(d.group(2));
            } else if (t.find()) {
                textLength = Integer.parseInt(t.group(1));
            } else if (columnType.toUpperCase().contains("BIGINT")) {
                precision = 18;
            }
        }
        List<Object> inputs = new ArrayList<>();
        Object largest;
        if (numeric) {
            BigDecimal typical = new BigDecimal("1234.56").setScale(scale, RoundingMode.DOWN);
            BigDecimal small = BigDecimal.valueOf(scale == 0 ? 7 : 5).setScale(scale, RoundingMode.DOWN);
            BigDecimal max = new BigDecimal(BigInteger.TEN.pow(precision).subtract(BigInteger.ONE)).movePointLeft(scale);
            inputs.add(small);
            inputs.add(typical.precision() <= precision ? typical : small);
            inputs.add(max);
            largest = max;
        } else {
            int n = textLength == null ? 12 : textLength;
            inputs.add("AB");
            inputs.add("SAMPLE".substring(0, Math.min(6, Math.max(1, n))));
            String max = "X".repeat(n);
            if (textLength != null) {
                inputs.add(max);
            }
            largest = max;
        }
        int width = intAttr(c, field, "width");
        List<List<String>> examples = new ArrayList<>();
        List<String> exampleFacts = new ArrayList<>();
        int largestLength = 0;
        try {
            for (Object in : inputs) {
                Object v = in;
                List<String> trail = new ArrayList<>();
                for (Op op : ops) {
                    v = op.apply(v);
                    trail.add(v instanceof BigDecimal b ? b.toPlainString() : "\"" + v + "\"");
                }
                String out = String.valueOf(v);
                if (in == largest) {
                    largestLength = out.length();
                }
                exampleFacts.add("Example (illustrative, worked through the formatting steps, not real data): the value "
                        + (in instanceof BigDecimal b0 ? b0.toPlainString() : "\"" + in + "\"") + " is written to the file as " + out + ", " + out.length() + " characters.");
                examples.add(List.of(in instanceof BigDecimal b ? b.toPlainString() : "\"" + in + "\"", String.join(" → ", trail),
                        "`" + out + "`", out.length() + (width > 0 && out.length() > width ? " (**more than " + width + "**)" : "")));
            }
        } catch (RuntimeException e) {
            return new Result(true, steps, List.of(), layoutOf(lastPad), null, false, List.of());
        }

        String check;
        boolean problem = false;
        if (columnType == null || (!numeric && textLength == null) || (numeric && !DECIMAL.matcher(columnType).find() && !columnType.toUpperCase().contains("INT"))) {
            check = "Not checked: the size of the source column is not known, so the longest possible value cannot be tested against the field width.";
        } else if (width > 0 && largestLength > width) {
            problem = true;
            check = "**Can overflow:** the largest value the column allows (" + (largest instanceof BigDecimal b ? b.toPlainString() : "\"" + largest + "\"")
                    + ") is written as " + largestLength + " characters, but the field is " + width + " wide. No step here shortens it, so it would push the next field out of position.";
        } else if (lastPad != null && width > 0 && lastPad.width() != width) {
            problem = true;
            check = "**Width differs:** the padding step makes the text " + lastPad.width() + " characters long, but the field is " + width + " wide.";
        } else {
            check = "Fits: the largest value the column allows is written as " + largestLength + " characters"
                    + (width > 0 ? ", within the " + width + " available." : ".");
        }
        if (problem == false && lastPad != null && width > 0 && lastPad.width() != width) {
            problem = true;
            check += " **Width differs:** the padding step makes the text " + lastPad.width() + " characters long, but the field is " + width + " wide.";
        }
        return new Result(true, steps, examples, layoutOf(lastPad), check, problem, exampleFacts);
    }

    private static String layoutOf(Pad p) {
        return p == null ? null : (p.left() ? "right-aligned, " : "left-aligned, ") + "padded with " + (p.ch() == ' ' ? "spaces" : "'" + p.ch() + "'")
                + (p.left() ? " on the left" : " on the right") + " to " + p.width() + " characters";
    }

    private static int intAttr(DocContext c, Entity e, String name) {
        try {
            return Integer.parseInt(c.attr(e, name));
        } catch (RuntimeException ex) {
            return -1;
        }
    }
}
