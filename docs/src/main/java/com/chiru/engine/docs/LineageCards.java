package com.chiru.engine.docs;

import com.chiru.engine.model.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.stream.Collectors;

/**
 * One lineage card per output field and kind of work: where the value comes from (table, column, data type, the query that reads
 * it), the calculations in the order they happen with named intermediate values, the values that are chosen by a condition and what decides
 * them, and how the value is formatted before it is written. It is built from the ordered steps of each trace ({@code stepIds}), not from
 * a bag of rules, so a reader can follow the value from the database to the file.
 *
 * <p>A column that is only read by a condition (for example {@code newQuantity == 0} choosing between "SENT" and "PARTIAL") is shown as
 * "decided by", not as a source of the value: the value itself is the constant that the condition selects.
 */
final class LineageCards {

    private LineageCards() {}

    record Source(String key, String table, String column, String type, String query) {}

    record Step(String id, String name, String formula, String when) {}

    record Card(String field, String scenario, List<Source> sources, List<Source> deciders, List<String> chosen, List<Step> steps,
                List<Step> deciderSteps, List<Step> formatting, List<String> unknown, List<String> fixed,
                Map<String, List<String>> varies) {

        boolean isEmpty() {
            return sources.isEmpty() && deciders.isEmpty() && chosen.isEmpty() && steps.isEmpty() && unknown.isEmpty() && fixed.isEmpty();
        }

        /** How the value comes about, in one word for the summary table. */
        String how() {
            if (!unknown.isEmpty()) {
                return "not traceable";
            }
            if (!chosen.isEmpty() || !deciders.isEmpty()) {
                return "chosen by condition";
            }
            if (!steps.isEmpty()) {
                return "calculated";
            }
            if (!sources.isEmpty()) {
                return "copied";
            }
            return "fixed value";
        }
    }

    private static final Pattern LITERAL_ONLY = Pattern.compile("^(\".*\"|'.*'|-?\\d+(\\.\\d+)?|(?:[\\w.]*\\.)?[A-Z][A-Z0-9_]*)$");
    private static final Pattern TRIVIAL = Pattern.compile("^[\\w.]+$");

    private enum Role { SOURCE, DECIDES, FIXED, UNKNOWN }

