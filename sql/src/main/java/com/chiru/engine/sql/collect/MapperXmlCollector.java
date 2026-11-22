package com.chiru.engine.sql.collect;

import com.chiru.engine.model.SourceLocation;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Extracts SQL from MyBatis-style mapper XML ({@code <mapper>} with select/insert/update/delete). {@code #{x}}
 * becomes {@code ?}; {@code ${x}} and dynamic tags (if, where, foreach, ...) make the statement dynamic, in which
 * case the text is a superset of all branches and every derived fact is reported as partial.
 */
public final class MapperXmlCollector {

    private static final Set<String> STATEMENTS = Set.of("select", "insert", "update", "delete", "statement", "procedure");
    private static final Pattern HASH_PARAM = Pattern.compile("#\\{\\s*([\\w.\\[\\]]+)[^}]*}");
    /** iBatis 2 style {@code #name#} (sqlMap files). */
    private static final Pattern IBATIS_PARAM = Pattern.compile("#([A-Za-z_][\\w.]*)#");
    private static final Pattern DOLLAR_PARAM = Pattern.compile("\\$\\{\\s*([\\w.\\[\\]]+)\\s*}");

    public record Result(List<SqlOrigin> origins, List<String> problems, boolean isMapper) {}

    private static final class Node {
        final String name;
        final Map<String, String> attrs;
        final int line;
        int endLine;
        final List<Object> content = new ArrayList<>();

        Node(String name, Map<String, String> attrs, int line) {
            this.name = name;
            this.attrs = attrs;
            this.line = line;
        }
    }

    private static final class Render {
        final StringBuilder sb = new StringBuilder();
        final List<String> paramNames = new ArrayList<>();
        final List<String> notes = new ArrayList<>();
        boolean dynamic;
        int dyn;
    }

    private MapperXmlCollector() {}

    public static Result collect(String displayPath, InputStream xml) {
        List<String> problems = new ArrayList<>();
        Node root;
        try {
            root = parse(xml);
        } catch (XMLStreamException | IOException e) {
            return new Result(List.of(), List.of(displayPath + ": XML could not be read: " + e.getMessage()), false);
        }
        if (root == null || !(root.name.equals("mapper") || root.name.equals("sqlMap"))) {
            return new Result(List.of(), problems, false);
        }
        String namespace = root.attrs.getOrDefault("namespace", "");
        Map<String, Node> fragments = new HashMap<>();
        for (Object o : root.content) {
            if (o instanceof Node n && n.name.equals("sql") && n.attrs.containsKey("id")) {
                fragments.put(n.attrs.get("id"), n);
            }
        }
        List<SqlOrigin> origins = new ArrayList<>();
        for (Object o : root.content) {
            if (!(o instanceof Node stmt) || !STATEMENTS.contains(stmt.name)) {
                continue;
            }
            String id = stmt.attrs.getOrDefault("id", "line" + stmt.line);
            Render r = new Render();
            render(stmt, fragments, r, 0);
            String text = tidy(r.sb.toString());
            List<String> notes = new ArrayList<>(r.notes);
            if (r.dynamic) {
                notes.add("dynamic mapper SQL: the text merges every branch and may not match any single runtime variant");
            }
            origins.add(new SqlOrigin(SqlOrigin.Kind.XML,
                    "xml/" + displayPath + "#" + (namespace.isEmpty() ? "" : namespace + ".") + id,
                    new SourceLocation(displayPath, stmt.line, Math.max(stmt.endLine, stmt.line)),
                    text, r.dynamic, notes, r.paramNames, null));
        }
        return new Result(origins, problems, true);
    }

    private static Node parse(InputStream in) throws XMLStreamException, IOException {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        XMLStreamReader r = f.createXMLStreamReader(in);
        Deque<Node> stack = new ArrayDeque<>();
        Node root = null;
        while (r.hasNext()) {
            switch (r.next()) {
                case XMLStreamConstants.START_ELEMENT -> {
                    Map<String, String> attrs = new LinkedHashMap<>();
                    for (int i = 0; i < r.getAttributeCount(); i++) {
                        attrs.put(r.getAttributeLocalName(i), r.getAttributeValue(i));
                    }
                    Node n = new Node(r.getLocalName(), attrs, r.getLocation().getLineNumber());
                    if (stack.isEmpty()) {
                        root = n;
                    } else {
                        stack.peek().content.add(n);
                    }
                    stack.push(n);
                }
                case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE -> {
                    if (!stack.isEmpty()) {
                        stack.peek().content.add(r.getText());
                    }
                }
                case XMLStreamConstants.END_ELEMENT -> {
                    Node n = stack.pop();
                    n.endLine = r.getLocation().getLineNumber();
                }
                default -> { }
            }
        }
        r.close();
        return root;
    }

    private static void render(Node n, Map<String, Node> fragments, Render r, int depth) {
        for (Object o : n.content) {
            if (o instanceof String s) {
                r.sb.append(convert(s, r));
                continue;
            }
            Node child = (Node) o;
            switch (child.name) {
                case "include" -> {
                    Node frag = fragments.get(child.attrs.get("refid"));
                    if (frag == null || depth > 10) {
                        r.dynamic = true;
                        r.sb.append(" __DYN_").append(++r.dyn).append("__ ");
                        r.notes.add("<include refid=\"" + child.attrs.get("refid") + "\"> could not be resolved");
                    } else {
                        render(frag, fragments, r, depth + 1);
                    }
                }
                case "where" -> {
                    r.dynamic = true;
                    r.sb.append(" WHERE ");
                    render(child, fragments, r, depth + 1);
                }
                case "set" -> {
                    r.dynamic = true;
                    r.sb.append(" SET ");
                    render(child, fragments, r, depth + 1);
                }
                case "trim" -> {
                    r.dynamic = true;
                    r.sb.append(' ').append(child.attrs.getOrDefault("prefix", "")).append(' ');
                    render(child, fragments, r, depth + 1);
                    r.sb.append(' ').append(child.attrs.getOrDefault("suffix", "")).append(' ');
                }
                case "if", "when", "otherwise", "choose", "foreach" -> {
                    r.dynamic = true;
                    render(child, fragments, r, depth + 1);
                }
                case "bind", "selectKey" -> r.dynamic = true;
                default -> {
                    r.dynamic = true;
                    r.notes.add("unsupported mapper element <" + child.name + "> was rendered as plain text");
                    render(child, fragments, r, depth + 1);
                }
            }
        }
    }

    private static String convert(String text, Render r) {
        Matcher dollar = DOLLAR_PARAM.matcher(text);
        StringBuilder out = new StringBuilder();
        while (dollar.find()) {
            r.dynamic = true;
            r.notes.add("${" + dollar.group(1) + "} substitutes raw text into the SQL");
            dollar.appendReplacement(out, Matcher.quoteReplacement("__DYN_" + (++r.dyn) + "__"));
        }
        dollar.appendTail(out);
        Matcher ib = IBATIS_PARAM.matcher(out.toString());
        StringBuilder out1 = new StringBuilder();
        while (ib.find()) {
            r.paramNames.add(ib.group(1));
            ib.appendReplacement(out1, "?");
        }
        ib.appendTail(out1);
        Matcher hash = HASH_PARAM.matcher(out1.toString());
        StringBuilder out2 = new StringBuilder();
        while (hash.find()) {
            r.paramNames.add(hash.group(1));
            hash.appendReplacement(out2, "?");
        }
        hash.appendTail(out2);
        return out2.toString();
    }

    /** Collapses whitespace and repairs the leading AND/OR and trailing comma that dynamic tags would trim. */
    private static String tidy(String sql) {
        String s = sql.replaceAll("\\s+", " ").trim();
        s = s.replaceAll("(?i)\\bWHERE\\s+(AND|OR)\\b", "WHERE");
        s = s.replaceAll("(?i)\\bSET\\s*,", "SET");
        s = s.replaceAll(",\\s*(?=WHERE\\b|$)", " ");
        return s.replaceAll("\\s+", " ").trim();
    }
}
