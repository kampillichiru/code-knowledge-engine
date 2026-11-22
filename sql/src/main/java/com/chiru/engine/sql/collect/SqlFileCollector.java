package com.chiru.engine.sql.collect;

import com.chiru.engine.model.SourceLocation;
import com.chiru.engine.sql.analysis.SqlAnalyzer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Splits {@code .sql} files into statements (keeping line numbers) and separates DDL schema from DML. */
public final class SqlFileCollector {

    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?is)^create\\s+(or\\s+replace\\s+)?(global\\s+temporary\\s+|temporary\\s+|unlogged\\s+)?table\\b.*");
    private static final Pattern DML = Pattern.compile("(?is)^(select|insert|update|delete|merge|with|call|exec|execute)\\b.*");

    /** One statement and its 1-based line range. */
    public record Piece(String text, int startLine, int endLine) {}

    public record Result(List<SqlOrigin> origins, List<SchemaDef> schemas, List<String> problems, int otherStatements) {}

    private SqlFileCollector() {}

    public static Result collect(String displayPath, String content) {
        List<SqlOrigin> origins = new ArrayList<>();
        List<SchemaDef> schemas = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        int other = 0;
        for (Piece p : split(content)) {
            SourceLocation loc = new SourceLocation(displayPath, p.startLine(), p.endLine());
            if (CREATE_TABLE.matcher(p.text()).matches()) {
                try {
                    SqlAnalyzer.tableDefinition(p.text()).ifPresentOrElse(def -> schemas.add(toSchema(def, loc)),
                            () -> problems.add(loc + ": CREATE TABLE form not understood"));
                } catch (Exception e) {
                    problems.add(loc + ": CREATE TABLE could not be parsed: " + firstLine(e));
                }
            } else if (DML.matcher(p.text()).matches()) {
                origins.add(new SqlOrigin(SqlOrigin.Kind.SQL_FILE, "file/" + displayPath + ":" + p.startLine(), loc,
                        p.text(), false, List.of(), List.of(), null));
            } else {
                other++;
            }
        }
        return new Result(origins, schemas, problems, other);
    }

    private static SchemaDef toSchema(SqlAnalyzer.TableDef def, SourceLocation loc) {
        List<SchemaDef.Col> cols = def.columns().stream()
                .map(c -> new SchemaDef.Col(c.name(), c.type(), c.specs(), false, loc)).toList();
        return new SchemaDef(def.table(), "ddl", null, false, loc, cols);
    }

    /** Splits on {@code ;} outside quotes and comments; comments are removed from the statement text. */
    public static List<Piece> split(String content) {
        List<Piece> pieces = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int line = 1;
        int start = -1;
        int i = 0;
        int n = content.length();
        while (i < n) {
            char c = content.charAt(i);
            char next = i + 1 < n ? content.charAt(i + 1) : '\0';
            if (c == '-' && next == '-') {
                while (i < n && content.charAt(i) != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '/' && next == '*') {
                i += 2;
                while (i < n && !(content.charAt(i) == '*' && i + 1 < n && content.charAt(i + 1) == '/')) {
                    if (content.charAt(i) == '\n') {
                        line++;
                    }
                    i++;
                }
                i += 2;
                continue;
            }
            if (c == '\'' || c == '"') {
                if (start < 0) {
                    start = line;
                }
                cur.append(c);
                i++;
                while (i < n) {
                    char q = content.charAt(i);
                    cur.append(q);
                    if (q == '\n') {
                        line++;
                    }
                    i++;
                    if (q == c) {
                        if (i < n && content.charAt(i) == c) { // doubled quote is an escape
                            cur.append(c);
                            i++;
                        } else {
                            break;
                        }
                    }
                }
                continue;
            }
            if (c == ';') {
                emit(pieces, cur, start, line);
                cur.setLength(0);
                start = -1;
                i++;
                continue;
            }
            if (c == '\n') {
                line++;
            }
            if (!Character.isWhitespace(c) && start < 0) {
                start = line;
            }
            cur.append(c);
            i++;
        }
        emit(pieces, cur, start, line);
        return pieces;
    }

    private static void emit(List<Piece> pieces, StringBuilder cur, int start, int endLine) {
        String text = cur.toString().strip();
        if (!text.isEmpty() && start > 0) {
            pieces.add(new Piece(text, start, endLine));
        }
    }

    private static String firstLine(Exception e) {
        String m = String.valueOf(e.getMessage());
        int nl = m.indexOf('\n');
        return nl < 0 ? m : m.substring(0, nl);
    }
}