    /** The cards of a field, one per kind of work, in the order of the scenarios. */
    static List<Card> build(DocContext c, Entity field, Set<String> formattingPrefixes, Map<String, String> scenarioLabels) {
        Humanizer h = c.humanizer;
        Map<String, List<Entity>> byScenario = new TreeMap<>();
        for (Map.Entry<Entity, TraceStatus> e : c.traces(field)) {
            String name = Optional.ofNullable(c.attr(e.getKey(), "scenario")).orElse("(all)");
            byScenario.computeIfAbsent(scenarioLabels.getOrDefault(name, name), k -> new ArrayList<>()).add(e.getKey());
        }
        List<Card> cards = new ArrayList<>();
        for (Map.Entry<String, List<Entity>> s : byScenario.entrySet()) {
            Map<String, Source> sources = new LinkedHashMap<>();
            Map<String, Source> deciders = new LinkedHashMap<>();
            LinkedHashSet<String> chosen = new LinkedHashSet<>();
            LinkedHashSet<String> fixed = new LinkedHashSet<>();
            Map<String, Set<String>> fixedWhen = new LinkedHashMap<>();
            LinkedHashSet<String> unknown = new LinkedHashSet<>();
            Map<String, Step> steps = new LinkedHashMap<>();
            Map<String, Step> deciderSteps = new LinkedHashMap<>();
            Map<String, Step> formatting = new LinkedHashMap<>();
            Map<String, Set<String>> varies = new TreeMap<>();
            List<Entity> traces = new ArrayList<>(s.getValue());
            traces.sort(Comparator.comparingInt((Entity t) -> -ids(c, t).size()));        // the longest trace first: it holds the most steps
            for (Entity t : traces) {
                List<Entity> statements = new ArrayList<>();
                for (String id : ids(c, t)) {
                    Optional<Entity> e = c.model.entity(new EntityId(id));
                    if (e.isPresent() && e.get().type() == EntityType.EXPRESSION && isAssignment(c, e.get())) {
                        statements.add(e.get());
                    }
                }
                Collections.reverse(statements);                                        // source first, output last
                Role role = roleOf(c, t, statements, formattingPrefixes);
                collectVaries(c, h, t, statements, formattingPrefixes, varies);
                for (Entity st : statements) {
                    Step step = step(c, h, st);
                    if (isFormatting(st, formattingPrefixes)) {
                        if (!c.attr(st, "text").trim().startsWith("new ")) {
                            formatting.putIfAbsent(st.id().value(), step);
                        }
                    } else if (isChoice(c, st)) {
                        List<String[]> picks = ternaryPicks(c.attr(st, "text"));
                        if (picks != null) {
                            for (String[] p : picks) {
                                chosen.add(h.value(p[0]) + (p[1] == null ? " otherwise" : " when " + h.condition(p[1])));
                            }
                        } else {
                            chosen.add(h.value(c.attr(st, "text")) + (step.when().isEmpty() ? "" : " when " + step.when()));
                        }
                    } else if (role == Role.SOURCE && !TRIVIAL.matcher(c.attr(st, "text")).matches()) {
                        steps.putIfAbsent(st.id().value(), step);
                    } else if (role == Role.DECIDES && !TRIVIAL.matcher(c.attr(st, "text")).matches()) {
                        deciderSteps.putIfAbsent(st.id().value(), step);
                    }
                }
                String kind = c.attr(t, "terminalKind");
                String terminal = c.attr(t, "terminal");
                switch (role) {
                    case SOURCE -> {
                        Source src = source(c, h, c.attr(t, "terminalId"), terminal);
                        sources.putIfAbsent(src.key(), src);
                    }
                    case DECIDES -> {
                        Source src = source(c, h, c.attr(t, "terminalId"), terminal);
                        deciders.putIfAbsent(src.key(), src);
                    }
                    case FIXED -> fixedWhen.computeIfAbsent(h.value(terminal), k -> new LinkedHashSet<>()).add(conditionSuffix(c, h, t, formattingPrefixes));
                    default -> unknown.add(("runtime input".equals(kind) || "entry input".equals(kind) ? "supplied when the job runs: "
                            : "external call".equals(kind) ? "comes from outside the analysed code: " : "could not be traced: ") + h.value(terminal));
                }
            }
            if (steps.isEmpty() && chosen.isEmpty() && deciders.isEmpty() && fixedWhen.isEmpty()) {
                varies.clear();      // a plain copy: the conditions decide whether the record is written, not what this field holds
            }
            fixedWhen.forEach((value, whens) -> fixed.add(value + (whens.size() == 1 ? whens.iterator().next() : "")));
            Card card = new Card(field.name(), s.getKey(), new ArrayList<>(sources.values()), new ArrayList<>(deciders.values()),
                    new ArrayList<>(chosen), execOrder(c, steps.values()), execOrder(c, deciderSteps.values()), execOrder(c, formatting.values()),
                    new ArrayList<>(unknown), new ArrayList<>(fixed),
                    varies.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, x -> new ArrayList<>(x.getValue()), (a, b) -> a, LinkedHashMap::new)));
            if (!card.isEmpty()) {
                cards.add(card);
            }
        }
        return cards;
    }

    /**
     * Steps in the order they happen: inside a method by line, and a method called by a step runs just before that step, so the callee's
     * calculation is listed before the statement that uses its result.
     */
    private static List<Step> execOrder(DocContext c, Collection<Step> steps) {
        Map<String, List<Step>> byMethod = new LinkedHashMap<>();
        for (Step s : steps) {
            byMethod.computeIfAbsent(methodPart(s.id()), k -> new ArrayList<>()).add(s);
        }
        byMethod.values().forEach(l -> l.sort(Comparator.comparingInt((Step s) -> position(s.id(), 1)).thenComparingInt(s -> position(s.id(), 2))));
        Set<String> called = new HashSet<>();
        Map<String, Set<String>> callees = new HashMap<>();
        for (Step s : steps) {
            Set<String> set = callees.computeIfAbsent(s.id(), k -> new LinkedHashSet<>());
            c.model.entity(new EntityId(s.id())).ifPresent(e -> {
                for (Relationship contains : c.index.out(e.id(), RelationType.DERIVED_FROM)) {
                    if (contains.to().type() == EntityType.INVOCATION) {
                        for (Relationship inv : c.index.out(contains.to(), RelationType.INVOKES)) {
                            String m = ModelIndex.key(inv.to());
                            if (byMethod.containsKey(m) && !m.equals(methodPart(s.id()))) {
                                set.add(m);
                                called.add(m);
                            }
                        }
                    }
                }
            });
        }
        List<Step> out = new ArrayList<>();
        Set<String> done = new HashSet<>();
        for (String m : byMethod.keySet()) {
            if (!called.contains(m)) {
                emit(m, byMethod, callees, done, out);
            }
        }
        for (String m : byMethod.keySet()) {
            emit(m, byMethod, callees, done, out);
        }
        return out;
    }

    private static void emit(String method, Map<String, List<Step>> byMethod, Map<String, Set<String>> callees, Set<String> done, List<Step> out) {
        if (!done.add(method)) {
            return;
        }
        for (Step s : byMethod.get(method)) {
            for (String callee : callees.getOrDefault(s.id(), Set.of())) {
                emit(callee, byMethod, callees, done, out);
            }
            out.add(s);
        }
    }

    private static String methodPart(String id) {
        return id.substring(id.indexOf(':') + 1, Math.max(id.indexOf(':') + 1, id.lastIndexOf('@')));
    }

    private static int position(String id, int group) {
        Matcher m = Pattern.compile("@(\\d+):(\\d+)").matcher(id.substring(Math.max(0, id.lastIndexOf('@'))));
        return m.find() ? Integer.parseInt(m.group(group)) : 0;
    }

    private static List<String> ids(DocContext c, Entity trace) {
        String raw = c.attr(trace, "stepIds");
        return raw == null || raw.isEmpty() ? List.of() : Arrays.asList(raw.split(" ;; "));
    }

    private static boolean isAssignment(DocContext c, Entity e) {
        String kind = c.attr(e, "kind");
        return ("init".equals(kind) || "assign".equals(kind) || "compound".equals(kind)) && c.attr(e, "target") != null && c.attr(e, "text") != null;
    }

    private static boolean isFormatting(Entity e, Set<String> prefixes) {
        String key = ModelIndex.key(e.id());
        return prefixes.stream().anyMatch(p -> key.startsWith(p));
    }

    /** A statement that picks a constant, under a condition: {@code status = "SENT"} inside an if. */
    private static boolean isChoice(DocContext c, Entity statement) {
        String text = c.attr(statement, "text").trim();
        return (LITERAL_ONLY.matcher(text).matches() && !conditionsOf(c, statement).isEmpty()) || ternaryPicks(text) != null;
    }

    /** {@code cond ? "A" : "B"} with two literal branches as (value, condition) pairs; the condition of the last is null ("otherwise"). Null if it is not that shape. */
    static List<String[]> ternaryPicks(String text) {
        int q = -1;
        int colon = -1;
        int depth = 0;
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '"') {
                quoted = !quoted;
            } else if (!quoted && (ch == '(' || ch == '[')) {
                depth++;
            } else if (!quoted && (ch == ')' || ch == ']')) {
                depth--;
            } else if (!quoted && depth == 0 && ch == '?' && q < 0) {
                q = i;
            } else if (!quoted && depth == 0 && ch == ':' && q > 0 && colon < 0) {
                colon = i;
            }
        }
        if (q < 0 || colon < 0) {
            return null;
        }
        String cond = text.substring(0, q).trim();
        String yes = text.substring(q + 1, colon).trim();
        String no = text.substring(colon + 1).trim();
        if (!LITERAL_ONLY.matcher(yes).matches() || !LITERAL_ONLY.matcher(no).matches()) {
            return null;
        }
        return List.of(new String[] {yes, cond}, new String[] {no, null});
    }

    private static final Pattern OPERAND = Pattern.compile("[A-Za-z_]\\w*(?:\\.[A-Za-z_]\\w*(?:\\(\\))?)*");
    private static final Set<String> NOT_ATTRIBUTE = Set.of("equals", "equalsIgnoreCase", "compareTo", "signum", "isBefore", "isAfter",
            "contains", "startsWith", "endsWith", "isEmpty", "length", "intValue", "not", "null", "true", "false", "this", "new", "instanceof",
            "get", "of", "valueOf", "trim", "toString");

    /**
     * Records which attributes the conditions on a trace test (for example "transaction type", "zone"), with the humanised condition. Zone, event
     * and code choices all appear here, because they are conditions on the path to the field.
     */
    private static void collectVaries(DocContext c, Humanizer h, Entity trace, List<Entity> statements, Set<String> formattingPrefixes,
                                      Map<String, Set<String>> varies) {
        Set<String> raw = new LinkedHashSet<>();
        String rules = c.attr(trace, "rules");
        if (rules != null) {
            for (String id : rules.split(", ")) {
                c.model.entity(new EntityId(id)).ifPresent(r -> {
                    String method = c.attr(r, "method");
                    boolean formatting = method != null && formattingPrefixes.stream().anyMatch(method.substring(Math.min(7, method.length()))::startsWith);
                    if (!formatting && c.attr(r, "conditions") != null) {
                        raw.add(c.attr(r, "conditions"));
                    }
                });
            }
        }
        for (Entity st : statements) {
            if (!isFormatting(st, formattingPrefixes)) {
                raw.addAll(conditionsOf(c, st));
            }
        }
        for (String condition : raw) {
            String words = h.condition(condition);
            for (String atom : atomsOf(condition, 0)) {
                String attribute = attributeOf(h, atom);
                if (attribute != null) {
                    varies.computeIfAbsent(attribute, k -> new LinkedHashSet<>()).add(words);
                }
            }
        }
    }

    /** The simple tests inside a condition: split at AND / OR outside brackets and quotes, looking through {@code not ( ... )} and outer brackets. */
    static List<String> atomsOf(String condition, int depth) {
        String s = condition.trim();
        String inner = s.startsWith("not ") ? s.substring(4).trim() : s.startsWith("!") ? s.substring(1).trim() : s;
        if (inner.startsWith("(") && inner.endsWith(")") && matchingClose(inner) == inner.length() - 1) {
            inner = inner.substring(1, inner.length() - 1);
        }
        List<String> parts = new ArrayList<>();
        int level = 0;
        int from = 0;
        boolean quote = false;
        for (int i = 0; i < inner.length(); i++) {
            char ch = inner.charAt(i);
            if (ch == '"' && (i == 0 || inner.charAt(i - 1) != '\\')) {
                quote = !quote;
            } else if (!quote && ch == '(') {
                level++;
            } else if (!quote && ch == ')') {
                level--;
            } else if (!quote && level == 0 && ch == ' ') {
                for (String sep : List.of(" AND ", " OR ", " && ", " || ")) {
                    if (inner.startsWith(sep, i)) {
                        parts.add(inner.substring(from, i));
                        from = i + sep.length();
                        i = from - 1;
                        break;
                    }
                }
            }
        }
        parts.add(inner.substring(from));
        if (parts.size() == 1 || depth > 6) {
            return List.of(inner.trim());
        }
        List<String> out = new ArrayList<>();
        parts.forEach(p -> out.addAll(atomsOf(p, depth + 1)));
        return out;
    }

    private static int matchingClose(String s) {
        int level = 0;
        boolean quote = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '"' && (i == 0 || s.charAt(i - 1) != '\\')) {
                quote = !quote;
            } else if (!quote && ch == '(') {
                level++;
            } else if (!quote && ch == ')' && --level == 0) {
                return i;
            }
        }
        return -1;
    }

    /** The thing a condition tests, in words: {@code txn.getType().equals("A")} gives "type". Constants and literals are not attributes. */
    private static String attributeOf(Humanizer h, String atom) {
        java.util.regex.Matcher m = OPERAND.matcher(atom.replaceAll("\"[^\"]*\"", "\"\"").replaceAll("^\\s*not\\s*\\(|^\\s*!\\s*\\(", "("));
        while (m.find()) {
            String[] parts = m.group().replace("()", "").split("\\.");
            for (int i = parts.length - 1; i >= 0; i--) {
                String p = parts[i];
                if (NOT_ATTRIBUTE.contains(p) || p.equals(p.toUpperCase())) {
                    continue;
                }
                String bare = p.replaceFirst("^(get|is|has)(?=[A-Z])", "");
                if (!bare.isEmpty()) {
                    return h.words(Character.toLowerCase(bare.charAt(0)) + bare.substring(1));
                }
            }
        }
        return null;
    }

    private static List<String> conditionsOf(DocContext c, Entity expression) {
        List<String> out = new ArrayList<>();
        for (Relationship r : c.index.in(expression.id(), RelationType.DERIVED_FROM)) {
            if (r.from().type() == EntityType.BUSINESS_RULE) {
                c.model.entity(r.from()).map(rule -> c.attr(rule, "conditions")).filter(x -> x != null && !x.isBlank()).ifPresent(out::add);
            }
        }
        return out;
    }

    private static Step step(DocContext c, Humanizer h, Entity st) {
        String target = c.attr(st, "target");
        String text = c.attr(st, "text");
        String name = h.words(target.replaceAll("^this\\.", ""));
        String formula = "compound".equals(c.attr(st, "kind")) ? h.effect(target + " (compound) " + text) : h.value(text);
        List<String> raw = conditionsOf(c, st).stream().distinct().toList();
        boolean opposite = raw.stream().anyMatch(x -> raw.contains("not (" + x + ")"));
        String when = opposite ? "" : raw.stream().map(h::condition).collect(Collectors.joining(" and "));
        return new Step(st.id().value(), name, formula, when);
    }

    /** SOURCE when the column feeds the value; DECIDES when the only statement next to the output chooses a constant; FIXED, UNKNOWN otherwise. */
    private static Role roleOf(DocContext c, Entity trace, List<Entity> statements, Set<String> formattingPrefixes) {
        String kind = c.attr(trace, "terminalKind");
        if ("constant".equals(kind)) {
            return Role.FIXED;
        }
        if (!"column".equals(kind)) {
            return Role.UNKNOWN;
        }
        List<Entity> business = statements.stream().filter(s -> !isFormatting(s, formattingPrefixes) && !TRIVIAL.matcher(c.attr(s, "text")).matches()).toList();
        return !business.isEmpty() && isChoice(c, business.get(business.size() - 1)) ? Role.DECIDES : Role.SOURCE;
    }

    /** True for a calculation (not a copy and not a constant): it combines values, so the column may feed it. */
    private static boolean referencesColumnPath(DocContext c, Entity statement) {
        return c.attr(statement, "operators") != null || !LITERAL_ONLY.matcher(c.attr(statement, "text").trim()).matches();
    }

    private static Source source(DocContext c, Humanizer h, String columnId, String text) {
        String key = columnId == null ? text : ModelIndex.key(new EntityId(columnId));
        int dot = key.indexOf('.');
        String table = dot < 0 ? "" : key.substring(0, dot);
        String column = dot < 0 ? key : key.substring(dot + 1);
        String type = columnId == null ? null : c.model.entity(new EntityId(columnId)).map(e -> c.attr(e, "type")).orElse(null);
        return new Source(key, table, column, type, queryOf(c, h, table, column));
    }

    /** The SELECT that reads the column, in words. */
    private static String queryOf(DocContext c, Humanizer h, String table, String column) {
        for (Entity s : c.of(EntityType.SQL_STATEMENT)) {
            String text = Optional.ofNullable(c.attr(s, "text")).orElse("");
            Matcher m = Pattern.compile("(?is)^SELECT\\s+(.*?)\\s+FROM\\s+(\\w+)").matcher(text);
            if ("SELECT".equals(c.attr(s, "type")) && m.find() && m.group(2).equalsIgnoreCase(table)
                    && Pattern.compile("(?i)\\b" + Pattern.quote(column) + "\\b").matcher(m.group(1)).find()) {
                return "Records read from the " + h.words(table) + " table where " + FunctionalDocs.filterText(c.attr(s, "predicates"), h);
            }
        }
        return null;
    }

    /** The conditions the rules on a trace apply under; a condition and its opposite together mean "always". */
    private static String conditionSuffix(DocContext c, Humanizer h, Entity trace, Set<String> formattingPrefixes) {
        String rules = c.attr(trace, "rules");
        if (rules == null) {
            return "";
        }
        Set<String> raw = new LinkedHashSet<>();
        for (String id : rules.split(", ")) {
            c.model.entity(new EntityId(id)).ifPresent(r -> {
                String method = c.attr(r, "method");
                boolean formatting = method != null && formattingPrefixes.stream().anyMatch(method.substring(Math.min(7, method.length()))::startsWith);
                if (!formatting && c.attr(r, "conditions") != null) {
                    raw.add(c.attr(r, "conditions"));
                }
            });
        }
        boolean opposite = raw.stream().anyMatch(x -> raw.contains("not (" + x + ")"));
        return raw.isEmpty() || opposite ? "" : " when " + raw.stream().map(h::condition).collect(Collectors.joining(" and "));
    }

    // ---------------------------------------------------------------- layout

    private static final Pattern PAD = Pattern.compile("pad(Left|Right)\\((?:.*?),\\s*(\\d+)(?:,\\s*'(.)')?\\)", Pattern.DOTALL);

    /** How the value is placed in the line, from the layout expression: alignment, width and padding as far as the code states them. */
    static String layout(DocContext c, Entity field) {
        String text = c.attr(field, "text");
        if (text == null) {
            return null;
        }
        Matcher m = PAD.matcher(text);
        if (m.find()) {
            boolean left = m.group(1).equals("Left");
            String pad = m.group(3) == null ? "padded" : "padded with '" + m.group(3) + "'";
            return (left ? "right-aligned, " : "left-aligned, ") + pad + " " + (left ? "on the left" : "on the right") + " to " + m.group(2) + " characters";
        }
        return null;
    }

    // ---------------------------------------------------------------- rendering

    static String render(Card card, Humanizer h) {
        StringBuilder sb = new StringBuilder("**").append(card.scenario()).append("**\n\n");
        List<String> queries = new ArrayList<>(new LinkedHashSet<>(Stream.concat(card.sources().stream(), card.deciders().stream())
                .map(Source::query).filter(Objects::nonNull).toList()));
        if (!card.sources().isEmpty()) {
            sb.append("- **Comes from:**\n");
            for (Source s : card.sources()) {
                sb.append("  - ").append(describe(s, h)).append('\n');
            }
        }
        if (!queries.isEmpty()) {
            sb.append("- **Read by:** ").append(String.join("; ", queries)).append('\n');
        }
        if (!card.steps().isEmpty()) {
            sb.append("- **Calculated as:**\n");
            numbered(sb, card.steps(), "  ");
            sb.append("  The field takes the value of **").append(card.steps().get(card.steps().size() - 1).name()).append("**.\n");
        }
        if (!card.chosen().isEmpty()) {
            sb.append("- **The value is chosen by a condition:** ").append(String.join("; ", card.chosen())).append('\n');
        }
        if (!card.deciders().isEmpty()) {
            sb.append("- **The choice depends on:** ").append(card.deciders().stream().map(s -> describe(s, h)).collect(Collectors.joining("; "))).append('\n');
            if (!card.deciderSteps().isEmpty()) {
                sb.append("  - the deciding value is calculated as:\n");
                numbered(sb, card.deciderSteps(), "    ");
            }
        }
        if (!card.fixed().isEmpty()) {
            sb.append("- **Fixed value:** ").append(String.join("; ", card.fixed())).append('\n');
        }
        for (String u : card.unknown()) {
            sb.append("- **Source not traceable:** ").append(u).append(" — **confirm with the business**\n");
        }
        if (!card.formatting().isEmpty()) {
            sb.append("- **Prepared for the file:** ").append(card.formatting().stream()
                    .map(st -> st.name() + " = " + st.formula()).collect(Collectors.joining("; "))).append('\n');
        }
        return sb.append('\n').toString();
    }

    private static void numbered(StringBuilder sb, List<Step> steps, String indent) {
        int n = 0;
        for (Step st : steps) {
            sb.append(indent).append(++n).append(". ").append(st.name()).append(" = ").append(st.formula())
                    .append(st.when().isEmpty() ? "" : " (only when " + st.when() + ")").append('\n');
        }
    }

    private static String describe(Source s, Humanizer h) {
        String words = s.table().isEmpty() ? s.key() : h.column(s.table(), s.column());
        StringBuilder sb = new StringBuilder(words);
        if (!s.table().isEmpty()) {
            sb.append(" — table ").append(Md.code(s.table())).append(", column ").append(Md.code(s.column()));
            if (s.type() != null) {
                sb.append(" (").append(s.type()).append(')');
            }
        }
        return sb.toString();
    }
}
