package com.chiru.engine.docs;

import java.util.List;

/** Small Markdown helpers. Output is deterministic: no timestamps, no host paths, stable ordering is the caller's job. */
final class Md {

    private static final int CELL_LIMIT = 420;
    static final String NONE = "—";

    private Md() {}

    /** Makes text safe inside a table cell: no pipes, no line breaks, bounded length. */
    static String cell(String s) {
        if (s == null || s.isBlank()) {
            return NONE;
        }
        String flat = s.replace("\r", " ").replace("\n", " ").replaceAll("\\s+", " ").trim()
                .replace("|", "\\|").replace("<", "&lt;").replace(">", "&gt;");
        return flat.length() <= CELL_LIMIT ? flat : flat.substring(0, CELL_LIMIT) + "…";
    }

    static String table(List<String> headers, List<List<String>> rows) {
        if (rows.isEmpty()) {
            return "_None found._\n\n";
        }
        StringBuilder sb = new StringBuilder("| ").append(String.join(" | ", headers)).append(" |\n|");
        headers.forEach(h -> sb.append(" --- |"));
        sb.append('\n');
        for (List<String> row : rows) {
            sb.append("| ");
            for (int i = 0; i < row.size(); i++) {
                sb.append(i == 0 ? "" : " | ").append(cell(row.get(i)));
            }
            sb.append(" |\n");
        }
        return sb.append('\n').toString();
    }

    /** Inline code that survives backticks in the text. */
    static String code(String s) {
        if (s == null || s.isBlank()) {
            return NONE;
        }
        String flat = s.replaceAll("\\s+", " ").trim();
        return flat.contains("`") ? "`` " + flat + " ``" : "`" + flat + "`";
    }

    /** A fenced block; the fence is longer than any backtick run inside. */
    static String block(String lang, String text) {
        String fence = "```";
        while (text.contains(fence)) {
            fence += "`";
        }
        return fence + lang + "\n" + text.stripTrailing() + "\n" + fence + "\n\n";
    }

    static String h(int level, String title) {
        return "#".repeat(level) + " " + title + "\n\n";
    }

    static String bullet(String label, String value) {
        return "- **" + label + ":** " + (value == null || value.isBlank() ? NONE : value) + "\n";
    }
}
