package com.chiru.engine.sql.collect;

import com.chiru.engine.model.SourceLocation;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Finds SQL that legacy applications keep outside Java: any XML element or attribute (Spring bean files, custom query
 * catalogues, {@code <entry key=..>}, {@code <prop key=..>}), {@code .properties} values and {@code .yml} values whose text is
 * a SQL statement. Each statement is registered under the keys that Java code is likely to use to look it up
 * ({@code id}/{@code name}/{@code key} attributes, the property key, the YAML path), so the Java stage can connect the
 * lookup call to the statement and, from there, to the code that executes it.
 */
public final class ConfigSqlCollector {

    private static final List<Pattern> SQL_SHAPES = List.of(
            Pattern.compile("(?is)^\\s*select\\b.*\\bfrom\\b.*"),
            Pattern.compile("(?is)^\\s*insert\\s+(into|ignore\\s+into|overwrite)\\b.*"),
            Pattern.compile("(?is)^\\s*update\\s+\\S+(\\s+(as\\s+)?\\w+)?\\s+set\\b.*"),
            Pattern.compile("(?is)^\\s*delete\\s+(from\\s+|\\w+\\s+from\\s+).*"),
            Pattern.compile("(?is)^\\s*merge\\s+into\\b.*"),
            Pattern.compile("(?is)^\\s*with\\b.*\\bselect\\b.*"),
            Pattern.compile("(?is)^\\s*(call|exec|execute)\\s+[\\w.$#]+.*"),
            Pattern.compile("(?is)^\\s*\\{\\s*(\\?\\s*=\\s*)?call\\s+.*}\\s*$"));
    private static final Pattern NAMED_PARAM = Pattern.compile("(?<![:\\w]):([A-Za-z_]\\w*)");

    /** @param origins every statement found; @param aliases lookup key to the origins that carry it (ambiguous when more than one). */
    public record Result(List<SqlOrigin> origins, Map<String, List<SqlOrigin>> aliases, List<String> problems) {}

    private ConfigSqlCollector() {}

    static boolean isSql(String text) {
        if (text == null) {
            return false;
        }
        String t = text.trim();
        return t.length() > 10 && SQL_SHAPES.stream().anyMatch(p -> p.matcher(t).matches());
    }

    private static SqlOrigin origin(String displayPath, String where, String key, int line, int endLine, String raw, String lookup) {
        String text = raw.replaceAll("\\s+", " ").trim();
        List<String> notes = new ArrayList<>();
        notes.add("SQL is configured outside Java (" + where + "); Java reaches it by the key \"" + lookup + "\"");
        List<String> params = new ArrayList<>();
        java.util.regex.Matcher m = NAMED_PARAM.matcher(text);
        while (m.find()) {
            params.add(m.group(1));
        }
        if (!params.isEmpty()) {
            text = NAMED_PARAM.matcher(text).replaceAll("?");
        }
        return new SqlOrigin(SqlOrigin.Kind.CONFIG, "config/" + displayPath + "#" + key,
                new SourceLocation(displayPath, line, Math.max(line, endLine)), text, false, notes, params, null);
    }

    private static void register(Map<String, List<SqlOrigin>> aliases, SqlOrigin o, Set<String> keys) {
        for (String k : keys) {
            if (k != null && !k.isBlank()) {
                List<SqlOrigin> l = aliases.computeIfAbsent(k.trim(), x -> new ArrayList<>());
                if (!l.contains(o)) {
                    l.add(o);
                }
            }
        }
    }

    // ------------------------------------------------------------------ .properties

