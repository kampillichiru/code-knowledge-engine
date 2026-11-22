package com.chiru.engine.docs;

import com.chiru.engine.model.Entity;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * F07: one section per output field with everything about it, and two indexes that make the trace work in both directions:
 * from a table column down to the fields it feeds, and from a field back to its columns. A third index lists which attributes (zone,
 * transaction type, operation code, ...) change which fields.
 */
final class FieldReference {

    private FieldReference() {}

    static String anchor(String field) {
        return "f-" + field.toLowerCase().replaceAll("[^a-z0-9]+", "-");
    }

    static String build(DocContext c, Humanizer h, String header, List<Entity> fields, Map<String, List<LineageCards.Card>> cards,
                        Function<Entity, String> fileWords) {
        StringBuilder sb = new StringBuilder(header);
        if (fields.isEmpty()) {
            return sb.append("_No fixed-width output field was found._\n").toString();
        }

        Map<String, FormatRecipe.Result> formats = new LinkedHashMap<>();
        for (Entity x : fields) {
            String type = cards.getOrDefault(x.name(), List.of()).stream().flatMap(card -> card.sources().stream()).map(LineageCards.Source::type)
                    .filter(java.util.Objects::nonNull).findFirst().orElse(null);
            formats.put(x.name(), FormatRecipe.of(c, h, x, type));
        }
        List<List<String>> problems = new ArrayList<>();
        for (Entity x : fields) {
            FormatRecipe.Result fr = formats.get(x.name());
            if (fr.widthProblem()) {
                problems.add(List.of("[" + h.words(x.name()) + "](#" + anchor(x.name()) + ")", c.attr(x, "width"), fr.widthCheck()));
            }
        }
        if (!problems.isEmpty()) {
            sb.append(Md.h(2, "Width problems"));
            sb.append("_Fields where the formatting can produce more characters than the field is wide, or pads to a different width._\n\n");
            sb.append(Md.table(List.of("Field", "Width", "Problem"), problems));
        }

        // 1. at a glance
        sb.append(Md.h(2, "Fields at a glance"));
        sb.append("_Click a field name to open everything known about it._\n\n");
        List<List<String>> glance = new ArrayList<>();
        Map<String, Set<String>> attributeToFields = new TreeMap<>();
        Map<String, Set<String>> attributeToConditions = new TreeMap<>();
        Map<String, Set<String>> columnToFields = new TreeMap<>();
        Map<String, String> columnType = new LinkedHashMap<>();
        for (Entity x : fields) {
            List<LineageCards.Card> fc = cards.getOrDefault(x.name(), List.of());
            Set<String> attrs = new LinkedHashSet<>();
            Set<String> cols = new LinkedHashSet<>();
            for (LineageCards.Card card : fc) {
                card.varies().forEach((a, conds) -> {
                    attrs.add(a);
                    attributeToFields.computeIfAbsent(a, k -> new LinkedHashSet<>()).add(h.words(x.name()));
                    attributeToConditions.computeIfAbsent(a, k -> new LinkedHashSet<>()).addAll(conds);
                });
                for (LineageCards.Source s : java.util.stream.Stream.concat(card.sources().stream(), card.deciders().stream()).toList()) {
                    cols.add(Md.code(s.key()));
                    columnToFields.computeIfAbsent(s.key(), k -> new LinkedHashSet<>())
                            .add("[" + h.words(x.name()) + "](#" + anchor(x.name()) + ")" + (fc.size() > 1 ? " (" + card.scenario() + ")" : ""));
                    if (s.type() != null) {
                        columnType.putIfAbsent(s.key(), s.type());
                    }
                }
            }
            glance.add(List.of("[" + h.words(x.name()) + "](#" + anchor(x.name()) + ")",
                    c.attr(x, "start") + "–" + c.attr(x, "end"),
                    fc.stream().map(LineageCards.Card::scenario).distinct().collect(Collectors.joining(", ")),
                    attrs.isEmpty() ? "nothing (same rule every time)" : String.join(", ", attrs),
                    cols.isEmpty() ? Md.NONE : String.join(", ", cols)));
        }
        sb.append(Md.table(List.of("Field", "Characters", "Kinds of work", "Changes with", "Source columns"), glance));

        // 2. what changes which field
        sb.append(Md.h(2, "What changes the value of a field"));
        if (attributeToFields.isEmpty()) {
            sb.append("_No field depends on a condition: every field is calculated the same way for every record._\n\n");
        } else {
            sb.append("_Each row is something the code tests (a zone, a transaction type, a code, a date, an amount) and the fields whose value changes because of it._\n\n");
            List<List<String>> rows = new ArrayList<>();
            attributeToFields.forEach((a, fs) -> rows.add(List.of(a, String.valueOf(fs.size()), String.join(", ", fs),
                    attributeToConditions.get(a).stream().limit(4).collect(Collectors.joining("; ")))));
            sb.append(Md.table(List.of("Attribute tested", "Fields affected", "Which fields", "Example conditions"), rows));
        }

        // 3. top to bottom: column to fields
        sb.append(Md.h(2, "From table column to output field (top to bottom)"));
        if (columnToFields.isEmpty()) {
            sb.append("_No output field traces to a table column._\n\n");
        } else {
            List<List<String>> rows = new ArrayList<>();
            columnToFields.forEach((col, fs) -> rows.add(List.of(Md.code(col), columnType.getOrDefault(col, Md.NONE), String.join(", ", fs))));
            sb.append(Md.table(List.of("Table.column", "Type", "Feeds these output fields"), rows));
        }

        // 4. the fields
        sb.append(Md.h(2, "Field details (bottom to top)"));
        sb.append("_For each field: where it sits in the file, what changes it, the path back to its table columns, then the calculation for each kind of work._\n\n");
        for (Entity x : fields) {
            sb.append("<a id=\"").append(anchor(x.name())).append("\"></a>\n\n");
            sb.append(Md.h(3, h.words(x.name())));
            sb.append(Md.bullet("Written to", fileWords.apply(x) + ", characters " + c.attr(x, "start") + " to " + c.attr(x, "end") + " (" + c.attr(x, "width") + " wide)"));
            List<LineageCards.Card> fc = cards.getOrDefault(x.name(), List.of());
            FormatRecipe.Result fr = formats.get(x.name());
            String layout = LineageCards.layout(c, x);
            if (layout == null && fr.resolved()) {
                layout = fr.layout();
            }
            sb.append(Md.bullet("Layout", layout != null ? layout : "width " + c.attr(x, "width") + "; alignment and padding are not stated by the analysis"));
            sb.append(Md.bullet("Technical name", Md.code(x.name())));
            if (fr.resolved()) {
                sb.append("- **How it is written to the file:**\n");
                for (int i = 0; i < fr.steps().size(); i++) {
                    sb.append("  ").append(i + 1).append(". ").append(fr.steps().get(i)).append('\n');
                }
                if (!fr.examples().isEmpty()) {
                    sb.append("- **Examples** _(worked through the formatting steps above; the values are illustrative, not data from your system):_\n\n");
                    sb.append(Md.table(List.of("Value before formatting", "After each step", "Text in the file", "Length"), fr.examples()));
                }
                if (fr.widthCheck() != null) {
                    sb.append(Md.bullet("Width check", fr.widthCheck()));
                }
            }
            Map<String, Set<String>> varies = new LinkedHashMap<>();
            fc.forEach(card -> card.varies().forEach((a, conds) -> varies.computeIfAbsent(a, k -> new LinkedHashSet<>()).addAll(conds)));
            if (fc.size() > 1) {
                sb.append(Md.bullet("Kind of work changes it", fc.stream().map(LineageCards.Card::scenario).collect(Collectors.joining(", "))));
            }
            if (varies.isEmpty()) {
                sb.append(Md.bullet("Changes with", "nothing: the same rule applies to every record"));
            } else {
                sb.append("- **Changes with:**\n");
                varies.forEach((a, conds) -> sb.append("  - ").append(a).append(": ").append(String.join("; ", conds)).append('\n'));
            }
            for (LineageCards.Card card : fc) {
                sb.append("- **Path back").append(fc.size() > 1 ? " (" + card.scenario() + ")" : "").append(":** ").append(pathBack(h, x, card)).append('\n');
            }
            sb.append('\n');
            for (LineageCards.Card card : fc) {
                sb.append(LineageCards.render(card, h));
            }
        }
        return sb.toString();
    }

    private static String pathBack(Humanizer h, Entity field, LineageCards.Card card) {
        List<String> chain = new ArrayList<>();
        chain.add("**" + h.words(field.name()) + "**");
        List<LineageCards.Step> reversed = new ArrayList<>(card.steps());
        Collections.reverse(reversed);
        reversed.stream().map(LineageCards.Step::name).distinct().forEach(chain::add);
        if (!card.chosen().isEmpty()) {
            chain.add("chosen by a condition");
        }
        List<String> origin = new ArrayList<>();
        card.sources().forEach(s -> origin.add(Md.code(s.key())));
        card.deciders().forEach(s -> origin.add("decided by " + Md.code(s.key())));
        card.fixed().forEach(v -> origin.add("fixed value " + v.replaceAll(" when .*$", "")));
        card.unknown().forEach(origin::add);
        chain.add(origin.isEmpty() ? "no source found" : String.join(", ", new LinkedHashSet<>(origin)));
        return String.join(" ← ", chain);
    }
}