    public static Result properties(String displayPath, String content) {
        List<SqlOrigin> origins = new ArrayList<>();
        Map<String, List<SqlOrigin>> aliases = new LinkedHashMap<>();
        String[] lines = content.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.stripLeading();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                continue;
            }
            int start = i + 1;
            StringBuilder logical = new StringBuilder(trimmed);
            while (endsWithOddBackslashes(logical)) {
                logical.setLength(logical.length() - 1);
                if (i + 1 >= lines.length) {
                    break;
                }
                logical.append(lines[++i].stripLeading());
            }
            String s = logical.toString();
            int sep = separatorIndex(s);
            if (sep < 0) {
                continue;
            }
            String key = unescape(s.substring(0, sep).trim());
            String value = unescape(s.substring(sep + 1).trim());
            if (isSql(value)) {
                SqlOrigin o = origin(displayPath, "property " + key, key, start, i + 1, value, key);
                origins.add(o);
                register(aliases, o, Set.of(key));
            }
        }
        return new Result(origins, aliases, List.of());
    }

    private static boolean endsWithOddBackslashes(CharSequence s) {
        int n = 0;
        for (int i = s.length() - 1; i >= 0 && s.charAt(i) == '\\'; i--) {
            n++;
        }
        return n % 2 == 1;
    }

    private static int separatorIndex(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '=' || c == ':' || Character.isWhitespace(c)) {
                int j = i;
                while (j < s.length() && Character.isWhitespace(s.charAt(j))) {
                    j++;
                }
                if (j < s.length() && (s.charAt(j) == '=' || s.charAt(j) == ':') && c != '=' && c != ':') {
                    return j;
                }
                return i;
            }
        }
        return -1;
    }

    private static String unescape(String s) {
        return s.replace("\\n", " ").replace("\\t", " ").replace("\\\\", "\\").replace("\\=", "=").replace("\\:", ":");
    }

    // ------------------------------------------------------------------ .yml / .yaml

    /** A deliberately small reader: nested mappings, quoted scalars, and {@code |} / {@code >} block scalars. */
    public static Result yaml(String displayPath, String content) {
        List<SqlOrigin> origins = new ArrayList<>();
        Map<String, List<SqlOrigin>> aliases = new LinkedHashMap<>();
        String[] lines = content.split("\\R", -1);
        Deque<int[]> indents = new ArrayDeque<>();
        Deque<String> path = new ArrayDeque<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String t = line.strip();
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("- ") && !t.contains(": ")) {
                if (t.startsWith("- ") && isSql(stripQuotes(t.substring(2)))) {
                    String key = String.join(".", path.reversed()) + "[]";
                    SqlOrigin o = origin(displayPath, "yaml list entry under " + key, key + "@" + (i + 1), i + 1, i + 1,
                            stripQuotes(t.substring(2)), key);
                    origins.add(o);
                }
                continue;
            }
            int indent = line.length() - line.stripLeading().length();
            int colon = t.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            while (!indents.isEmpty() && indents.peek()[0] >= indent) {
                indents.pop();
                path.pop();
            }
            String key = stripQuotes(t.substring(0, colon).trim());
            String rest = t.substring(colon + 1).trim();
            int hash = rest.indexOf(" #");
            if (hash >= 0 && !rest.startsWith("\"") && !rest.startsWith("'")) {
                rest = rest.substring(0, hash).trim();
            }
            indents.push(new int[] {indent});
            path.push(key);
            String full = String.join(".", path.reversed());
            int start = i + 1;
            String value;
            if (rest.equals("|") || rest.equals(">") || rest.matches("[|>][+-]?")) {
                StringBuilder sb = new StringBuilder();
                int blockIndent = -1;
                while (i + 1 < lines.length) {
                    String next = lines[i + 1];
                    if (next.isBlank()) {
                        i++;
                        sb.append(' ');
                        continue;
                    }
                    int ni = next.length() - next.stripLeading().length();
                    if (blockIndent < 0) {
                        blockIndent = ni;
                    }
                    if (ni < blockIndent || ni <= indent) {
                        break;
                    }
                    sb.append(next.strip()).append(' ');
                    i++;
                }
                value = sb.toString();
            } else {
                value = stripQuotes(rest);
            }
            if (isSql(value)) {
                SqlOrigin o = origin(displayPath, "yaml key " + full, full, start, i + 1, value, full);
                origins.add(o);
                register(aliases, o, Set.of(full, key));
            }
        }
        return new Result(origins, aliases, List.of());
    }

    private static String stripQuotes(String s) {
        String t = s.trim();
        if (t.length() >= 2 && (t.startsWith("\"") && t.endsWith("\"") || t.startsWith("'") && t.endsWith("'"))) {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }

    // ------------------------------------------------------------------ any other XML

    private static final class Frame {
        final String name;
        final Map<String, String> attrs;
        final int line;
        final StringBuilder text = new StringBuilder();

        Frame(String name, Map<String, String> attrs, int line) {
            this.name = name;
            this.attrs = attrs;
            this.line = line;
        }

        String ident() {
            for (String k : List.of("id", "name", "key", "code", "queryName", "ref")) {
                if (attrs.get(k) != null && !attrs.get(k).isBlank()) {
                    return attrs.get(k);
                }
            }
            return null;
        }
    }

    public static Result xml(String displayPath, InputStream in) {
        List<SqlOrigin> origins = new ArrayList<>();
        Map<String, List<SqlOrigin>> aliases = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        try {
            XMLInputFactory f = XMLInputFactory.newFactory();
            f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            XMLStreamReader r = f.createXMLStreamReader(in);
            Deque<Frame> stack = new ArrayDeque<>();
            while (r.hasNext()) {
                switch (r.next()) {
                    case XMLStreamConstants.START_ELEMENT -> {
                        Map<String, String> attrs = new LinkedHashMap<>();
                        for (int i = 0; i < r.getAttributeCount(); i++) {
                            attrs.put(r.getAttributeLocalName(i), r.getAttributeValue(i));
                        }
                        Frame fr = new Frame(r.getLocalName(), attrs, r.getLocation().getLineNumber());
                        for (Map.Entry<String, String> a : attrs.entrySet()) {
                            if (isSql(a.getValue())) {
                                Set<String> keys = keysFor(fr, stack, a.getKey(), true);
                                SqlOrigin o = origin(displayPath, "attribute " + a.getKey() + " of <" + fr.name + ">",
                                        pick(keys, fr.name + "@" + fr.line), fr.line, fr.line, a.getValue(), pick(keys, fr.name));
                                origins.add(o);
                                register(aliases, o, keys);
                            }
                        }
                        stack.push(fr);
                    }
                    case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE -> {
                        if (!stack.isEmpty()) {
                            stack.peek().text.append(r.getText());
                        }
                    }
                    case XMLStreamConstants.END_ELEMENT -> {
                        Frame fr = stack.pop();
                        String text = fr.text.toString();
                        if (isSql(text)) {
                            Set<String> keys = keysFor(fr, stack, null, false);
                            SqlOrigin o = origin(displayPath, "text of <" + fr.name + ">", pick(keys, fr.name + "@" + fr.line),
                                    fr.line, r.getLocation().getLineNumber(), text, pick(keys, fr.name));
                            origins.add(o);
                            register(aliases, o, keys);
                        }
                    }
                    default -> { }
                }
            }
            r.close();
        } catch (XMLStreamException e) {
            problems.add(displayPath + ": XML could not be read: " + e.getMessage());
        }
        return new Result(origins, aliases, problems);
    }

    /**
     * Keys a Java lookup may use: the element's own id/name/key; for {@code <property name=N value=SQL>} the name N and
     * {@code beanId.N}; for {@code <entry key=K>} / {@code <prop key=K>} the key K.
     */
    private static Set<String> keysFor(Frame fr, Deque<Frame> ancestors, String attr, boolean fromAttribute) {
        Set<String> keys = new LinkedHashSet<>();
        String own = fr.ident();
        if (own != null) {
            keys.add(own);
        }
        Frame parent = ancestors.peek();
        if (own != null && parent != null && parent.ident() != null && (fr.name.equals("property") || fr.name.equals("entry")
                || fr.name.equals("prop"))) {
            keys.add(parent.ident() + "." + own);
        }
        if (own == null && parent != null && parent.ident() != null) {
            keys.add(parent.ident() + "." + fr.name);
            keys.add(parent.ident());
        }
        if (own != null && fromAttribute && attr != null && !attr.equals("value") && !attr.equals("id") && !attr.equals("name")) {
            keys.add(own + "." + attr);
        }
        return keys;
    }

    private static String pick(Set<String> keys, String fallback) {
        return keys.isEmpty() ? fallback : keys.iterator().next();
    }

    /** Convenience for tests. */
    public static Result xml(String displayPath, String xml) {
        try (InputStream in = new java.io.ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            return xml(displayPath, in);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
